package com.dharmeshbpatel

import grails.transaction.Transactional
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.Reservation

@Transactional
class AwsService {

    static final int INSTANCE_TYPE = 0
    static final int AVAILABILITY_ZONE = 1
    static final int INSTANCE_COUNT = 2
    static final int RESERVED_COUNT = 3
    static final int DIFFERENCE = 4

    static final int MOVEMENT_INSTANCE_TYPE = 0
    static final int MOVEMENT_AVAILABILITY_ZONE = 1
    static final int MOVEMENT_DIFF = 2

    def amazonWebService

    def optimizeReservedInstanceUtilization(dryRun = true) {
        def activeInstances = getActiveInstances()
        def activeReservedInstances = getActiveReservedInstances()

        def currentState = getReservedState(activeInstances, activeReservedInstances)

        def totalActiveInstances = activeInstances.size() ?: 0
        def totalActiveReservedInstances = activeReservedInstances.size() ?: 0

        log.info("Total Active Instances - $totalActiveInstances")
        log.info("Total Purchased Reserved Instances - $totalActiveReservedInstances")
        printAllocationTable(currentState, "Current")

        def optimizedState = optimize(currentState)
        printAllocationTable(optimizedState, "Optimized")

        if (! dryRun) {
            // TODO update reserved instances
        }
    }

    /**
     * AWS call to get active instances
     */
    def getActiveInstances() {
        def activeInstances = []
        List reservations = amazonWebService.ec2.describeInstances().reservations
        reservations.each { Reservation reservation ->
            reservation.instances.each() { Instance instance ->
                if (instance.state.getName().equalsIgnoreCase("running")) {
                    activeInstances << instance
                }
            }
        }

        return activeInstances
    }

    /**
     * AWS call to get purchased reserved instances
     */
    def getActiveReservedInstances() {
        def reservedInstances = amazonWebService.ec2.describeReservedInstances().reservedInstances
        def activeReservedInstances = reservedInstances.findAll { it.state.equalsIgnoreCase("active") }
        return activeReservedInstances
    }

    /**
     * Get a list of count differences between existing instances and purchased reserved instances
     */
    def getReservedState(activeInstances, activeReservedInstances) {
        def activeInstanceGroup = activeInstances.groupBy({it.instanceType}, {it.placement.availabilityZone})
        def reservedInstanceGroup = activeReservedInstances.groupBy({it.instanceType}, {it.availabilityZone})

        def output = buildReservedState(activeInstanceGroup, reservedInstanceGroup)

        return output
    }

    /**
     * This modifies the "state" for what it would look optimally
     */
    def optimize(state) {
        if (state.size() == 0) {
            log.error("I got nothing to work with.")
            return
        }

        def unbalancedInstances = state.collect { it }

        while (shouldReallocate(findUnbalancedInstances(unbalancedInstances))) {
            unbalancedInstances = findUnbalancedInstances(unbalancedInstances)

            if (unbalancedInstances.size() > 0) {
                def largestPositive = findLargestPostiveDiff(unbalancedInstances)
                def type = largestPositive[INSTANCE_TYPE]
                def largestNegative = findLargestNegativeDiff(unbalancedInstances, type)

                def typeReallocations = reallocate(type, largestPositive, largestNegative)

                unbalancedInstances = recalculateState(unbalancedInstances, typeReallocations)
            } else {
                break
            }
        }

        return state
    }

    /**
     * NOTE: These changes will impact original list since sourceList contents are
     * references
     */
    def recalculateState(sourceList, reallocations) {
        reallocations.each { reallocation ->
            def src = sourceList.find {
                it[INSTANCE_TYPE] == reallocation[MOVEMENT_INSTANCE_TYPE] &&
                it[AVAILABILITY_ZONE] == reallocation[MOVEMENT_AVAILABILITY_ZONE]
            }

            if (src) {
                src[RESERVED_COUNT] = src[RESERVED_COUNT] + reallocation[MOVEMENT_DIFF]
                src[DIFFERENCE] = src[DIFFERENCE] + reallocation[MOVEMENT_DIFF]
            }
        }

        return sourceList
    }

    def findLargestPostiveDiff(balanceableListForType) {
        balanceableListForType.max { it[DIFFERENCE] }
    }

    def findLargestNegativeDiff(balanceableListForType, type) {
        balanceableListForType.findAll { it[INSTANCE_TYPE] == type }.min { it[DIFFERENCE] }
    }

    def findUnbalancedInstances(balanceableList) {
        return balanceableList.findAll { it[DIFFERENCE] != 0 }
    }

    def reallocate(type, source, destination) {
        def reallocations = []
        def sourceAvailable = Math.abs(source[DIFFERENCE])
        def destinationNeeded = Math.abs(destination[DIFFERENCE])

        if (sourceAvailable > destinationNeeded) {
            reallocations << [type, source[AVAILABILITY_ZONE], -(destinationNeeded)]
            reallocations << [type, destination[AVAILABILITY_ZONE], destinationNeeded]
        } else if (destinationNeeded > sourceAvailable) {
            reallocations << [type, source[AVAILABILITY_ZONE], -(sourceAvailable)]
            reallocations << [type, destination[AVAILABILITY_ZONE], sourceAvailable]
        } else {  // Even move perfect, I give it a 5/7
            reallocations << [type, source[AVAILABILITY_ZONE], -(sourceAvailable)]
            reallocations << [type, destination[AVAILABILITY_ZONE], destinationNeeded]
        }

        return reallocations
    }

    /**
     * Check if all in positive or all in negative, if not then continue balancing
     */
    def shouldReallocate(balanceableList) {
        def balanceableListByType = balanceableList.groupBy { it[INSTANCE_TYPE] }

        return balanceableListByType.find { type, _balanceableList ->
           (_balanceableList.findAll { it[DIFFERENCE] >= 0 }.size()) != _balanceableList.size() &&
           (_balanceableList.findAll { it[DIFFERENCE] <= 0 }.size() != _balanceableList.size())
        }
    }

    /**
     * Returns rows in format [instance type, aws zone, count of instances, count of reserved instances, diff]
     */
    def buildReservedState(instanceGroup, reserveGroup) {
        def output = []

        def typeKeys = instanceGroup?.keySet() + reserveGroup?.keySet()

        typeKeys.each { typeKey ->
            def zoneKeys = instanceGroup[typeKey]?.keySet()
            if (reserveGroup[typeKey]?.keySet()) {
                zoneKeys = zoneKeys + reserveGroup[typeKey]?.keySet()
            }

            zoneKeys.each { zoneKey ->
                def reservedCount = 0
                if (reserveGroup[typeKey] && reserveGroup[typeKey][zoneKey]) {
                    reservedCount = reserveGroup[typeKey][zoneKey].instanceCount[0]
                }

                def diff = reservedCount - (instanceGroup[typeKey][zoneKey]?.size() ?: 0)

                def instanceCount = 0

                if (instanceGroup[typeKey] && instanceGroup[typeKey][zoneKey]) {
                    instanceCount = instanceGroup[typeKey][zoneKey]?.size()
                }

                output << [typeKey, zoneKey, instanceCount, reservedCount, diff]
            }
        }

        return output
    }

    def printAllocationTable(output, shortDescription = "") {
        def defaultPad = 15

        String outputString = "Instance Type".padRight(defaultPad) + "Aws Zone".padLeft(defaultPad) + "Current Count".padLeft(defaultPad) + "Reserved Avail".padLeft(defaultPad) + "Diff".padLeft(defaultPad) + "\n"
        output.each { row ->
            outputString = outputString +
                    row[INSTANCE_TYPE].padRight(defaultPad) +
                    row[AVAILABILITY_ZONE].padLeft(defaultPad) +
                    row[INSTANCE_COUNT].toString().padLeft(defaultPad) +
                    row[RESERVED_COUNT].toString().padLeft(defaultPad) +
                    row[DIFFERENCE].toString().padLeft(defaultPad) +
                    "\n"
        }

        def title = "******************** AWS Reserved Instance (${shortDescription}) ***********************"
        log.info(title)
        log.info("\n" + outputString + "\n")
        log.info("*".multiply(title.length()))
    }
}
