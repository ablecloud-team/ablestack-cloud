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

import com.cloud.storage.VolumeVO;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageServiceVolumePreparationCmd;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageVolumePreparationObservationTest {
    private static final class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;
        VolumeVO volume;
        boolean unavailable;
        int freshDeadline;
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
        @Override protected VolumeVO requireVolume(Long id) { return volume; }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance, String command, JsonObject request, int timeout) {
            freshDeadline=timeout;
            if(unavailable)throw new CloudRuntimeException("Fresh observation timed out");
            JsonArray disks=request.getAsJsonArray("volumes").deepCopy();
            for(JsonElement value:disks){JsonObject disk=value.getAsJsonObject();disk.addProperty("serial","exact-original-serial");disk.addProperty("matchedBy","VOLUME_SERIAL");disk.addProperty("mappingStatus","EXACT");disk.addProperty("filesystem","xfs");disk.addProperty("filesystemUuid","original-filesystem");disk.addProperty("observedDevicePath","/dev/sdb");}
            JsonObject result=new JsonObject();result.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);result.addProperty("bootId","current-boot");result.add("volumes",disks);return result;
        }
    }
    private Manager manager;
    private GetStorageServiceVolumePreparationCmd command;
    private StorageServiceGuestCommandDispatcher guest;
    @Before public void setup(){
        manager=new Manager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(manager.instance.getVmId()).thenReturn(7L);Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        manager.volume=Mockito.mock(VolumeVO.class);Mockito.when(manager.volume.getInstanceId()).thenReturn(7L);Mockito.when(manager.volume.getUuid()).thenReturn("original-volume");Mockito.when(manager.volume.getSize()).thenReturn(1024L);
        guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);JsonObject history=new JsonObject();history.addProperty("success",true);history.addProperty("state","COMPLETE");history.addProperty("devicePath","/dev/sdc");
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand request=call.getArgument(0);Assert.assertEquals("volume operation status",request.getOperation());Assert.assertTrue(request.getTimeoutSeconds()<=5);return new StorageServiceGuestCommandResult(true,"historical",history.toString());});ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        command=new GetStorageServiceVolumePreparationCmd();ReflectionTestUtils.setField(command,"instanceId",6L);ReflectionTestUtils.setField(command,"volumeId",30L);
    }
    private JsonObject observation(){return new JsonParser().parse((String)ReflectionTestUtils.getField(manager.getStorageServiceVolumePreparation(command),"resultJson")).getAsJsonObject();}
    @Test public void renamedCurrentDeviceDoesNotOverwriteHistoricalJournalAndBothQueriesAreBounded(){JsonObject result=observation();Assert.assertEquals("/dev/sdc",result.get("devicePath").getAsString());Assert.assertTrue(result.get("historicalDevicePathOnly").getAsBoolean());Assert.assertEquals("/dev/sdb",result.getAsJsonObject("currentIdentity").get("observedDevicePath").getAsString());Assert.assertEquals("EXACT",result.get("currentIdentityStatus").getAsString());Assert.assertTrue(manager.freshDeadline<=5);Mockito.verify(guest).dispatch(Mockito.any());}
    @Test public void freshTimeoutReturnsUnavailableWithoutClaimingHistoricalPathCurrent(){manager.unavailable=true;JsonObject result=observation();Assert.assertEquals("COMPLETE",result.get("state").getAsString());Assert.assertEquals("UNAVAILABLE",result.get("currentIdentityStatus").getAsString());Assert.assertFalse(result.has("currentIdentity"));Assert.assertTrue(manager.freshDeadline<=5);}
}
