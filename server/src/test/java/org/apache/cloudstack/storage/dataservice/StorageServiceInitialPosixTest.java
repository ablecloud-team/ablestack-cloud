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

import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class StorageServiceInitialPosixTest {
    @Mock SharedFSDao sharedFSDao;
    @Mock StorageFileShareDao storageFileShareDao;
    @InjectMocks StorageServiceManagerImpl manager;
    SharedFSVO fs; StorageServiceInstanceVO instance; CreateStorageNfsExportCmd cmd;
    SharedFSCreationContext context;
    AutoCloseable mocks;
    String key = "11111111-2222-4333-8444-555555555555";
    @Before public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        fs = mock(SharedFSVO.class); instance = mock(StorageServiceInstanceVO.class);
        cmd = mock(CreateStorageNfsExportCmd.class);
        when(instance.getVmId()).thenReturn(7L); when(instance.getId()).thenReturn(70L);
        when(sharedFSDao.findByVm(7L)).thenReturn(fs);
        when(fs.getState()).thenReturn(SharedFS.State.Ready); when(fs.getCreationRequestId()).thenReturn(key);
        context = new SharedFSCreationContext(); context.existing=true;context.volumeId=10L;context.phase="READY";
        when(fs.getCreationContext()).thenAnswer(x->context.json());
        when(cmd.getInitialCreationRequestId()).thenReturn(key);
        when(cmd.getVolumeId()).thenReturn(10L);when(cmd.getImportMode()).thenReturn("MOUNT_EXISTING");
        when(cmd.getOwnerUid()).thenReturn(null);when(cmd.getOwnerGid()).thenReturn(null);
        when(storageFileShareDao.listByInstanceIdAndProtocol(70L,StorageServiceInstance.Protocol.NFS)).thenReturn(java.util.List.of());
    }
    @After public void tearDown() throws Exception {mocks.close();}
    @Test public void originalExistingInitialRequestPreserves() { assertTrue(manager.preserveInitialImportedNfsPosix(instance,cmd)); }
    @Test public void ordinaryNewOrLaterShareHasNoPolicy() {
        when(cmd.getInitialCreationRequestId()).thenReturn(null);
        assertFalse(manager.preserveInitialImportedNfsPosix(instance,cmd));
        verifyNoInteractions(sharedFSDao,storageFileShareDao);
    }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void requestForANewVolumeCannotUsePreservationIntent() {context.existing=false;manager.preserveInitialImportedNfsPosix(instance,cmd);}
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void differentRequestCannotClaimInitialSetup() {when(cmd.getInitialCreationRequestId()).thenReturn("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");manager.preserveInitialImportedNfsPosix(instance,cmd);}
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void initialSetupCannotInitializeExistingOwnership() {when(cmd.getOwnerUid()).thenReturn(65534);manager.preserveInitialImportedNfsPosix(instance,cmd);}
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void laterExportCannotReuseInitialPolicy() {when(storageFileShareDao.listByInstanceIdAndProtocol(70L,StorageServiceInstance.Protocol.NFS)).thenReturn(java.util.List.of(mock(StorageFileShareVO.class)));manager.preserveInitialImportedNfsPosix(instance,cmd);}
    @Test public void preservedConfigKeepsSquashMappingButNoSyntheticOwner() {
        JsonObject c=new JsonObject();c.addProperty("rootSquash",true);c.addProperty("posixPolicy","PRESERVE_EXISTING_INITIAL");
        manager.applyNfsWritableRootSquashDefaults(c,true);
        assertEquals(65534,c.get("anonUid").getAsInt());
        assertFalse(c.has("ownerUid"));assertFalse(c.has("ownerGid"));assertFalse(c.has("mode"));
        c.addProperty("ownerUid",1234);c.addProperty("mode","0750");
        manager.applyNfsWritableRootSquashDefaults(c,true);
        assertEquals(1234,c.get("ownerUid").getAsInt());assertEquals("0750",c.get("mode").getAsString());
    }
    @Test public void ordinaryConfigRetainsLegacyInitialization() {
        JsonObject c=new JsonObject();c.addProperty("rootSquash",true);
        manager.applyNfsWritableRootSquashDefaults(c,true);
        assertEquals(65534,c.get("ownerUid").getAsInt());assertEquals("0775",c.get("mode").getAsString());
    }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void oldGuestIsRejectedBeforeNfsApply() {
        StorageFileShareVO share=mock(StorageFileShareVO.class);
        when(share.getConfigJson()).thenReturn("{\"lastInspection\":{}}");
        manager.requireInitialPosixPreservationRuntime(share);
    }
    @Test public void verifiedGuestAllowsNfsApply() {
        StorageFileShareVO share=mock(StorageFileShareVO.class);
        when(share.getConfigJson()).thenReturn("{\"lastInspection\":{\"supportsInitialPosixPreservation\":true}}");
        manager.requireInitialPosixPreservationRuntime(share);
    }
}
