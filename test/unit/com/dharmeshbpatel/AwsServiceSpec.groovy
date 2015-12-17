package com.dharmeshbpatel

import grails.test.mixin.TestFor
import spock.lang.Specification

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.ReservedInstances

@TestFor(AwsService)
class AwsServiceSpec extends Specification {

    void 'Test evenly reallocate'() {
        given: '4 instances & 4 reservations'
            def instance1 = buildInstance('m3.medium', 'us-east-1a')
            def instance2 = buildInstance('m3.medium', 'us-east-1c')
            def instance3 = buildInstance('m3.medium', 'us-east-1d')
            def instance4 = buildInstance('m3.medium', 'us-east-1e')
            def reservedInstance1 = buildReservedInstance(1, 'm3.medium', 'us-east-1a', 2)
            def reservedInstance2 = buildReservedInstance(1, 'm3.medium', 'us-east-1c', 2)

            def activeInstances = [instance1, instance2, instance3, instance4]
            def reservedInstances = [reservedInstance1, reservedInstance2]

        when: 'Reallocate'
            def currentState = service.getReservedState(activeInstances, reservedInstances)
            service.printAllocationTable(currentState, "Current")
            def optimizedState = service.optimize(currentState)
            service.printAllocationTable(optimizedState, "Optimized")
            println optimizedState

        then: 'Move each reservation to each zone'
            optimizedState.size() == 4
            optimizedState.contains(["m3.medium", "us-east-1a", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1c", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1d", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1e", 1, 1, 0])
    }

    void 'Test reallocation with extra servers'() {
        given: '4 instances & 3 reservations'
            def instance1 = buildInstance('m3.medium', 'us-east-1a')
            def instance2 = buildInstance('m3.medium', 'us-east-1c')
            def instance3 = buildInstance('m3.medium', 'us-east-1d')
            def instance4 = buildInstance('m3.medium', 'us-east-1e')
            def reservedInstance1 = buildReservedInstance(1, 'm3.medium', 'us-east-1a', 2)
            def reservedInstance2 = buildReservedInstance(1, 'm3.medium', 'us-east-1c', 1)

            def activeInstances = [instance1, instance2, instance3, instance4]
            def reservedInstances = [reservedInstance1, reservedInstance2]

        when: 'Reallocate'
            def currentState = service.getReservedState(activeInstances, reservedInstances)
            service.printAllocationTable(currentState, "Current")
            def optimizedState = service.optimize(currentState)
            service.printAllocationTable(optimizedState, "Optimized")

        then: 'Move each reservation to each zone'
            optimizedState.size() == 4
            optimizedState.contains(["m3.medium", "us-east-1a", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1c", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1d", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1e", 1, 0, -1])
    }

    void 'Test reallocation with wasted reserve'() {
        given: '4 instances & 5 reservations'
            def instance1 = buildInstance('m3.medium', 'us-east-1a')
            def instance2 = buildInstance('m3.medium', 'us-east-1c')
            def instance3 = buildInstance('m3.medium', 'us-east-1d')
            def instance4 = buildInstance('m3.medium', 'us-east-1e')
            def reservedInstance1 = buildReservedInstance(1, 'm3.medium', 'us-east-1a', 2)
            def reservedInstance2 = buildReservedInstance(1, 'm3.medium', 'us-east-1c', 3)

            def activeInstances = [instance1, instance2, instance3, instance4]
            def reservedInstances = [reservedInstance1, reservedInstance2]

        when: 'Reallocate'
            def currentState = service.getReservedState(activeInstances, reservedInstances)
            service.printAllocationTable(currentState, "Current")
            def optimizedState = service.optimize(currentState)
            service.printAllocationTable(optimizedState, "Optimized")

        then: 'Move each reservation to each zone'
            optimizedState.size() == 4
            optimizedState.contains(["m3.medium", "us-east-1a", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1c", 1, 2, 1])
            optimizedState.contains(["m3.medium", "us-east-1d", 1, 1, 0])
            optimizedState.contains(["m3.medium", "us-east-1e", 1, 1, 0])
    }


    void 'Test reallocation with multiple types'() {
        given: '4 instances & 5 reservations'
        def instance1 = buildInstance('m3.medium', 'us-east-1a')
        def instance2 = buildInstance('m3.medium', 'us-east-1c')
        def instance3 = buildInstance('m3.medium', 'us-east-1d')
        def instance4 = buildInstance('m4.medium', 'us-east-1e')
        def reservedInstance1 = buildReservedInstance(1, 'm3.medium', 'us-east-1a', 2)
        def reservedInstance2 = buildReservedInstance(1, 'm3.medium', 'us-east-1c', 3)

        def activeInstances = [instance1, instance2, instance3, instance4]
        def reservedInstances = [reservedInstance1, reservedInstance2]

        when: 'Reallocate'
            def currentState = service.getReservedState(activeInstances, reservedInstances)
            service.printAllocationTable(currentState, "Current")
            def optimizedState = service.optimize(currentState)
            service.printAllocationTable(optimizedState, "Optimized")

        then: 'Move each reservation to each zone'
            optimizedState.size() == 4
            optimizedState.contains(["m3.medium", "us-east-1a", 1, 2, 1])
            optimizedState.contains(["m3.medium", "us-east-1c", 1, 2, 1])
            optimizedState.contains(["m3.medium", "us-east-1d", 1, 1, 0])
            optimizedState.contains(["m4.medium", "us-east-1e", 1, 0, -1])
    }

    void 'Test reallocations'() {
        given: 'Unbalanced reserve count'
            def balanceableList = [
                ['m3.medium', 'us-east-1a', 2, 1, -1],
                ['m3.medium', 'us-east-1c', 10, 1, -9],
                ['m3.medium', 'us-east-1d', 2, 5, 3],
                ['m3.medium', 'us-east-1e', 2, 1, -1],
            ]
        when: 'Try to balance'
            def largestPositive = service.findLargestPostiveDiff(balanceableList)
            def largestNegative = service.findLargestNegativeDiff(balanceableList, largestPositive[0])
            def typeReallocations = service.reallocate("m3.medium", largestPositive, largestNegative)

        then: 'Verify reallocations'
            typeReallocations.contains(['m3.medium', 'us-east-1d', -3])
            typeReallocations.contains(['m3.medium', 'us-east-1c', 3])
    }

    void 'Find largest positive diff'() {
        given: '3 allocations'
            def balanceableList = [
                ['m3.medium', 'us-east-1a', 2, 1, -1],
                ['m3.medium', 'us-east-1c', 10, 1, -9],
                ['m3.medium', 'us-east-1d', 2, 5, 3],
                ['m3.medium', 'us-east-1e', 2, 1, -1],
            ]

        when: 'Finding largest positive diff'
            def largestPositive = service.findLargestPostiveDiff(balanceableList)

        then: 'Confirm zone us-east-1d'
            largestPositive[4] == 3
    }

    void 'Find largest negative diff'() {
        given: '3 allocations'
            def balanceableList = [
                ['m3.medium', 'us-east-1a', 2, 1, -1],
                ['m3.medium', 'us-east-1c', 10, 1, -9],
                ['m3.medium', 'us-east-1d', 2, 5, 3],
                ['m3.medium', 'us-east-1e', 2, 1, -1],
            ]

        when: 'Finding largest negative diff'
            def largestNegative = service.findLargestNegativeDiff(balanceableList, 'm3.medium')

        then: 'Confirm zone us-east-1c'
            largestNegative[4] == -9
    }

    private def buildInstance(type, availabilityZone) {
        def instance = new Instance()
        def placement = new Placement()

        instance.instanceType = type
        placement.availabilityZone = availabilityZone
        instance.placement = placement

        return instance
    }

    private def buildReservedInstance(id, type, availabilityZone, count) {
        def reservedInstance = new ReservedInstances()

        reservedInstance.reservedInstancesId = id
        reservedInstance.instanceType = type
        reservedInstance.availabilityZone = availabilityZone
        reservedInstance.instanceCount = count

        return reservedInstance
    }
}
