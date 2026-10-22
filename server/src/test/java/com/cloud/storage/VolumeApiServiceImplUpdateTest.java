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
package com.cloud.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeApiServiceImplUpdateTest {
    @Spy @InjectMocks private VolumeApiServiceImpl service = new VolumeApiServiceImpl();
    @Mock private VolumeDao volumeDao;
    @Mock private com.cloud.storage.dao.VolumeDetailsDao allocationDetails;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private UserVmDao userVmDao;
    @Mock private AccountManager accountManager;
    @Mock private Account account;
    @Mock private User user;
    @Mock private DiskOfferingVO offering;
    @Mock private UserVmVO vm;
    private VolumeVO volume;

    @Before
    public void setUp() {
        when(account.getId()).thenReturn(2L);
        CallContext.register(user, account);
        when(accountManager.isRootAdmin(2L)).thenReturn(true);
        volume = new VolumeVO(Volume.Type.DATADISK, "original", 1L, 1L, 2L, 3L,
                Storage.ProvisioningType.THIN, 1073741824L, null, null, null);
        ReflectionTestUtils.setField(volume, "id", 100L);
        volume.setPath("original-path");
        volume.setChainInfo("original-chain");
        volume.setPoolId(9L);
        volume.setState(Volume.State.Ready);
        volume.setFormat(Storage.ImageFormat.QCOW2);
        when(volumeDao.findById(100L)).thenReturn(volume);
        when(diskOfferingDao.findById(3L)).thenReturn(offering);
        doNothing().when(service).updateDisplay(any(), any());
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    private void update(String path, String name, Boolean protection) {
        service.updateVolume(100L, path, null, null, null, protection, null, 2L, null, name, null);
    }

    private void assertStorageUnchanged() {
        assertEquals("original-path", volume.getPath());
        assertEquals("original-chain", volume.getChainInfo());
        assertEquals(Long.valueOf(9L), volume.getPoolId());
        assertEquals(Volume.State.Ready, volume.getState());
        assertEquals(Storage.ImageFormat.QCOW2, volume.getFormat());
        assertEquals(Volume.Type.DATADISK, volume.getVolumeType());
        assertEquals(Long.valueOf(1073741824L), volume.getSize());
    }

    @Test
    public void nameOnlyPreservesStorage() {
        update(null, "renamed", null);
        assertEquals("renamed", volume.getName());
        assertFalse(volume.isDeleteProtection());
        assertStorageUnchanged();
        verify(volumeDao).update(100L, volume);
    }

    @Test
    public void protectionOnlyPreservesStorage() {
        update(null, null, true);
        assertEquals("original", volume.getName());
        assertTrue(volume.isDeleteProtection());
        assertStorageUnchanged();
    }

    @Test
    public void combinedUpdatePreservesStorage() {
        update(null, "renamed", true);
        assertEquals("renamed", volume.getName());
        assertTrue(volume.isDeleteProtection());
        assertStorageUnchanged();
    }

    @Test
    public void nonRootCanUpdateNameAndProtectionWithoutPath() {
        when(accountManager.isRootAdmin(2L)).thenReturn(false);
        update(null, "renamed", true);
        assertTrue(volume.isDeleteProtection());
        assertStorageUnchanged();
    }

    @Test
    public void nonRootCannotChangePathOrType() {
        when(accountManager.isRootAdmin(2L)).thenReturn(false);
        assertRejected(() -> update("different", "renamed", true));
        assertRejected(() -> service.updateVolume(100L, null, null, null, null, null, null, 2L, null, null, "ROOT"));
        assertStorageUnchanged();
    }

    @Test
    public void accessRejectionLeavesAllFieldsUnchanged() {
        doThrow(new PermissionDeniedException("Not the owner")).when(accountManager).checkAccess(eq(account), any(), eq(true), eq(volume));
        try {
            update(null, "renamed", true);
            fail("Expected access rejection");
        } catch (PermissionDeniedException expected) {
            assertEquals("original", volume.getName());
            assertFalse(volume.isDeleteProtection());
            assertStorageUnchanged();
            verify(volumeDao, never()).update(anyLong(), any());
        }
    }

    @Test
    public void blankPathCannotClearExistingPath() {
        for (String path : new String[]{"", " ", "\t"}) {
            assertRejected(() -> update(path, "renamed", true));
            assertEquals("original", volume.getName());
            assertFalse(volume.isDeleteProtection());
            assertStorageUnchanged();
        }
    }

    @Test
    public void unchangedPathIsNoOpForRunningAttachedVolume() {
        volume.setInstanceId(5L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmDao.findById(5L)).thenReturn(vm);
        update("original-path", "renamed", null);
        assertStorageUnchanged();
        verify(diskOfferingDao, never()).findById(anyLong());
    }

    @Test
    public void explicitPathCanBeRepairedForDetachedReadyVolume() {
        update("repaired-path", null, null);
        assertEquals("repaired-path", volume.getPath());
        assertEquals("original-chain", volume.getChainInfo());
        assertEquals(Long.valueOf(9L), volume.getPoolId());
    }

    @Test
    public void missingPathCanBeRepairedForStoppedAttachedVolume() {
        volume.setPath(null);
        volume.setInstanceId(5L);
        when(userVmDao.findById(5L)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        update("verified-original-path", null, null);
        assertEquals("verified-original-path", volume.getPath());
    }

    @Test
    public void actualPathChangeRequiresStoppedVm() {
        volume.setInstanceId(5L);
        when(userVmDao.findById(5L)).thenReturn(vm);
        for (VirtualMachine.State state : new VirtualMachine.State[]{VirtualMachine.State.Running, VirtualMachine.State.Starting, VirtualMachine.State.Stopping}) {
            when(vm.getState()).thenReturn(state);
            assertRejected(() -> update("different", "renamed", null));
            assertStorageUnchanged();
        }
    }

    @Test
    public void actualPathChangeRejectsUnknownVm() {
        volume.setInstanceId(5L);
        assertRejected(() -> update("different", null, null));
        assertStorageUnchanged();
    }

    @Test
    public void actualPathChangeRejectsInProgressAndDestroyedVolume() {
        for (Volume.State state : new Volume.State[]{Volume.State.Resizing, Volume.State.Migrating, Volume.State.Destroy}) {
            volume.setState(state);
            assertRejected(() -> update("different", "renamed", true));
            assertEquals("original-path", volume.getPath());
            assertEquals(state, volume.getState());
        }
    }

    @Test
    public void pathChangeWithMissingOfferingIsRejected() {
        when(diskOfferingDao.findById(3L)).thenReturn(null);
        assertRejected(() -> update("different", null, null));
        assertStorageUnchanged();
    }

    @Test
    public void explicitShareablePathKeepsExistingMappingContract() {
        when(offering.getShareable()).thenReturn(true);
        VolumeVO source = new VolumeVO(volume);
        source.setPoolId(12L);
        source.setFormat(Storage.ImageFormat.RAW);
        when(volumeDao.findByPath("shared-path")).thenReturn(source);
        update("shared-path", null, null);
        assertEquals("shared-path", volume.getPath());
        assertEquals(Long.valueOf(12L), volume.getPoolId());
        assertEquals(Storage.ImageFormat.RAW, volume.getFormat());
        assertEquals(Volume.State.Ready, volume.getState());
    }

    private void assertRejected(Runnable call) {
        try {
            call.run();
            fail("Expected parameter rejection");
        } catch (InvalidParameterValueException expected) {
            verify(volumeDao, never()).update(anyLong(), any());
        }
    }
}
