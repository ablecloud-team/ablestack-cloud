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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.cloudstack.api.command.admin.storage.dataservice.RepairStorageServiceNicIdentityCmd;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageNicIdentityRepairTest {
    private static final String DECLARED_PRIMARY = "10.10.13.240";
    private static final String ALIAS = "10.10.13.241";

    private static final class Manager extends StorageServiceManagerImpl {
        private String runtimePrimary = DECLARED_PRIMARY;
        private JsonObject result;

        @Override
        protected String observeRuntimePrimaryIp(StorageServiceInstanceVO instance) {
            Assert.assertEquals(Long.valueOf(50), instance.getVmId());
            return runtimePrimary;
        }

        @Override
        protected StorageServiceRuntimeResponse createRuntimeResponse(StorageServiceInstanceVO instance,
                String operation, boolean success, String status, String details, String json) {
            result = JsonParser.parseString(json).getAsJsonObject();
            return new StorageServiceRuntimeResponse();
        }
    }

    private Manager manager;
    private NicDao nics;
    private NicSecondaryIpDao aliases;
    private NicVO nic;
    private RepairStorageServiceNicIdentityCmd cmd;
    private AtomicReference<String> primary;

    @Before
    public void setup() {
        manager = new Manager();
        SharedFSDao shared = Mockito.mock(SharedFSDao.class);
        StorageServiceInstanceDao instances = Mockito.mock(StorageServiceInstanceDao.class);
        SharedFSVO fs = Mockito.mock(SharedFSVO.class);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(shared.findById(4L)).thenReturn(fs);
        Mockito.when(fs.getId()).thenReturn(4L);
        Mockito.when(fs.getUuid()).thenReturn("sharedfs-test");
        Mockito.when(fs.getVmId()).thenReturn(50L);
        Mockito.when(instances.findByVmId(50L)).thenReturn(instance);
        Mockito.when(instance.getVmId()).thenReturn(50L);
        Mockito.when(instance.getUuid()).thenReturn("instance-test");
        nics = Mockito.mock(NicDao.class);
        aliases = Mockito.mock(NicSecondaryIpDao.class);
        nic = Mockito.mock(NicVO.class);
        primary = new AtomicReference<>(ALIAS);
        Mockito.when(nic.getId()).thenReturn(79L);
        Mockito.when(nic.getUuid()).thenReturn("nic-test");
        Mockito.when(nic.getIPv4Address()).thenAnswer(invocation -> primary.get());
        Mockito.when(nics.findDefaultNicForVM(50L)).thenReturn(nic);
        Mockito.when(nics.findById(79L)).thenReturn(nic);
        NicSecondaryIpVO alias = Mockito.mock(NicSecondaryIpVO.class);
        Mockito.when(alias.getIp4Address()).thenReturn(ALIAS);
        Mockito.when(aliases.listByNicId(79L)).thenReturn(List.of(alias));
        Mockito.when(nics.updatePrimaryIpAddress(79L, DECLARED_PRIMARY, ALIAS)).thenAnswer(invocation -> {
            primary.set(DECLARED_PRIMARY);
            return true;
        });
        ReflectionTestUtils.setField(manager, "sharedFSDao", shared);
        ReflectionTestUtils.setField(manager, "storageServiceInstanceDao", instances);
        ReflectionTestUtils.setField(manager, "nicDao", nics);
        ReflectionTestUtils.setField(manager, "nicSecondaryIpDao", aliases);
        cmd = Mockito.mock(RepairStorageServiceNicIdentityCmd.class);
        Mockito.when(cmd.getSharedFileSystemId()).thenReturn(4L);
        Mockito.when(cmd.getExpectedRuntimePrimary()).thenReturn(DECLARED_PRIMARY);
    }

    @Test
    public void dryRunReportsDriftWithoutChangingNicOrAliases() {
        Mockito.when(cmd.isDryRun()).thenReturn(true);
        manager.repairStorageServiceNicIdentity(cmd);
        Assert.assertTrue(manager.result.get("eligible").getAsBoolean());
        Assert.assertEquals("ELIGIBLE", manager.result.get("reason").getAsString());
        Mockito.verify(nics, Mockito.never()).updatePrimaryIpAddress(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
        Mockito.verify(aliases, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void explicitRepairUsesCompareAndSetAndPreservesRegisteredAlias() {
        try (MockedStatic<CallContext> context = Mockito.mockStatic(CallContext.class)) {
            context.when(CallContext::current).thenReturn(Mockito.mock(CallContext.class));
            manager.repairStorageServiceNicIdentity(cmd);
        }
        Mockito.verify(nics).updatePrimaryIpAddress(79L, DECLARED_PRIMARY, ALIAS);
        Assert.assertTrue(manager.result.get("aliasesPreserved").getAsBoolean());
        Assert.assertEquals(DECLARED_PRIMARY, manager.result.get("persistedPrimaryIpAfter").getAsString());
        Assert.assertEquals(ALIAS, manager.result.getAsJsonArray("aliasesAfter").get(0).getAsString());
    }

    @Test
    public void concurrentPrimaryChangeRejectsRepairWithoutOverwritingIt() {
        Mockito.when(nics.updatePrimaryIpAddress(79L, DECLARED_PRIMARY, ALIAS)).thenReturn(false);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.repairStorageServiceNicIdentity(cmd));
        Mockito.verify(nics, Mockito.never()).findById(79L);
    }

    @Test
    public void expectedRuntimePrimaryMustStillMatchBeforeCas() {
        Mockito.when(cmd.getExpectedRuntimePrimary()).thenReturn("10.10.13.242");
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.repairStorageServiceNicIdentity(cmd));
        Mockito.verify(nics, Mockito.never()).updatePrimaryIpAddress(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void unknownRuntimeOrUnregisteredOldPrimaryCannotAuthorizeRepair() {
        manager.runtimePrimary = null;
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.repairStorageServiceNicIdentity(cmd));
        manager.runtimePrimary = DECLARED_PRIMARY;
        Mockito.when(aliases.listByNicId(79L)).thenReturn(List.of());
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.repairStorageServiceNicIdentity(cmd));
        Mockito.verify(nics, Mockito.never()).updatePrimaryIpAddress(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void alreadyConsistentIsReadOnlyAndIneligible() {
        primary.set(DECLARED_PRIMARY);
        Mockito.when(cmd.isDryRun()).thenReturn(true);
        manager.repairStorageServiceNicIdentity(cmd);
        Assert.assertFalse(manager.result.get("eligible").getAsBoolean());
        Assert.assertEquals("ALREADY_CONSISTENT", manager.result.get("reason").getAsString());
        Mockito.verify(nics, Mockito.never()).updatePrimaryIpAddress(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    }
}
