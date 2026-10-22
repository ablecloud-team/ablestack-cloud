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
import org.apache.cloudstack.storage.sharedfs.*;
import org.apache.cloudstack.storage.dataservice.dao.*;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.google.gson.*;
import org.junit.*;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class StorageServiceInitialBackingTest {
    @Mock StorageServiceInstanceDao storageServiceInstanceDao;
    @Mock VolumeDao volumeDao;
    @Mock StorageServiceGuestCommandDispatcher guestCommandDispatcher;
    @Spy @InjectMocks StorageServiceManagerImpl manager;
    SharedFSVO fs;
    StorageServiceInstanceVO instance;
    VolumeVO volume;
    AutoCloseable mocks;
    final String uuid="11111111-2222-4333-8444-555555555555";
    @Before public void setUp() {
        mocks=MockitoAnnotations.openMocks(this);fs=mock(SharedFSVO.class);instance=mock(StorageServiceInstanceVO.class);volume=mock(VolumeVO.class);
        when(fs.getVmId()).thenReturn(7L);when(fs.getVolumeId()).thenReturn(10L);when(fs.getAccountId()).thenReturn(2L);
        SharedFSCreationContext c=new SharedFSCreationContext();c.existing=true;c.volumeId=10L;c.expectedFilesystem="AUTO";
        when(fs.getCreationContext()).thenReturn(c.json());
        when(instance.getId()).thenReturn(70L);when(instance.getVmId()).thenReturn(7L);when(instance.getUuid()).thenReturn("instance-uuid");
        when(storageServiceInstanceDao.findByVmId(7L)).thenReturn(instance);doReturn(instance).when(manager).requireInstance(70L);
        when(volumeDao.findById(10L)).thenReturn(volume);when(volume.getInstanceId()).thenReturn(7L);
        when(volume.getAccountId()).thenReturn(2L);when(volume.getId()).thenReturn(10L);when(volume.getUuid()).thenReturn(uuid);
        when(volume.getSize()).thenReturn(21474836480L);
        when(guestCommandDispatcher.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok","{\"volumeUuid\":\""+uuid+"\",\"filesystem\":\"ext4\"}"));
    }
    @After public void tearDown() throws Exception {mocks.close();}
    @Test public void inspectUsesIdentityOnlyAndCannotCreateDirectories() {
        manager.prepareInitialImportedBackingVolume(fs,"INSPECT_ONLY");
        ArgumentCaptor<StorageServiceGuestCommand> cap=ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
        verify(guestCommandDispatcher).dispatch(cap.capture());
        JsonObject p=JsonParser.parseString(cap.getValue().getPayload()).getAsJsonObject();
        assertEquals("INSPECT_ONLY",p.get("importMode").getAsString());assertEquals(uuid,p.get("volumeUuid").getAsString());
        assertFalse(p.has("shareId"));assertFalse(p.has("shareUuid"));assertFalse(p.has("path"));
        assertFalse(p.getAsJsonObject("config").get("createDirectory").getAsBoolean());
        assertEquals("",p.getAsJsonObject("config").get("relativeSharePath").getAsString());
    }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void existingFormatModeIsRejectedBeforeDispatch() {manager.prepareInitialImportedBackingVolume(fs,"FORMAT_IF_EMPTY");}
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void attachmentMismatchIsRejectedBeforeDispatch() {when(volume.getInstanceId()).thenReturn(99L);manager.prepareInitialImportedBackingVolume(fs,"MOUNT_EXISTING");}
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class)
    public void returnedVolumeIdentityCannotBeAdopted() {
        when(guestCommandDispatcher.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok","{\"volumeUuid\":\"different\"}"));
        manager.prepareInitialImportedBackingVolume(fs,"INSPECT_ONLY");
    }
}
