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
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.GlobalLock;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ApplyStorageServiceApprovedMaintenanceCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageApprovedMaintenanceRetryTest {
    private static class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;boolean routeProtected;int protectedEntries;
        @Override public org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse storageServiceConfiguration(StorageConfigRequest request){if(!routeProtected)throw new AssertionError("Unexpected protected route");Assert.assertEquals(Boolean.TRUE,request.getMaintenanceWindow());protectedEntries++;return new org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse();}
        @Override protected void requireConfigurationAdministrator(){ }
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected JsonObject requiredRenderedValidationProfile(StorageServiceInstanceVO instance){if(routeProtected)return new JsonObject();throw new AssertionError("Completed retry must not reauthorize an expired execution profile");}
    }
    private Manager manager;private StorageServiceOperationDao operations;private StorageConfigArtifactDao artifacts;private StorageServiceOperationVO completed;private ApplyStorageServiceApprovedMaintenanceCmd command;
    @Before public void setup(){
        com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(11L);CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));manager=new Manager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(manager.instance.getId()).thenReturn(7L);Mockito.when(manager.instance.getUuid()).thenReturn("instance");Mockito.when(manager.instance.getName()).thenReturn("fixture");operations=Mockito.mock(StorageServiceOperationDao.class);artifacts=Mockito.mock(StorageConfigArtifactDao.class);ReflectionTestUtils.setField(manager,"storageOperationDao",operations);ReflectionTestUtils.setField(manager,"storageConfigArtifactDao",artifacts);
        completed=new StorageServiceOperationVO();completed.setInstanceId(7);completed.setCreatedBy(11);completed.setState("COMPLETE");completed.setRevision(3);completed.setResultJson("{\"_requestFingerprint\":\"reviewed-intent\",\"_response\":{\"artifact\":\"public-success\"}}");Mockito.when(operations.findByRequest(7L,"SERVICE_MAINTENANCE:retry")).thenReturn(completed);command=Mockito.mock(ApplyStorageServiceApprovedMaintenanceCmd.class);Mockito.when(command.getInstanceId()).thenReturn(7L);Mockito.when(command.getConfirmation()).thenReturn("fixture");Mockito.when(command.getMaintenanceWindow()).thenReturn(true);Mockito.when(command.getIdempotencyKey()).thenReturn("retry");Mockito.when(command.getExpectedRevision()).thenReturn(2L);
    }
    @After public void cleanup(){CallContext.unregister();}
    private Object replay(){GlobalLock lock=Mockito.mock(GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);try(MockedStatic<GlobalLock> locks=Mockito.mockStatic(GlobalLock.class);MockedStatic<StorageServiceRequestFingerprint> fingerprints=Mockito.mockStatic(StorageServiceRequestFingerprint.class)){locks.when(()->GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);fingerprints.when(()->StorageServiceRequestFingerprint.of(command)).thenReturn("reviewed-intent");return manager.applyStorageServiceApprovedMaintenance(command);}}
    @Test public void completedRetryReturnsOriginalPublicResultBeforeConsumedTokenOldRevisionOrExpiredProfileChecks(){String response=new com.google.gson.Gson().toJson(replay());Assert.assertTrue(response.contains("public-success"));Assert.assertFalse(response.contains("reviewed-intent"));Assert.assertFalse(response.contains("_requestFingerprint"));Mockito.verifyNoInteractions(artifacts);Mockito.verify(operations,Mockito.never()).persist(Mockito.any());}
    @Test public void foreignActorOrParameterIntentCannotReadAnotherCompletedMaintenanceResult(){completed.setCreatedBy(12);Assert.assertThrows(InvalidParameterValueException.class,this::replay);completed.setCreatedBy(11);completed.setResultJson("{\"_requestFingerprint\":\"foreign-intent\"}");Assert.assertThrows(InvalidParameterValueException.class,this::replay);Mockito.verifyNoInteractions(artifacts);}
    @Test public void authenticatedAdMaintenanceUsesOneProtectedWriterBeforeGenericBeginOrStop() {
        manager.routeProtected=true;Mockito.when(operations.findByRequest(7L,"SERVICE_MAINTENANCE:retry")).thenReturn(null);Mockito.when(command.getArtifactId()).thenReturn(8L);StorageConfigArtifactVO artifact=Mockito.mock(StorageConfigArtifactVO.class);Mockito.when(artifact.getInstanceId()).thenReturn(7L);Mockito.when(artifact.getMetadataJson()).thenReturn("{\"plan\":{\"targetInstanceUuid\":\"instance\",\"targetMode\":\"RESTORE_EXISTING\",\"adIdentitySourceDescriptor\":{}}}");Mockito.when(artifacts.findById(8L)).thenReturn(artifact);
        replay();Assert.assertEquals(1,manager.protectedEntries);Mockito.verify(operations,Mockito.never()).persist(Mockito.any());
    }
}
