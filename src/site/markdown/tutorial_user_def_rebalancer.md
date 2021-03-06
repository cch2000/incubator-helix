<!---
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
# Tutorial: User-Defined Rebalancing

Even though Helix can compute both the location and the state of replicas internally using a default fully-automatic rebalancer, specific applications may require rebalancing strategies that optimize for different requirements. Thus, Helix allows applications to plug in arbitrary rebalancer algorithms that implement a provided interface. One of the main design goals of Helix is to provide maximum flexibility to any distributed application. Thus, it allows applications to fully implement the rebalancer, which is the core constraint solver in the system, if the application developer so chooses.

Whenever the state of the cluster changes, as is the case when participants join or leave the cluster, Helix automatically calls the rebalancer to compute a new mapping of all the replicas in the resource. When using a pluggable rebalancer, the only required step is to register it with Helix. Subsequently, no additional bootstrapping steps are necessary. Helix uses reflection to look up and load the class dynamically at runtime. As a result, it is also technically possible to change the rebalancing strategy used at any time.

The Rebalancer interface is as follows:

```
ResourceMapping computeResourceMapping(final Resource resource,
      final IdealState currentIdealState, final CurrentStateOutput currentStateOutput,
      final ClusterDataCache clusterData);
```
The first parameter is the resource to rebalance, the second is pre-existing ideal mappings, the third is a snapshot of the actual placements and state assignments, and the fourth is a full cache of all of the cluster data available to Helix. Internally, Helix implements the same interface for its own rebalancing routines, so a user-defined rebalancer will be cognizant of the same information about the cluster as an internal implementation. Helix strives to provide applications the ability to implement algorithms that may require a large portion of the entire state of the cluster to make the best placement and state assignment decisions possible.

A ResourceMapping is a full representation of the location and the state of each replica of each partition of a given resource. This is a simple representation of the placement that the algorithm believes is the best possible. If the placement meets all defined constraints, this is what will become the actual state of the distributed system.

### Specifying a Rebalancer
For implementations that set up the cluster through existing code, the following HelixAdmin calls will update the Rebalancer class:

```
IdealState idealState = helixAdmin.getResourceIdealState(clusterName, resourceName);
idealState.setRebalanceMode(RebalanceMode.USER_DEFINED);
idealState.setRebalancerClassName(className);
helixAdmin.setResourceIdealState(clusterName, resourceName, idealState);
```
There are two key fields to set to specify that a pluggable rebalancer should be used. First, the rebalance mode should be set to USER_DEFINED, and second the rebalancer class name should be set to a class that implements Rebalancer and is within the scope of the project. The class name is a fully-qualified class name consisting of its package and its name. Without specification of the USER_DEFINED mode, the user-defined rebalancer class will not be used even if specified. Furthermore, Helix will not attempt to rebalance the resources through its standard routines if its mode is USER_DEFINED, regardless of whether or not a rebalancer class is registered.

Alternatively, the rebalancer class name can be specified in a YAML file representing the cluster configuration. The requirements are the same, but the representation is more compact. Below are the first few lines of an example YAML file. To see a full YAML specification, see the [YAML tutorial](./tutorial_yaml.html).

```
clusterName: lock-manager-custom-rebalancer # unique name for the cluster
resources:
  - name: lock-group # unique resource name
    rebalancer: # we will provide our own rebalancer
      mode: USER_DEFINED
      class: domain.project.helix.rebalancer.UserDefinedRebalancerClass
...
```

### Example
We demonstrate plugging in a simple user-defined rebalancer as part of a revisit of the [distributed lock manager](./recipes/user_def_rebalancer.html) example. It includes a functional Rebalancer implementation, as well as the entire YAML file used to define the cluster.

Consider the case where partitions are locks in a lock manager and 6 locks are to be distributed evenly to a set of participants, and only one participant can hold each lock. We can define a rebalancing algorithm that simply takes the modulus of the lock number and the number of participants to evenly distribute the locks across participants. Helix allows capping the number of partitions a participant can accept, but since locks are lightweight, we do not need to define a restriction in this case. The following is a succinct implementation of this algorithm.

```
@Override
public ResourceAssignment computeResourceMapping(Resource resource, IdealState currentIdealState,
    CurrentStateOutput currentStateOutput, ClusterDataCache clusterData) {
  // Initialize an empty mapping of locks to participants
  ResourceAssignment assignment = new ResourceAssignment(resource.getResourceName());

  // Get the list of live participants in the cluster
  List<String> liveParticipants = new ArrayList<String>(clusterData.getLiveInstances().keySet());

  // Get the state model (should be a simple lock/unlock model) and the highest-priority state
  String stateModelName = currentIdealState.getStateModelDefRef();
  StateModelDefinition stateModelDef = clusterData.getStateModelDef(stateModelName);
  if (stateModelDef.getStatesPriorityList().size() < 1) {
    LOG.error("Invalid state model definition. There should be at least one state.");
    return assignment;
  }
  String lockState = stateModelDef.getStatesPriorityList().get(0);

  // Count the number of participants allowed to lock each lock
  String stateCount = stateModelDef.getNumInstancesPerState(lockState);
  int lockHolders = 0;
  try {
    // a numeric value is a custom-specified number of participants allowed to lock the lock
    lockHolders = Integer.parseInt(stateCount);
  } catch (NumberFormatException e) {
    LOG.error("Invalid state model definition. The lock state does not have a valid count");
    return assignment;
  }

  // Fairly assign the lock state to the participants using a simple mod-based sequential
  // assignment. For instance, if each lock can be held by 3 participants, lock 0 would be held
  // by participants (0, 1, 2), lock 1 would be held by (1, 2, 3), and so on, wrapping around the
  // number of participants as necessary.
  // This assumes a simple lock-unlock model where the only state of interest is which nodes have
  // acquired each lock.
  int i = 0;
  for (Partition partition : resource.getPartitions()) {
    Map<String, String> replicaMap = new HashMap<String, String>();
    for (int j = i; j < i + lockHolders; j++) {
      int participantIndex = j % liveParticipants.size();
      String participant = liveParticipants.get(participantIndex);
      // enforce that a participant can only have one instance of a given lock
      if (!replicaMap.containsKey(participant)) {
        replicaMap.put(participant, lockState);
      }
    }
    assignment.addReplicaMap(partition, replicaMap);
    i++;
  }
  return assignment;
}
```

Here is the ResourceMapping emitted by the user-defined rebalancer for a 3-participant system whenever there is a change to the set of participants.

* Participant_A joins

```
{
  "lock_0": { "Participant_A": "LOCKED"},
  "lock_1": { "Participant_A": "LOCKED"},
  "lock_2": { "Participant_A": "LOCKED"},
  "lock_3": { "Participant_A": "LOCKED"},
  "lock_4": { "Participant_A": "LOCKED"},
  "lock_5": { "Participant_A": "LOCKED"},
}
```

A ResourceMapping is a mapping for each resource of partition to the participant serving each replica and the state of each replica. The state model is a simple LOCKED/RELEASED model, so participant A holds all lock partitions in the LOCKED state.

* Participant_B joins

```
{
  "lock_0": { "Participant_A": "LOCKED"},
  "lock_1": { "Participant_B": "LOCKED"},
  "lock_2": { "Participant_A": "LOCKED"},
  "lock_3": { "Participant_B": "LOCKED"},
  "lock_4": { "Participant_A": "LOCKED"},
  "lock_5": { "Participant_B": "LOCKED"},
}
```

Now that there are two participants, the simple mod-based function assigns every other lock to the second participant. On any system change, the rebalancer is invoked so that the application can define how to redistribute its resources.

* Participant_C joins (steady state)

```
{
  "lock_0": { "Participant_A": "LOCKED"},
  "lock_1": { "Participant_B": "LOCKED"},
  "lock_2": { "Participant_C": "LOCKED"},
  "lock_3": { "Participant_A": "LOCKED"},
  "lock_4": { "Participant_B": "LOCKED"},
  "lock_5": { "Participant_C": "LOCKED"},
}
```

This is the steady state of the system. Notice that four of the six locks now have a different owner. That is because of the naïve modulus-based assignmemt approach used by the user-defined rebalancer. However, the interface is flexible enough to allow you to employ consistent hashing or any other scheme if minimal movement is a system requirement.

* Participant_B fails

```
{
  "lock_0": { "Participant_A": "LOCKED"},
  "lock_1": { "Participant_C": "LOCKED"},
  "lock_2": { "Participant_A": "LOCKED"},
  "lock_3": { "Participant_C": "LOCKED"},
  "lock_4": { "Participant_A": "LOCKED"},
  "lock_5": { "Participant_C": "LOCKED"},
}
```

On any node failure, as in the case of node addition, the rebalancer is invoked automatically so that it can generate a new mapping as a response to the change. Helix ensures that the Rebalancer has the opportunity to reassign locks as required by the application.

* Participant_B (or the replacement for the original Participant_B) rejoins

```
{
  "lock_0": { "Participant_A": "LOCKED"},
  "lock_1": { "Participant_B": "LOCKED"},
  "lock_2": { "Participant_C": "LOCKED"},
  "lock_3": { "Participant_A": "LOCKED"},
  "lock_4": { "Participant_B": "LOCKED"},
  "lock_5": { "Participant_C": "LOCKED"},
}
```

The rebalancer was invoked once again and the resulting ResourceMapping reflects the steady state.

### Caveats
- The rebalancer class must be available at runtime, or else Helix will not attempt to rebalance at all