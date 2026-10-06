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

package com.cloud.kubernetes.cluster;

import org.apache.cloudstack.api.response.KubernetesClusterResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.CONTROL;
import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.WORKER;
import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.ETCD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesNodeTemplateResponseTest {
    @Mock
    private VMTemplateDao templateDao;
    @InjectMocks
    private KubernetesClusterManagerImpl manager;

    private void template(long id, String uuid, String name) {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getUuid()).thenReturn(uuid);
        Mockito.when(template.getName()).thenReturn(name);
        Mockito.when(templateDao.findByIdIncludingRemoved(id)).thenReturn(template);
    }

    @Test
    public void defaultTemplateIsEffectiveWhenNoOverrideWasConfigured() {
        template(1L, "default-uuid", "default-image");
        KubernetesClusterResponse response = new KubernetesClusterResponse();
        manager.setNodeTypeTemplateResponse(response, CONTROL, null, 1L);
        manager.setNodeTypeTemplateResponse(response, WORKER, null, 1L);
        assertEquals("default-uuid", response.getControlTemplateId());
        assertEquals("default-image", response.getWorkerTemplateName());
        assertNull(response.getEtcdTemplateId());
    }

    @Test
    public void sameOverridesDoNotRewriteTheLegacyDefault() {
        template(326L, "prepared-uuid", "prepared-image");
        KubernetesClusterResponse response = new KubernetesClusterResponse();
        response.setTemplateId("legacy-default");
        manager.setNodeTypeTemplateResponse(response, CONTROL, 326L, 231L);
        manager.setNodeTypeTemplateResponse(response, WORKER, 326L, 231L);
        manager.setNodeTypeTemplateResponse(response, ETCD, 326L, 231L);
        assertEquals("prepared-uuid", response.getControlTemplateId());
        assertEquals("prepared-uuid", response.getWorkerTemplateId());
        assertEquals("prepared-uuid", response.getEtcdTemplateId());
        assertEquals("legacy-default", response.getTemplateId());
    }

    @Test
    public void distinctControlWorkerAndExternalEtcdTemplatesSerializeSeparately() {
        template(1L, "control-uuid", "control-image");
        template(2L, "worker-uuid", "worker-image");
        template(3L, "etcd-uuid", "etcd-image");
        KubernetesClusterResponse response = new KubernetesClusterResponse();
        manager.setNodeTypeTemplateResponse(response, CONTROL, 1L, 231L);
        manager.setNodeTypeTemplateResponse(response, WORKER, 2L, 231L);
        manager.setNodeTypeTemplateResponse(response, ETCD, 3L, 231L);
        JsonObject json = new Gson().toJsonTree(response).getAsJsonObject();
        assertEquals("control-uuid", json.get("controltemplateid").getAsString());
        assertEquals("worker-image", json.get("workertemplatename").getAsString());
        assertEquals("etcd-uuid", json.get("etcdtemplateid").getAsString());
    }

    @Test
    public void unknownOverrideIsNotMisrepresentedAsTheDefault() {
        KubernetesClusterResponse response = new KubernetesClusterResponse();
        manager.setNodeTypeTemplateResponse(response, CONTROL, 999L, 231L);
        assertNull(response.getControlTemplateId());
        Mockito.verify(templateDao, Mockito.never()).findByIdIncludingRemoved(231L);
    }
}
