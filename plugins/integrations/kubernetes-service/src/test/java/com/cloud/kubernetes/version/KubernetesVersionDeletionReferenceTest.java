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

package com.cloud.kubernetes.version;

import java.util.Collections;
import java.util.Date;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.admin.kubernetes.version.DeleteKubernetesSupportedVersionCmd;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class KubernetesVersionDeletionReferenceTest {
    private KubernetesVersionManagerImpl manager;
    private KubernetesClusterDao clusters;
    private KubernetesClusterDetailsDao details;
    private KubernetesClusterVmMapDao maps;
    private KubernetesSupportedVersionDao versions;
    private VMTemplateDao templates;
    private KubernetesSupportedVersionVO version;
    private KubernetesClusterVO cluster;
    @Before public void setup() {
        manager = Mockito.spy(new KubernetesVersionManagerImpl());
        clusters = Mockito.mock(KubernetesClusterDao.class);
        details = Mockito.mock(KubernetesClusterDetailsDao.class);
        maps = Mockito.mock(KubernetesClusterVmMapDao.class);
        versions = Mockito.mock(KubernetesSupportedVersionDao.class);
        templates = Mockito.mock(VMTemplateDao.class);
        ReflectionTestUtils.setField(manager, "kubernetesClusterDao", clusters);
        ReflectionTestUtils.setField(manager, "kubernetesClusterDetailsDao", details);
        ReflectionTestUtils.setField(manager, "kubernetesClusterVmMapDao", maps);
        ReflectionTestUtils.setField(manager, "kubernetesSupportedVersionDao", versions);
        ReflectionTestUtils.setField(manager, "templateDao", templates);
        version = Mockito.mock(KubernetesSupportedVersionVO.class);
        Mockito.when(version.getId()).thenReturn(2L);
        Mockito.when(version.getSemanticVersion()).thenReturn("1.34.9");
        cluster = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(3L);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(cluster.getKubernetesVersionId()).thenReturn(1L);
        Mockito.when(clusters.listAll()).thenReturn(Collections.singletonList(cluster));
    }
    private void pin(String name, String value) {
        KubernetesClusterDetailsVO d = Mockito.mock(KubernetesClusterDetailsVO.class);
        Mockito.when(d.getValue()).thenReturn(value);
        Mockito.when(details.findDetail(3L, name)).thenReturn(d);
    }
    @Test public void currentClusterReferenceProtected() {
        Mockito.when(clusters.listAllByKubernetesVersion(2L)).thenReturn(Collections.singletonList(cluster));
        assertTrue(manager.isKubernetesVersionReferenced(version));
    }
    @Test public void targetPinProtectedBeforeFirstNodeMutation() {
        pin(KubernetesVersionReferences.TARGET, "2");
        assertTrue(manager.isKubernetesVersionReferenced(version));
    }
    @Test public void sourcePinProtectedAfterPartialFailure() {
        pin(KubernetesVersionReferences.SOURCE, "2");
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Alert);
        assertTrue(manager.isKubernetesVersionReferenced(version));
    }
    @Test public void removedClusterDoesNotHoldArtifactForever() {
        Mockito.when(cluster.getRemoved()).thenReturn(new Date());
        Mockito.when(clusters.listAllByKubernetesVersion(2L)).thenReturn(Collections.singletonList(cluster));
        assertFalse(manager.isKubernetesVersionReferenced(version));
    }
    @Test public void legacyMixedNodeVersionProtectsUpgradeTarget() {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Alert);
        KubernetesSupportedVersionVO current = Mockito.mock(KubernetesSupportedVersionVO.class);
        Mockito.when(current.getSemanticVersion()).thenReturn("1.34.2");
        Mockito.when(versions.findById(1L)).thenReturn(current);
        KubernetesClusterVmMapVO node = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(node.getNodeVersion()).thenReturn("v1.34.9");
        Mockito.when(maps.listByClusterId(3L)).thenReturn(Collections.singletonList(node));
        assertTrue(manager.isKubernetesVersionReferenced(version));
        Mockito.when(version.getSemanticVersion()).thenReturn("1.35.9");
        assertFalse(manager.isKubernetesVersionReferenced(version));
    }
    @Test public void unrelatedArtifactIsNotClaimedByRunningCluster() {
        pin(KubernetesVersionReferences.TARGET, "7");
        assertFalse(manager.isKubernetesVersionReferenced(version));
    }
    private DeleteKubernetesSupportedVersionCmd deletion() {
        DeleteKubernetesSupportedVersionCmd cmd = Mockito.mock(DeleteKubernetesSupportedVersionCmd.class);
        Mockito.when(cmd.getId()).thenReturn(2L);
        Mockito.when(versions.findById(2L)).thenReturn(version);
        Mockito.when(version.getIsoId()).thenReturn(4L);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getId()).thenReturn(4L);
        Mockito.when(templates.findByIdIncludingRemoved(4L)).thenReturn(template);
        return cmd;
    }
    @Test public void falseIsoDeletionPreservesCatalogRow() throws Exception {
        DeleteKubernetesSupportedVersionCmd cmd = deletion();
        Mockito.doReturn(false).when(manager).deleteKubernetesVersionIso(4L);
        try { manager.deleteUnreferencedKubernetesVersion(cmd); fail("false ISO deletion must fail"); }
        catch (CloudRuntimeException expected) { Mockito.verify(versions, Mockito.never()).remove(2L); }
    }
    @Test public void failedIsoDeletionPreservesCatalogRow() throws Exception {
        DeleteKubernetesSupportedVersionCmd cmd = deletion();
        Mockito.doThrow(new CloudRuntimeException("storage unavailable")).when(manager).deleteKubernetesVersionIso(4L);
        try { manager.deleteUnreferencedKubernetesVersion(cmd); fail("ISO deletion failure must propagate"); }
        catch (CloudRuntimeException expected) { Mockito.verify(versions, Mockito.never()).remove(2L); }
    }
}
