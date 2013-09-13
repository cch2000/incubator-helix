package org.apache.helix.controller.rebalancer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.helix.HelixDefinedState;
import org.apache.helix.api.Cluster;
import org.apache.helix.api.CustomRebalancerConfig;
import org.apache.helix.api.Participant;
import org.apache.helix.api.ParticipantId;
import org.apache.helix.api.PartitionId;
import org.apache.helix.api.State;
import org.apache.helix.controller.rebalancer.util.NewConstraintBasedAssignment;
import org.apache.helix.controller.stages.ResourceCurrentState;
import org.apache.helix.model.ResourceAssignment;
import org.apache.helix.model.StateModelDefinition;
import org.apache.log4j.Logger;

/**
 * This is a Rebalancer specific to custom mode. It is tasked with checking an existing mapping of
 * partitions against the set of live instances to mark assignment states as dropped or erroneous
 * as necessary.
 * The input is the required current assignment of partitions to instances, as well as the required
 * existing instance preferences.
 * The output is a verified mapping based on that preference list, i.e. partition p has a replica
 * on node k with state s, where s may be a dropped or error state if necessary.
 */
public class NewCustomRebalancer implements NewRebalancer<CustomRebalancerConfig> {

  private static final Logger LOG = Logger.getLogger(NewCustomRebalancer.class);

  @Override
  public ResourceAssignment computeResourceMapping(CustomRebalancerConfig config, Cluster cluster,
      ResourceCurrentState currentState) {
    StateModelDefinition stateModelDef =
        cluster.getStateModelMap().get(config.getStateModelDefId());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing resource:" + config.getResourceId());
    }
    ResourceAssignment partitionMapping = new ResourceAssignment(config.getResourceId());
    for (PartitionId partition : config.getPartitionSet()) {
      Map<ParticipantId, State> currentStateMap =
          currentState.getCurrentStateMap(config.getResourceId(), partition);
      Set<ParticipantId> disabledInstancesForPartition =
          NewConstraintBasedAssignment.getDisabledParticipants(cluster.getParticipantMap(),
              partition);
      Map<ParticipantId, State> bestStateForPartition =
          computeCustomizedBestStateForPartition(cluster.getLiveParticipantMap(), stateModelDef,
              config.getPreferenceMap(partition), currentStateMap, disabledInstancesForPartition);
      partitionMapping.addReplicaMap(partition, bestStateForPartition);
    }
    return partitionMapping;
  }

  /**
   * compute best state for resource in CUSTOMIZED rebalancer mode
   * @param liveParticipantMap
   * @param stateModelDef
   * @param idealStateMap
   * @param currentStateMap
   * @param disabledParticipantsForPartition
   * @return
   */
  private Map<ParticipantId, State> computeCustomizedBestStateForPartition(
      Map<ParticipantId, Participant> liveParticipantMap, StateModelDefinition stateModelDef,
      Map<ParticipantId, State> idealStateMap, Map<ParticipantId, State> currentStateMap,
      Set<ParticipantId> disabledParticipantsForPartition) {
    Map<ParticipantId, State> participantStateMap = new HashMap<ParticipantId, State>();

    // if the resource is deleted, idealStateMap will be null/empty and
    // we should drop all resources.
    if (currentStateMap != null) {
      for (ParticipantId participantId : currentStateMap.keySet()) {
        if ((idealStateMap == null || !idealStateMap.containsKey(participantId))
            && !disabledParticipantsForPartition.contains(participantId)) {
          // if dropped and not disabled, transit to DROPPED
          participantStateMap.put(participantId, State.from(HelixDefinedState.DROPPED));
        } else if ((currentStateMap.get(participantId) == null || !currentStateMap.get(
            participantId).equals(State.from(HelixDefinedState.ERROR)))
            && disabledParticipantsForPartition.contains(participantId)) {
          // if disabled and not in ERROR state, transit to initial-state (e.g. OFFLINE)
          participantStateMap.put(participantId, stateModelDef.getInitialState());
        }
      }
    }

    // ideal state is deleted
    if (idealStateMap == null) {
      return participantStateMap;
    }

    for (ParticipantId participantId : idealStateMap.keySet()) {
      boolean notInErrorState =
          currentStateMap == null || currentStateMap.get(participantId) == null
              || !currentStateMap.get(participantId).equals(State.from(HelixDefinedState.ERROR));

      if (liveParticipantMap.containsKey(participantId) && notInErrorState
          && !disabledParticipantsForPartition.contains(participantId)) {
        participantStateMap.put(participantId, idealStateMap.get(participantId));
      }
    }

    return participantStateMap;
  }
}
