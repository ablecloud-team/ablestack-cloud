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

package com.cloud.kubernetes.cluster.actionworkers;

import java.lang.reflect.Field;
import java.util.List;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KubernetesExternalNodeReadinessTest {
    private static final String VM = "91d9baad-9ea7-4fdd-92a7-0a337561e453";
    private static String node(String vm, String ready) {
        return "{\"metadata\":{\"name\":\"actual-worker\",\"uid\":\"11111111-1111-1111-1111-111111111111\"},"
            + "\"spec\":{\"providerID\":\"external-cloudstack://" + vm + "\"},"
            + "\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"" + ready + "\"}]}}";
    }
    private static String list(String... nodes) { return "{\"items\":[" + String.join(",", nodes) + "]}"; }
    private static final class Worker extends KubernetesClusterAddWorker {
        Worker() { super(Mockito.mock(KubernetesCluster.class), Mockito.mock(KubernetesClusterManagerImpl.class)); }
    }
    private Worker worker() {
        Worker w = new Worker(); w.userVmDao = Mockito.mock(UserVmDao.class);
        UserVmVO vm = Mockito.mock(UserVmVO.class); Mockito.when(vm.getUuid()).thenReturn(VM);
        Mockito.when(w.userVmDao.findById(1L)).thenReturn(vm); return w;
    }
    @Test public void readyIdentityUsesNativeNameInsteadOfDisplayName() {
        KubernetesExternalNodeIdentity.NativeNode n = KubernetesExternalNodeIdentity.find(list(node(VM,"True")), VM);
        assertEquals("actual-worker", n.name); assertTrue(n.ready);
    }
    @Test public void otherReadyNodeCannotReplaceRequestedVm() {
        assertFalse(worker().externalNodesReady(List.of(1L),new Pair<>(true,list(node("other","True")))));
    }
    @Test public void notReadyRequestedVmFailsEvenWhenAnotherNodeIsReady() {
        assertFalse(worker().externalNodesReady(List.of(1L),new Pair<>(true,list(node(VM,"False"),node("other","True")))));
    }
    @Test public void sshFailureCannotReuseReadyLookingOutput() {
        assertFalse(worker().externalNodesReady(List.of(1L),new Pair<>(false,list(node(VM,"True")))));
    }
    @Test public void requestedReadyVmCompletes() {
        assertTrue(worker().externalNodesReady(List.of(1L),new Pair<>(true,list(node(VM,"True")))));
    }
    @Test public void everyRequestedVmIsRequired() {
        assertFalse(worker().externalNodesReady(List.of(1L,2L),new Pair<>(true,list(node(VM,"True")))));
    }
    @Test public void timeoutDoesNotReportSuccessfulCompletion() throws Exception {
        Worker w=worker();Field f=KubernetesClusterAddWorker.class.getDeclaredField("addNodeTimeoutTime");f.setAccessible(true);f.setLong(w,0);
        assertFalse(w.waitForExternalNodesReady(List.of(1L),new Pair<>("127.0.0.1",2222)));
    }
    @Test public void ambiguousProviderIdentityFailsClosed() {
        try { KubernetesExternalNodeIdentity.find(list(node(VM,"True"),node(VM,"True")),VM); fail(); }
        catch (CloudRuntimeException expected) { assertFalse(expected.getMessage().contains(VM)); }
    }
    @Test public void malformedJsonDoesNotLeakQueryOutput() {
        try { KubernetesExternalNodeIdentity.find("private-sensitive-output",VM); fail(); }
        catch (CloudRuntimeException expected) { assertFalse(expected.getMessage().contains("private-sensitive-output")); }
    }
    @Test public void unsafeNativeNameIsRejected() {
        try { KubernetesExternalNodeIdentity.find(list(node(VM,"True").replace("actual-worker","worker; false")),VM); fail(); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("invalid or ambiguous")); }
    }
}
