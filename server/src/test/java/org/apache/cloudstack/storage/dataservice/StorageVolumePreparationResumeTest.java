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
import java.util.UUID;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageVolumePreparationResumeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        VolumeVO volume;boolean busy,noFormat=true,wrongFilesystem,partial;int mutations;
        @Override protected VolumeVO requireVolume(Long id){return volume;}
        @Override protected void validateVolumeResumeScope(StorageServiceInstanceVO instance,VolumeVO volume){ }
        @Override protected void requireVolumeFormatterCompletion(StorageServiceInstanceVO instance, VolumeVO volume) {if(partial)throw new CloudRuntimeException("formatter has only a partial header");}
        @Override protected void requireVolumeResumeIdle(StorageServiceInstanceVO instance,StorageServiceOperationVO own){if(busy)throw new CloudRuntimeException("formatter active");}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){Assert.assertEquals("volume operation resume",command);Assert.assertEquals("MOUNT_EXISTING",request.get("importMode").getAsString());Assert.assertTrue(request.get("resumeOnly").getAsBoolean());Assert.assertFalse(request.has("devicePath"));mutations++;JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("resumed",true);result.addProperty("resumeOnly",true);result.addProperty("formatInvoked",!noFormat);result.addProperty("formatterActive",false);result.add("instanceUuid",request.get("instanceUuid"));result.add("managerOperationUuid",request.get("managerOperationUuid"));result.add("revision",request.get("revision"));result.add("volumeUuid",request.get("volumeUuid"));result.add("operationId",request.get("operationId"));result.addProperty("matchedBy","VOLUME_SERIAL");result.addProperty("filesystemUuid",wrongFilesystem?"foreign":"original-filesystem");return result;}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private StorageServiceOperationVO operation;private StorageServiceOperationDao operations;
    @Before public void setup(){manager=new Manager();manager.volume=Mockito.mock(VolumeVO.class);String uuid=UUID.randomUUID().toString();Mockito.when(manager.volume.getUuid()).thenReturn(uuid);Mockito.when(manager.volume.getSize()).thenReturn(1024L);Mockito.when(manager.volume.getProvisioningType()).thenReturn(ProvisioningType.THIN);instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn(UUID.randomUUID().toString());operation=new StorageServiceOperationVO();operation.setAction("VOLUME_PREPARATION_RESUME");operation.setRevision(8);operation.setState("RECOVERY_REQUIRED");JsonObject scope=new JsonObject();scope.addProperty("volumeId",3);scope.addProperty("volumeUuid",uuid);scope.addProperty("volumeSizeBytes",1024);scope.addProperty("provisioningType","THIN");scope.addProperty("operationId","volume-"+uuid);scope.addProperty("expectedFilesystemUuid","original-filesystem");operation.setPreviousSnapshotJson(scope.toString());operation.setResultJson("{\"baseDesiredRevision\":7,\"desiredStateChanged\":false}");operations=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationDao",operations);}
    @Test public void interruptedPreparationResumesForwardAndDoesNotCommitANewDesiredRevision(){manager.recoverVolumePreparation(instance,operation);Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());Assert.assertEquals(8,operation.getRevision());Assert.assertEquals(1,manager.mutations);Assert.assertTrue(operation.getResultJson().contains("original-filesystem"));Assert.assertNull(operation.getSnapshotJson());}
    @Test public void activeFormatterAndChangedPinnedSizeAreRejectedBeforeMutation(){manager.busy=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,manager.mutations);manager.busy=false;Mockito.when(manager.volume.getSize()).thenReturn(2048L);Assert.assertThrows(InvalidParameterValueException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,manager.mutations);}
    @Test public void filesystemMismatchOrAbsentNoFormatEvidenceKeepsRecoveryRequired(){manager.wrongFilesystem=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());manager.wrongFilesystem=false;manager.noFormat=false;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());}
    @Test public void failedDurableIntentCannotDispatchNativeMutation(){Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(false);Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,manager.mutations);}
    @Test public void partialHeaderWithoutSuccessfulFormatterProofNeverDispatchesMountOrProbe() {
        manager.partial=true;
        Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));
        Assert.assertEquals(0,manager.mutations);
        Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
    }

}
