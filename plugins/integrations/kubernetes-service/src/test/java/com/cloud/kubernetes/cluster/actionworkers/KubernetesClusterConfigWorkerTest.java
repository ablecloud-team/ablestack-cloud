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

import org.apache.commons.codec.binary.Base64;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.uservm.UserVm;

public class KubernetesClusterConfigWorkerTest {
    private static final String CONFIG = "apiVersion: v1\nkind: Config\nclusters:\n- cluster:\n    server: https://10.0.0.8:6443\n  name: owned\nusers:\n- name: renewed\n  user:\n    client-certificate-data: renewed-fixture\n";
    private static final String ENDPOINT = "https://192.0.2.10:6443/";
    private KubernetesClusterVO cluster;
    private KubernetesClusterConfigWorker worker;
    private KubernetesClusterDetailsDao details;
    private KubernetesClusterDao dao;

    @Before
    public void setUp() throws Exception {
        cluster = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(1L);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(cluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(cluster.getEndpoint()).thenReturn(ENDPOINT);
        Mockito.when(cluster.getKubernetesVersionId()).thenReturn(2L);
        KubernetesClusterManagerImpl manager = Mockito.mock(KubernetesClusterManagerImpl.class);
        details = Mockito.mock(KubernetesClusterDetailsDao.class);
        dao = Mockito.mock(KubernetesClusterDao.class);
        manager.kubernetesClusterDetailsDao = details;
        manager.kubernetesClusterDao = dao;
        Mockito.when(dao.findById(1L)).thenReturn(cluster);
        worker = Mockito.spy(new KubernetesClusterConfigWorker(cluster, manager));
        Mockito.doReturn(new Pair<>("192.0.2.10", 2222)).when(worker).getKubernetesClusterServerIpSshPort(Mockito.nullable(UserVm.class));
        Mockito.doReturn(new Pair<>(true, CONFIG)).when(worker).readLatestConfig("192.0.2.10", 2222);
    }

    @Test
    public void refreshStoresCurrentCredentialAndPreservesPublicEndpoint() {
        String refreshed = worker.refresh();
        Assert.assertTrue(refreshed.contains("server: https://192.0.2.10:6443\n"));
        Assert.assertTrue(refreshed.contains("client-certificate-data: renewed-fixture"));
        Assert.assertFalse(refreshed.contains("10.0.0.8"));
        Mockito.verify(details).addDetail(1L, "kubeConfigData", Base64.encodeBase64String(refreshed.getBytes(java.nio.charset.StandardCharsets.UTF_8)), false);
    }

    private void rejected() {
        try { worker.refresh(); Assert.fail("Refresh should fail"); }
        catch (CloudRuntimeException e) { Assert.assertFalse(e.getMessage().contains("renewed-fixture")); }
        Mockito.verifyNoInteractions(details);
    }

    @Test
    public void failedSshDoesNotReplaceCache() throws Exception {
        Mockito.doReturn(new Pair<>(false, CONFIG)).when(worker).readLatestConfig("192.0.2.10", 2222);
        rejected();
    }

    @Test
    public void sshExceptionDoesNotExposeOutputOrReplaceCache() throws Exception {
        Mockito.doThrow(new Exception("renewed-fixture")).when(worker).readLatestConfig("192.0.2.10", 2222);
        rejected();
    }

    @Test
    public void invalidConfigDoesNotReplaceCache() throws Exception {
        Mockito.doReturn(new Pair<>(true, "not a kubeconfig")).when(worker).readLatestConfig("192.0.2.10", 2222);
        rejected();
    }

    @Test
    public void externalManagedNeverReadsNodeOrReplacesCache() throws Exception {
        Mockito.when(cluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.ExternalManaged);
        rejected();
        Mockito.verify(worker, Mockito.never()).readLatestConfig(Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    public void stoppedNeverReadsNodeOrReplacesCache() throws Exception {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Stopped);
        rejected();
        Mockito.verify(worker, Mockito.never()).readLatestConfig(Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    public void concurrentOperationDoesNotReplaceCache() {
        KubernetesClusterVO changed = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(changed.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(changed.getState()).thenReturn(KubernetesCluster.State.Upgrading);
        Mockito.when(dao.findById(1L)).thenReturn(changed);
        rejected();
    }

    @Test(expected = CloudRuntimeException.class)
    public void multipleServersAreNotRewrittenOrPersisted() {
        KubernetesClusterConfigWorker.withClusterEndpoint(CONFIG + "    server: https://192.0.2.20:6443\n", ENDPOINT);
    }

    @Test(expected = CloudRuntimeException.class)
    public void invalidEndpointDoesNotEnterConfiguration() {
        KubernetesClusterConfigWorker.withClusterEndpoint(CONFIG, "https://user:password@192.0.2.10:6443/");
    }
}
