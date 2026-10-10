// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import com.google.gson.JsonObject;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRenderedCheckpointPersistenceTest {
    private static class Manager extends StorageServiceManagerImpl {
        boolean failBegin;int begins;
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action) {
            if("begin".equals(action)){begins++;if(failBegin)throw new CloudRuntimeException("lost native begin reply");}
            JsonObject r=new JsonObject();r.add("generation",new JsonObject());r.add("configurationDesiredState",new JsonObject());return r;
        }
        @Override protected void prepareRenderedBatch(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,JsonObject before) {
            JsonObject snapshot=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();JsonObject rendered=new JsonObject();rendered.addProperty("keyId","protected-reference");rendered.addProperty("keySha256","a".repeat(64));rendered.addProperty("phase","PREPARED");snapshot.add("renderedGeneration",rendered);operation.setPreviousSnapshotJson(snapshot.toString());
        }
    }
    private void run(boolean fail) {
        Object oldDepot=ReflectionTestUtils.getField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot");org.apache.cloudstack.framework.config.impl.ConfigDepotImpl depot=Mockito.mock(org.apache.cloudstack.framework.config.impl.ConfigDepotImpl.class);Mockito.when(depot.getConfigStringValue(Mockito.anyString(),Mockito.any(),Mockito.any())).thenReturn("true");ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",depot);
        try {
        Manager manager=new Manager();manager.failBegin=fail;StorageServiceOperationDao dao=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(dao.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationDao",dao);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(7L);StorageServiceOperationVO operation=new StorageServiceOperationVO();operation.setPreviousSnapshotJson("{\"nativeIdentityCapsule\":{\"keyId\":\"existing-protected-identity\"},\"protocols\":[]}");
        if(fail)Assert.assertThrows(CloudRuntimeException.class,()->manager.prepareDesiredStateNativeCheckpoint(instance,operation));else manager.prepareDesiredStateNativeCheckpoint(instance,operation);
        JsonObject saved=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();Assert.assertEquals("protected-reference",saved.getAsJsonObject("renderedGeneration").get("keyId").getAsString());Assert.assertEquals("existing-protected-identity",saved.getAsJsonObject("nativeIdentityCapsule").get("keyId").getAsString());Assert.assertTrue(saved.has("nativeDesiredState"));Assert.assertEquals(1,manager.begins);Mockito.verify(dao,Mockito.atLeast(2)).update(Mockito.anyLong(),Mockito.any());
        } finally {ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",oldDepot);}
    }
    @Test public void realNativeCheckpointCallbackMergesRenderedKeyInsteadOfOverwritingIt(){run(false);}
    @Test public void lostBeginReplyStillRetainsRenderedAndIdentityReferencesForScopedRecovery(){run(true);}
}
