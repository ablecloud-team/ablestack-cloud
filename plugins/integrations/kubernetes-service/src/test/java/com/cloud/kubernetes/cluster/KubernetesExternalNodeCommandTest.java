// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.kubernetes.cluster;

import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.AddNodesToKubernetesClusterCmd;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesExternalNodeCommandTest {
    @Test public void failedNodeAdditionDoesNotReturnSuccessfulClusterResponse() {
        AddNodesToKubernetesClusterCmd cmd = new AddNodesToKubernetesClusterCmd();
        cmd.kubernetesClusterService = Mockito.mock(KubernetesClusterService.class);
        Mockito.when(cmd.kubernetesClusterService.addNodesToKubernetesCluster(cmd)).thenReturn(false);
        try { cmd.execute(); fail("failed service result must fail the API"); }
        catch (ServerApiException e) { assertTrue(e.getDescription().contains("Not all external nodes joined the Kubernetes cluster")); }
        Mockito.verify(cmd.kubernetesClusterService, Mockito.never()).createKubernetesClusterResponse(Mockito.anyLong());
    }
}
