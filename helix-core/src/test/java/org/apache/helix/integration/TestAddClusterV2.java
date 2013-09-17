package org.apache.helix.integration;

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

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.helix.TestHelper;
import org.apache.helix.TestHelper.StartCMResult;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.tools.ClusterSetup;
import org.apache.helix.tools.ClusterStateVerifier;
import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestAddClusterV2 extends ZkIntegrationTestBase {
  private static Logger LOG = Logger.getLogger(TestAddClusterV2.class);

  protected static final int CLUSTER_NR = 10;
  protected static final int NODE_NR = 5;
  protected static final int START_PORT = 12918;
  protected static final String STATE_MODEL = "MasterSlave";
  protected ClusterSetup _setupTool = null;
  protected Map<String, StartCMResult> _startCMResultMap = new HashMap<String, StartCMResult>();

  protected final String CLASS_NAME = getShortClassName();
  protected final String CONTROLLER_CLUSTER = CONTROLLER_CLUSTER_PREFIX + "_" + CLASS_NAME;

  protected static final String TEST_DB = "TestDB";

  @BeforeClass
  public void beforeClass() throws Exception {
    System.out.println("START " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));

    String namespace = "/" + CONTROLLER_CLUSTER;
    if (_gZkClient.exists(namespace)) {
      _gZkClient.deleteRecursive(namespace);
    }

    for (int i = 0; i < CLUSTER_NR; i++) {
      namespace = "/" + CLUSTER_PREFIX + "_" + CLASS_NAME + "_" + i;
      if (_gZkClient.exists(namespace)) {
        _gZkClient.deleteRecursive(namespace);
      }
    }

    _setupTool = new ClusterSetup(ZK_ADDR);

    // setup CONTROLLER_CLUSTER
    _setupTool.addCluster(CONTROLLER_CLUSTER, true);
    for (int i = 0; i < NODE_NR; i++) {
      String controllerName = CONTROLLER_PREFIX + "_" + i;
      _setupTool.addInstanceToCluster(CONTROLLER_CLUSTER, controllerName);
    }

    // setup cluster of clusters
    for (int i = 0; i < CLUSTER_NR; i++) {
      String clusterName = CLUSTER_PREFIX + "_" + CLASS_NAME + "_" + i;
      _setupTool.addCluster(clusterName, true);
      _setupTool.activateCluster(clusterName, CONTROLLER_CLUSTER, true);
    }

    final String firstCluster = CLUSTER_PREFIX + "_" + CLASS_NAME + "_0";
    setupStorageCluster(_setupTool, firstCluster, TEST_DB, 20, PARTICIPANT_PREFIX, START_PORT,
        "MasterSlave", 3, true);

    // start dummy participants for the first cluster
    for (int i = 0; i < 5; i++) {
      String instanceName = PARTICIPANT_PREFIX + "_" + (START_PORT + i);
      if (_startCMResultMap.get(instanceName) != null) {
        LOG.error("fail to start participant:" + instanceName
            + "(participant with the same name already running");
      } else {
        StartCMResult result = TestHelper.startDummyProcess(ZK_ADDR, firstCluster, instanceName);
        _startCMResultMap.put(instanceName, result);
      }
    }

    // start distributed cluster controllers
    for (int i = 0; i < 5; i++) {
      String controllerName = CONTROLLER_PREFIX + "_" + i;
      if (_startCMResultMap.get(controllerName) != null) {
        LOG.error("fail to start controller:" + controllerName
            + "(controller with the same name already running");
      } else {
        StartCMResult result =
            TestHelper.startController(CONTROLLER_CLUSTER, controllerName, ZK_ADDR,
                HelixControllerMain.DISTRIBUTED);
        _startCMResultMap.put(controllerName, result);
      }
    }

    verifyClusters();
  }

  @Test
  public void Test() {

  }

  @AfterClass
  public void afterClass() throws Exception {
    System.out.println("AFTERCLASS " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));

    /**
     * shutdown order:
     * 1) pause the leader (optional)
     * 2) disconnect all controllers
     * 3) disconnect leader/disconnect participant
     */
    String leader = getCurrentLeader(_gZkClient, CONTROLLER_CLUSTER);
    // pauseController(_startCMResultMap.get(leader)._manager.getDataAccessor());

    StartCMResult result;

    Iterator<Entry<String, StartCMResult>> it = _startCMResultMap.entrySet().iterator();

    while (it.hasNext()) {
      String instanceName = it.next().getKey();
      if (!instanceName.equals(leader) && instanceName.startsWith(CONTROLLER_PREFIX)) {
        result = _startCMResultMap.get(instanceName);
        result._manager.disconnect();
        result._thread.interrupt();
        it.remove();
      }
      verifyClusters();
    }

    result = _startCMResultMap.remove(leader);
    result._manager.disconnect();
    result._thread.interrupt();

    it = _startCMResultMap.entrySet().iterator();
    while (it.hasNext()) {
      String instanceName = it.next().getKey();
      result = _startCMResultMap.get(instanceName);
      result._manager.disconnect();
      result._thread.interrupt();
      it.remove();
    }

    System.out.println("END " + CLASS_NAME + " at " + new Date(System.currentTimeMillis()));
  }

  /**
   * verify the external view (against the best possible state)
   * in the controller cluster and the first cluster
   */
  protected void verifyClusters() {
    boolean result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(
            ZK_ADDR, CONTROLLER_CLUSTER));
    Assert.assertTrue(result);

    result =
        ClusterStateVerifier.verifyByPolling(new ClusterStateVerifier.BestPossAndExtViewZkVerifier(
            ZK_ADDR, CLUSTER_PREFIX + "_" + CLASS_NAME + "_0"));
    Assert.assertTrue(result);
  }

  protected void setupStorageCluster(ClusterSetup setupTool, String clusterName, String dbName,
      int partitionNr, String prefix, int startPort, String stateModel, int replica,
      boolean rebalance) {
    setupTool.addResourceToCluster(clusterName, dbName, partitionNr, stateModel);
    for (int i = 0; i < NODE_NR; i++) {
      String instanceName = prefix + "_" + (startPort + i);
      setupTool.addInstanceToCluster(clusterName, instanceName);
    }
    if (rebalance) {
      setupTool.rebalanceStorageCluster(clusterName, dbName, replica);
    }
  }
}
