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
package org.apache.cloudstack.storage.dataservice;

import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageSmbIdentityRepairRuntimeTest {
    private static class Manager extends StorageServiceManagerImpl {
        JsonObject originalGeneration;
        int rebinds;
        boolean sessionBusy,foreignAfter,liveIdentity;
        @Override protected void requireVolumeResumeIdle(StorageServiceInstanceVO instance,StorageServiceOperationVO operation) { }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO instance) { }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action) {
            Assert.assertEquals("status",action);JsonObject value=new JsonObject();value.addProperty("generationStatus","IN_SYNC");value.add("generation",originalGeneration);return value;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout) {
            if ("smb identity rebind".equals(command)) {rebinds++;JsonObject value=StorageSmbIdentityRepairProofTest.result();value.add("scope",smbIdentityRepairScope(instance,writer));return value;}
            Assert.assertEquals("smb identity inspect",command);Assert.assertTrue(timeout<=5);
            JsonObject value=StorageSmbIdentityRepairProofTest.inspection();value.add("scope",request.deepCopy());value.getAsJsonObject("generation").addProperty("instanceUuid",instance.getUuid());originalGeneration=value.getAsJsonObject("generation").deepCopy();
            if (sessionBusy) value.getAsJsonObject("sessions").addProperty("establishedTcpCount",1);
            value.addProperty("identityDatabaseAligned",rebinds>0);value.addProperty("identityRestoreSafe",!liveIdentity);
            if (foreignAfter && rebinds>0)value.getAsJsonObject("databases").getAsJsonObject("PASSDB").addProperty("inode",999);
            return value;
        }
        StorageServiceOperationVO writer;
    }
    private Manager manager;
    private StorageServiceInstanceVO instance;
    private StorageServiceOperationVO operation;
    private StorageServiceOperationDao operations;
    @Before public void setup() {
        manager=new Manager();instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("instance");
        operation=new StorageServiceOperationVO();operation.setAction("SMB_IDENTITY_REPAIR");operation.setRevision(10);operation.setState("RUNNING");JsonObject intent=new JsonObject();intent.addProperty("desiredStateChanged",false);intent.addProperty("baseDesiredRevision",9);operation.setResultJson(intent.toString());manager.writer=operation;
        operations=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationDao",operations);
    }
    @Test public void exactSessionlessRebindCompletesWithoutChangingDesiredOrNativeRevision() {
        manager.recoverSmbIdentityRepair(instance,operation);Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());Assert.assertEquals(1,manager.rebinds);Assert.assertEquals(9,manager.originalGeneration.get("revision").getAsLong());Assert.assertNull(operation.getSnapshotJson());
    }
    @Test public void realConnectionsBlockBeforeOwnedUnitsAreRestarted() {
        manager.sessionBusy=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverSmbIdentityRepair(instance,operation));Assert.assertEquals(0,manager.rebinds);Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
    }
    @Test public void responseLossOrChangedDatabaseIdentityCannotClaimRepairComplete() {
        manager.foreignAfter=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverSmbIdentityRepair(instance,operation));Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());Assert.assertNotNull(operation.getPreviousSnapshotJson());Assert.assertNull(operation.getSnapshotJson());
    }
    @Test public void liveAuthenticationDescriptorsRejectIdentityRestoreBeforeDatabaseRollback() {
        Mockito.when(instance.getVmId()).thenReturn(7L);JsonObject checkpoint=new JsonObject();checkpoint.add("nativeIdentityCapsule",new JsonObject());operation.setPreviousSnapshotJson(checkpoint.toString());manager.liveIdentity=true;
        Assert.assertThrows(CloudRuntimeException.class,()->manager.requireIdentityRollbackSafe(instance,operation));Assert.assertEquals(0,manager.rebinds);
        Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }
}
