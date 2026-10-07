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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.template.TemplateApiService;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;

public class KubernetesUpgradeIsoRetryTest {
    KubernetesClusterActionWorker worker;
    TemplateApiService templates;
    UserVm vm;
    @Before public void setUp() {
        worker = new KubernetesClusterActionWorker(Mockito.mock(KubernetesCluster.class), Mockito.mock(KubernetesClusterManagerImpl.class));
        templates = Mockito.mock(TemplateApiService.class);
        worker.templateService = templates;
        vm = Mockito.mock(UserVm.class);
        Mockito.when(vm.getId()).thenReturn(54L);
    }
    @Test public void sameTargetUpgradeMediaIsReusedWithoutAnotherAttachment() {
        Mockito.when(vm.getIsoId()).thenReturn(72L);
        worker.attachKubernetesIsoToVm(vm, 72L, true);
        Mockito.verifyNoInteractions(templates);
    }
    @Test public void absentMediaUsesNormalAttachment() {
        worker.attachKubernetesIsoToVm(vm, 72L, true);
        Mockito.verify(templates).attachIso(72L, 54L, true);
    }
    @Test public void differentAttachedMediaIsRejectedAndNeverDetached() {
        Mockito.when(vm.getIsoId()).thenReturn(73L);
        Mockito.doThrow(new CloudRuntimeException("different media")).when(templates).attachIso(72L, 54L, true);
        try {
            worker.attachKubernetesIsoToVm(vm, 72L, true);
            Assert.fail("different media must not be silently replaced");
        } catch (CloudRuntimeException expected) {
            Mockito.verify(templates).attachIso(72L, 54L, true);
            Mockito.verify(templates, Mockito.never()).detachIso(Mockito.anyLong(), Mockito.any(), Mockito.anyBoolean());
        }
    }
    @Test public void nonUpgradeAttachmentKeepsExistingTemplateApiContract() {
        Mockito.when(vm.getIsoId()).thenReturn(72L);
        worker.attachKubernetesIsoToVm(vm, 72L, false);
        Mockito.verify(templates).attachIso(72L, 54L, true);
    }
}
