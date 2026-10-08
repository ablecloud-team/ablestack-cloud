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

import java.util.List;
import java.util.function.Supplier;
import com.cloud.storage.VolumeVO;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StorageSmbShareUpdatePathTest {
    private static class Manager extends StorageServiceManagerImpl {
        StorageFileShareVO share;
        StorageServiceInstanceVO instance;
        VolumeVO volume;
        int prepares,applies,rebindings;
        @Override protected <T> T executeDesiredChange(BaseCmd cmd,Class<T> type,Supplier<T> action) {return action.get();}
        @Override protected StorageFileShareVO requireSmbShare(Long id) {return share;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {return instance;}
        @Override protected VolumeVO requireVolume(Long id) {return volume;}
        @Override protected void validateStorageServiceBackingVolume(StorageServiceInstanceVO owner,Long id,String kind) {Assert.assertEquals(instance,owner);}
        @Override protected void inheritPosixDirectoryPolicy(StorageServiceInstanceVO owner,StorageFileShareVO resource,Long id,Integer uid,Integer gid,String mode) { }
        @Override protected void prepareFileShareBackingVolume(StorageServiceInstanceVO owner,StorageFileShareVO resource,String mode) {prepares++;Assert.assertEquals("MOUNT_EXISTING",mode);}
        @Override protected void applySmbDesiredState(StorageServiceInstanceVO owner) {applies++;}
        @Override protected StorageSmbShareResponse createSmbShareResponse(StorageFileShareVO resource) {return new StorageSmbShareResponse();}
        @Override protected JsonObject inspectSmbReboundFilesystem(StorageServiceInstanceVO owner,VolumeVO selected) {rebindings++;JsonObject actual=new JsonObject();actual.addProperty("filesystemUuid","fresh-new-filesystem");return actual;}
    }
    private Manager manager;
    private StorageFileShareDao shares;
    private UpdateStorageSmbShareCmd cmd;
    @Before public void setup() {
        manager=new Manager();manager.instance=mock(StorageServiceInstanceVO.class);when(manager.instance.getId()).thenReturn(7L);
        manager.volume=mock(VolumeVO.class);when(manager.volume.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
        manager.share=new StorageFileShareVO(7L,StorageServiceInstance.Protocol.SMB,"visible","/export/parent/child",10L,"xfs",null,StorageServiceInstance.ResourceState.Ready,
                "{\"relativeSharePath\":\"parent/child\",\"filesystemUuid\":\"old-filesystem\",\"backingPath\":\"/old/parent/child\",\"lastInspection\":{}}");
        ReflectionTestUtils.setField(manager.share,"id",1L);
        shares=mock(StorageFileShareDao.class);when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(manager.share));ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
        cmd=mock(UpdateStorageSmbShareCmd.class);when(cmd.getId()).thenReturn(1L);when(cmd.getVolumeId()).thenReturn(null);when(cmd.getOwnerUid()).thenReturn(null);when(cmd.getOwnerGid()).thenReturn(null);when(cmd.getPosixPolicyId()).thenReturn(null);when(cmd.getQuotaBytes()).thenReturn(null);
    }
    @Test public void pathOnlyNestedUpdateUsesStoredRelativePathAndClearsOldPhysicalObservation() {
        when(cmd.getPath()).thenReturn("/export/parent/child");manager.updateStorageSmbShare(cmd);
        Assert.assertEquals("/export/parent/child",manager.share.getPath());Assert.assertEquals(1,manager.applies);Assert.assertEquals(0,manager.rebindings);
    }
    @Test public void volumeOnlyChangeMustRejectCrossVolumeParentOverlapBeforePrepareOrApply() {
        StorageFileShareVO parent=new StorageFileShareVO(7L,StorageServiceInstance.Protocol.NFS,"parent","/export/parent",10L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{\"relativeSharePath\":\"parent\"}");ReflectionTestUtils.setField(parent,"id",2L);
        when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(parent));when(cmd.getVolumeId()).thenReturn(11L);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.updateStorageSmbShare(cmd));Assert.assertEquals(0,manager.prepares);Assert.assertEquals(0,manager.applies);Assert.assertEquals(0,manager.rebindings);verify(shares,never()).update(anyLong(),any());
    }
    @Test public void selectedVolumeRebindPinsFreshUuidAndDropsOldDirectoryObservation() {
        when(cmd.getVolumeId()).thenReturn(11L);manager.updateStorageSmbShare(cmd);
        JsonObject config=JsonParser.parseString(manager.share.getConfigJson()).getAsJsonObject();Assert.assertEquals("fresh-new-filesystem",config.get("filesystemUuid").getAsString());Assert.assertFalse(config.has("backingPath"));Assert.assertFalse(config.has("lastInspection"));Assert.assertEquals(1,manager.rebindings);Assert.assertEquals(1,manager.prepares);
    }
    @Test public void renamePreservesLegacyPhysicalDirectoryWithoutMovingData() {
        manager.share.setPath("/export/legacy");manager.share.setName("legacy");manager.share.setConfigJson("{\"filesystemUuid\":\"original-filesystem\",\"backingPath\":\"/data/export/legacy\"}");when(cmd.getName()).thenReturn("new-visible");manager.updateStorageSmbShare(cmd);
        Assert.assertEquals("new-visible",manager.share.getName());Assert.assertEquals("/export/legacy",manager.share.getPath());Assert.assertEquals("/data/export/legacy",JsonParser.parseString(manager.share.getConfigJson()).getAsJsonObject().get("backingPath").getAsString());Assert.assertEquals(0,manager.rebindings);
    }
    @Test public void crossProtocolReuseRequiresSameSelectedVolumeAndExplicitApproval() {
        StorageFileShareVO nfs=new StorageFileShareVO(7L,StorageServiceInstance.Protocol.NFS,"nfs","/export/parent/child",10L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{\"relativeSharePath\":\"parent/child\"}");ReflectionTestUtils.setField(nfs,"id",2L);when(shares.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.NFS)).thenReturn(List.of(nfs));when(cmd.getName()).thenReturn("new-visible");
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.updateStorageSmbShare(cmd));when(cmd.getCrossProtocol()).thenReturn(true);manager.updateStorageSmbShare(cmd);Assert.assertEquals(1,manager.applies);
    }
}
