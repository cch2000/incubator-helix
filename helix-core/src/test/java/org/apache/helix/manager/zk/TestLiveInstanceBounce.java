package org.apache.helix.manager.zk;

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

import org.apache.helix.TestHelper;
import org.apache.helix.TestHelper.StartCMResult;
import org.apache.helix.ZkHelixTestManager;
import org.apache.helix.integration.ZkStandAloneCMTestBaseWithPropertyServerCheck;
import org.apache.helix.tools.ClusterStateVerifier;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TestLiveInstanceBounce extends ZkStandAloneCMTestBaseWithPropertyServerCheck {
  @Test
  public void testInstanceBounce() throws Exception {
    String controllerName = CONTROLLER_PREFIX + "_0";
    StartCMResult controllerResult = _startCMResultMap.get(controllerName);
    ZkHelixTestManager controller = controllerResult._manager;
    int handlerSize = controller.getHandlers().size();

    for (int i = 0; i < 2; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      // kill 2 participants
      _startCMResultMap.get(instanceName)._manager.disconnect();
      _startCMResultMap.get(instanceName)._thread.interrupt();
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      // restart the participant
      StartCMResult result = TestHelper.startDummyProcess(ZK_ADDR, CLUSTER_NAME, instanceName);
      _startCMResultMap.put(instanceName, result);
      Thread.sleep(100);
    }
    Thread.sleep(4000);

    boolean result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(
            ZK_ADDR, CLUSTER_NAME), 50 * 1000);
    Assert.assertTrue(result);

    // When a new live instance is created, we add current state listener to it
    // and we will remove current-state listener on expired session
    // so the number of callback handlers is unchanged
    for (int j = 0; j < 10; j++) {
      if (controller.getHandlers().size() == (handlerSize)) {
        break;
      }
      Thread.sleep(400);
    }
    Assert.assertEquals(controller.getHandlers().size(), handlerSize);
  }
}
