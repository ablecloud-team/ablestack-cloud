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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.UpgradeKubernetesClusterCmd;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;

public class KubernetesCancelledOperationRecoveryTest {
    KubernetesClusterVO cluster;
    AsyncJobVO job;
    @Before public void setUp() {
        cluster = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(54L);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Upgrading);
        job = Mockito.mock(AsyncJobVO.class);
        Mockito.when(job.getInstanceId()).thenReturn(54L);
        Mockito.when(job.getInstanceType()).thenReturn(ApiCommandResourceType.KubernetesCluster.toString());
        Mockito.when(job.getCmd()).thenReturn(UpgradeKubernetesClusterCmd.class.getName());
        Mockito.when(job.getStatus()).thenReturn(JobInfo.Status.FAILED);
        Mockito.when(job.getResult()).thenReturn("job cancelled because of management server restart or shutdown");
    }
    @Test public void restartCancelledMatchingJobIsRecoverable() {
        Assert.assertTrue(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }
    @Test public void activeOrSuccessfulJobCannotBeRecoveredAsFailed() {
        Mockito.when(job.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
        Mockito.when(job.getStatus()).thenReturn(JobInfo.Status.SUCCEEDED);
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }
    @Test public void ordinaryFailureCannotUnlockAStillRunningWorker() {
        Mockito.when(job.getResult()).thenReturn("request timed out");
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }
    @Test public void differentCommandOrResourceCannotRecoverThisCluster() {
        Mockito.when(job.getCmd()).thenReturn("different-command");
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
        Mockito.when(job.getCmd()).thenReturn(UpgradeKubernetesClusterCmd.class.getName());
        Mockito.when(job.getInstanceId()).thenReturn(55L);
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }
    @Test public void stableClusterHasNoInterruptedOperation() {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Running);
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }
    @Test public void recoveryDoesNotClearArtifactPinsOrNativeMappings() {
        KubernetesClusterManagerImpl manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        manager.kubernetesClusterVmMapDao = Mockito.mock(KubernetesClusterVmMapDao.class);
        Mockito.when(manager.asyncJobDao.findJob(null, 54L, ApiCommandResourceType.KubernetesCluster.toString())).thenReturn(job);
        Mockito.doReturn(true).when(manager).stateTransitTo(54L, KubernetesCluster.Event.OperationFailed);
        Assert.assertTrue(manager.recoverRestartCancelledOperation(cluster));
        Mockito.verify(manager).stateTransitTo(54L, KubernetesCluster.Event.OperationFailed);
        Mockito.verifyNoInteractions(manager.kubernetesClusterVmMapDao);
        Mockito.verify(manager.kubernetesClusterDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }
    @Test public void pendingJobPreventsRecoveryEvenWithOldCancelledJob() {
        KubernetesClusterManagerImpl manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        Mockito.when(manager.asyncJobDao.findInstancePendingAsyncJob(ApiCommandResourceType.KubernetesCluster.toString(), 54L)).thenReturn(job);
        Assert.assertFalse(manager.recoverRestartCancelledOperation(cluster));
        Mockito.verify(manager, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
        Mockito.verify(manager.asyncJobDao, Mockito.never()).findJob(Mockito.any(), Mockito.any(), Mockito.anyString());
    }
    @Test public void legacyUnattachedJobMatchesOnlyExactPersistedClusterUuid() {
        Mockito.when(cluster.getUuid()).thenReturn("cluster-54-uuid");
        Mockito.when(job.getInstanceId()).thenReturn(null);
        Mockito.when(job.getCmdInfo()).thenReturn("{\"id\":\"cluster-54-uuid\"}");
        Assert.assertTrue(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
        Mockito.when(job.getCmdInfo()).thenReturn("{\"id\":\"another-cluster\"}");
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }
    @Test public void malformedOrMissingLegacyRequestCannotRecoverCluster() {
        Mockito.when(cluster.getUuid()).thenReturn("cluster-54-uuid");
        Mockito.when(job.getInstanceId()).thenReturn(null);
        for (String info : new String[] {null, "bad-json", "{}", "{\"id\":null}"}) {
            Mockito.when(job.getCmdInfo()).thenReturn(info);
            Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
        }
    }
    @Test public void legacyPendingJobCannotUnlockOperation() {
        KubernetesClusterManagerImpl manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        Mockito.doReturn(job).when(manager).findLegacyInterruptedOperation(cluster);
        Mockito.when(job.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
        Assert.assertFalse(manager.recoverRestartCancelledOperation(cluster));
        Mockito.verify(manager, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
    }
    @Test public void upgradeAndScaleCommandsAttachTheActualClusterId() throws Exception {
        for (org.apache.cloudstack.api.BaseAsyncCmd command : new org.apache.cloudstack.api.BaseAsyncCmd[] {
                new UpgradeKubernetesClusterCmd(), new org.apache.cloudstack.api.command.user.kubernetes.cluster.ScaleKubernetesClusterCmd()}) {
            java.lang.reflect.Field id = command.getClass().getDeclaredField("id");
            id.setAccessible(true);
            id.set(command, 54L);
            Assert.assertEquals(Long.valueOf(54L), command.getApiResourceId());
        }
    }

    @Test public void newerLegacyCancellationOverridesOlderAttachedCreateJob() {
        KubernetesClusterManagerImpl manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        AsyncJobVO older = Mockito.mock(AsyncJobVO.class);
        Mockito.when(older.getCreated()).thenReturn(new java.util.Date(1000L));
        Mockito.when(job.getCreated()).thenReturn(new java.util.Date(2000L));
        Mockito.when(manager.asyncJobDao.findJob(null, 54L, ApiCommandResourceType.KubernetesCluster.toString())).thenReturn(older);
        Mockito.doReturn(job).when(manager).findLegacyInterruptedOperation(cluster);
        Mockito.doReturn(true).when(manager).stateTransitTo(54L, KubernetesCluster.Event.OperationFailed);
        Assert.assertTrue(manager.recoverRestartCancelledOperation(cluster));
    }
    @Test public void newerAttachedOrdinaryFailureCannotReuseOldLegacyCancellation() {
        KubernetesClusterManagerImpl manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        AsyncJobVO older = Mockito.mock(AsyncJobVO.class);
        Mockito.when(older.getCreated()).thenReturn(new java.util.Date(1000L));
        Mockito.when(job.getCreated()).thenReturn(new java.util.Date(2000L));
        Mockito.when(job.getResult()).thenReturn("ordinary failure");
        Mockito.when(manager.asyncJobDao.findJob(null, 54L, ApiCommandResourceType.KubernetesCluster.toString())).thenReturn(job);
        Mockito.doReturn(older).when(manager).findLegacyInterruptedOperation(cluster);
        Assert.assertFalse(manager.recoverRestartCancelledOperation(cluster));
        Mockito.verify(manager, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
    }

    @Test public void startingAndStoppingMatchOnlyTheirRestartCancelledOperation() {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Starting);
        for (Class<?> command : new Class<?>[]{
                org.apache.cloudstack.api.command.user.kubernetes.cluster.StartKubernetesClusterCmd.class,
                org.apache.cloudstack.api.command.user.kubernetes.cluster.CreateKubernetesClusterCmd.class}) {
            Mockito.when(job.getCmd()).thenReturn(command.getName());
            Assert.assertTrue(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
            Mockito.when(job.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
            Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
            Mockito.when(job.getStatus()).thenReturn(JobInfo.Status.FAILED);
        }
        Mockito.when(job.getCmd()).thenReturn(UpgradeKubernetesClusterCmd.class.getName());
        Assert.assertFalse(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Stopping);
        Mockito.when(job.getCmd()).thenReturn(org.apache.cloudstack.api.command.user.kubernetes.cluster.StopKubernetesClusterCmd.class.getName());
        Assert.assertTrue(KubernetesClusterManagerImpl.isRestartCancelledJob(cluster, job));
    }

    @Test public void cancelledStartRecoveryUsesOperationFailedAndPreservesNativeResources() {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Starting);
        Mockito.when(job.getCmd()).thenReturn(org.apache.cloudstack.api.command.user.kubernetes.cluster.StartKubernetesClusterCmd.class.getName());
        recoveryDoesNotClearArtifactPinsOrNativeMappings();
    }

}
