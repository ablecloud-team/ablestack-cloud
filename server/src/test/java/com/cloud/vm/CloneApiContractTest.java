// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.cloud.vm;

import java.util.List;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.api.command.user.vm.CloneVMCmd;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CloneApiContractTest {
    private UserVmManagerImpl manager;
    private CloneVMCmd cmd;
    private UserVmVO source;
    private VMInstanceDao vms;
    private VolumeDao volumes;
    private PrimaryDataStoreDao pools;
    private AccountService accounts;

    @Before public void setup() {
        manager = new UserVmManagerImpl(); cmd = new CloneVMCmd(); cmd.setName("qa-contract");
        source = mock(UserVmVO.class);
        when(source.getId()).thenReturn(10L); when(source.getAccountId()).thenReturn(2L);
        when(source.getDomainId()).thenReturn(3L); when(source.getDataCenterId()).thenReturn(4L);
        vms = mock(VMInstanceDao.class); volumes = mock(VolumeDao.class);
        pools = mock(PrimaryDataStoreDao.class); accounts = mock(AccountService.class);
        ReflectionTestUtils.setField(manager, "_vmInstanceDao", vms);
        ReflectionTestUtils.setField(manager, "_volsDao", volumes);
        ReflectionTestUtils.setField(manager, "_storagePoolDao", pools);
        ReflectionTestUtils.setField(manager, "_accountService", accounts);
    }
    private void set(String key, Object value) { ReflectionTestUtils.setField(cmd, key, value); }
    private void reject(Runnable action) { assertThrows(InvalidParameterValueException.class, action::run); }

    @Test public void omittedCountValidatesOneNameAndFiftyValidatesAllNames() {
        manager.validateCloneNames(cmd, 4);
        verify(vms).findVMByHostNameInZone("qa-contract", 4);
        clearInvocations(vms); set("count", 50);
        manager.validateCloneNames(cmd, 4);
        verify(vms, times(50)).findVMByHostNameInZone(anyString(), eq(4L));
        verify(vms).findVMByHostNameInZone("qa-contract-50", 4);
        verifyNoInteractions(volumes, pools, accounts);
    }
    @Test public void invalidCountsFailBeforeAnyResourceLookup() {
        for (int count : new int[]{0, -1, 51, Integer.MAX_VALUE}) {
            set("count", count); reject(() -> manager.validateCloneNames(cmd, 4));
        }
        verifyNoInteractions(vms, volumes, pools, accounts);
    }
    @Test public void longestBatchSuffixMustFitRfcName() {
        set("count", 50); cmd.setName("a".repeat(60)); manager.validateCloneNames(cmd, 4);
        clearInvocations(vms); cmd.setName("a".repeat(61)); reject(() -> manager.validateCloneNames(cmd, 4));
        verifyNoInteractions(vms);
    }
    @Test public void invalidAndDuplicateNamesAreRejected() {
        for (String name : new String[]{"", "1bad", "bad_underscore", "bad-"}) {
            cmd.setName(name); reject(() -> manager.validateCloneNames(cmd, 4));
        }
        cmd.setName("qa-contract"); UserVmVO existing = mock(UserVmVO.class);
        when(existing.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vms.findVMByHostNameInZone("qa-contract", 4)).thenReturn(existing);
        reject(() -> manager.validateCloneNames(cmd, 4));
    }
    @Test public void omittedOrFullTypeDoesNotRequireRbd() {
        manager.validateCloneRequestScope(cmd, source); set("type", "full");
        manager.validateCloneRequestScope(cmd, source); verifyNoInteractions(volumes, pools);
    }
    @Test public void invalidTypesFailForLegacyAsWellAsExplicitSources() {
        for (String type : new String[]{"", "FULL", "snapshot", " full "}) {
            set("type", type); reject(() -> manager.validateCloneRequestScope(cmd, source));
        }
        verifyNoInteractions(volumes, pools);
    }
    private void disks(StoragePoolType secondType) {
        VolumeVO root = mock(VolumeVO.class), data = mock(VolumeVO.class);
        when(root.getPoolId()).thenReturn(30L); when(data.getPoolId()).thenReturn(31L);
        when(volumes.findByInstance(10L)).thenReturn(List.of(root, data));
        StoragePoolVO rootPool = mock(StoragePoolVO.class), dataPool = mock(StoragePoolVO.class);
        when(rootPool.getPoolType()).thenReturn(StoragePoolType.RBD); when(dataPool.getPoolType()).thenReturn(secondType);
        when(pools.findById(30L)).thenReturn(rootPool); when(pools.findById(31L)).thenReturn(dataPool);
    }
    @Test public void linkedRequiresEveryDiskToUseRbd() {
        set("type", "linked"); disks(StoragePoolType.RBD); manager.validateCloneRequestScope(cmd, source);
        disks(StoragePoolType.SharedMountPoint); reject(() -> manager.validateCloneRequestScope(cmd, source));
        when(volumes.findByInstance(10L)).thenReturn(List.of()); reject(() -> manager.validateCloneRequestScope(cmd, source));
    }
    @Test public void sameDomainAndZoneRemainCompatible() {
        set("domainId", 3L); set("zoneIds", List.of(4L)); manager.validateCloneRequestScope(cmd, source);
    }
    @Test public void sameEnabledAccountPairRemainsCompatible() {
        set("accountName", "owner"); set("domainId", 3L);
        Account account = mock(Account.class); when(account.getId()).thenReturn(2L);
        when(account.getState()).thenReturn(Account.State.ENABLED); when(account.getType()).thenReturn(Account.Type.NORMAL);
        when(accounts.getActiveAccountByName("owner", 3L)).thenReturn(account);
        manager.validateCloneRequestScope(cmd, source);
        when(account.getId()).thenReturn(20L); reject(() -> manager.validateCloneRequestScope(cmd, source));
    }
    @Test public void ownerDomainAndZoneChangesFailBeforeDisks() {
        set("accountName", "owner"); reject(() -> manager.validateCloneRequestScope(cmd, source)); set("accountName", null);
        set("domainId", 30L); reject(() -> manager.validateCloneRequestScope(cmd, source)); set("domainId", null);
        for (List<Long> zones : List.of(List.of(40L), List.of(4L, 40L), List.of(4L, 4L))) {
            set("zoneIds", zones); reject(() -> manager.validateCloneRequestScope(cmd, source));
        }
        verifyNoInteractions(volumes, pools);
    }
    @Test public void missingVmIsAnInputError() { reject(() -> manager.validateCloneRequestScope(cmd, null)); }
}
