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

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class StorageConfigMaintenancePlanValidationTest {
    private static class Manager extends StorageServiceManagerImpl {String snapshot="{}";@Override public String captureConfigurationSnapshot(long id){return snapshot;}}
    private Manager manager;private StorageConfigArtifactDao artifacts;private StorageServiceOperationDao operations;private StorageServiceConfiguration configuration;private StorageServiceInstanceVO instance;private StorageConfigRequest request;private JsonObject metadata,plan,capability;
    private String sha(String value){return StorageConfigArchive.sha256(value.getBytes(StandardCharsets.UTF_8));}
    @Before public void setup(){
        com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(11L);CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));manager=new Manager();artifacts=Mockito.mock(StorageConfigArtifactDao.class);operations=Mockito.mock(StorageServiceOperationDao.class);configuration=new StorageServiceConfiguration(manager,artifacts,operations);instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn("instance");Mockito.when(operations.listByInstance(7L)).thenReturn(List.of());
        request=Mockito.mock(StorageConfigRequest.class);Mockito.when(request.getArtifactId()).thenReturn(8L);Mockito.when(request.getPlanToken()).thenReturn("synthetic-review-token");Mockito.when(request.getConfirmation()).thenReturn("fixture");
        plan=new JsonObject();plan.addProperty("targetMode","RESTORE_EXISTING");plan.addProperty("targetInstanceUuid","instance");plan.addProperty("targetName","fixture");plan.addProperty("artifactSha256","a".repeat(64));plan.addProperty("expectedRevision",0);plan.add("requiredCredentials",new JsonArray());capability=new JsonObject();capability.addProperty("user",11);capability.addProperty("expires",System.currentTimeMillis()+600000);capability.addProperty("hash",sha("synthetic-review-token"));capability.addProperty("planSha256",sha(plan.toString()));capability.addProperty("baselineSha256",sha("{}"));metadata=new JsonObject();metadata.addProperty("planState","PLANNED");metadata.add("plan",plan);metadata.add("planToken",capability);
        StorageConfigArtifactVO artifact=Mockito.mock(StorageConfigArtifactVO.class);Mockito.when(artifact.getInstanceId()).thenReturn(7L);Mockito.when(artifact.getState()).thenReturn("AVAILABLE");Mockito.when(artifact.getSha256()).thenReturn("a".repeat(64));Mockito.when(artifact.getMetadataJson()).thenAnswer(call->metadata.toString());Mockito.when(artifacts.findById(8L)).thenReturn(artifact);
    }
    @After public void cleanup(){CallContext.unregister();}
    @Test public void validReviewedMaintenancePreflightDoesNotConsumeCapabilityOrWriteArtifact(){configuration.validateApprovedMaintenancePlan(instance,request);Assert.assertTrue(metadata.has("planToken"));Assert.assertEquals("PLANNED",metadata.get("planState").getAsString());Mockito.verify(artifacts,Mockito.never()).update(Mockito.anyLong(),Mockito.any());}
    @Test public void expiredOrDifferentActorTokenRejectsBeforeAnyMaintenanceEffect(){capability.addProperty("expires",1);Assert.assertThrows(InvalidParameterValueException.class,()->configuration.validateApprovedMaintenancePlan(instance,request));capability.addProperty("expires",System.currentTimeMillis()+600000);capability.addProperty("user",12);Assert.assertThrows(InvalidParameterValueException.class,()->configuration.validateApprovedMaintenancePlan(instance,request));capability.addProperty("user",11);Mockito.when(request.getPlanToken()).thenReturn("foreign");Assert.assertThrows(InvalidParameterValueException.class,()->configuration.validateApprovedMaintenancePlan(instance,request));Mockito.verify(artifacts,Mockito.never()).update(Mockito.anyLong(),Mockito.any());}
    @Test public void staleBaselineForeignTargetAndClonePlanRejectWithoutCapabilityConsumption(){manager.snapshot="{\"changed\":true}";Assert.assertThrows(InvalidParameterValueException.class,()->configuration.validateApprovedMaintenancePlan(instance,request));manager.snapshot="{}";plan.addProperty("targetInstanceUuid","foreign");capability.addProperty("planSha256",sha(plan.toString()));Assert.assertThrows(InvalidParameterValueException.class,()->configuration.validateApprovedMaintenancePlan(instance,request));plan.addProperty("targetInstanceUuid","instance");plan.addProperty("targetMode","CREATE_NEW");capability.addProperty("planSha256",sha(plan.toString()));Assert.assertThrows(InvalidParameterValueException.class,()->configuration.validateApprovedMaintenancePlan(instance,request));Assert.assertTrue(metadata.has("planToken"));Mockito.verify(artifacts,Mockito.never()).update(Mockito.anyLong(),Mockito.any());}
}
