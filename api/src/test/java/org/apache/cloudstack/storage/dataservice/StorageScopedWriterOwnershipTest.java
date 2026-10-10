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

import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.context.CallContext;
import com.cloud.user.Account;
import com.cloud.user.User;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StorageScopedWriterOwnershipTest {
    @Test public void actualScopedWriterCommandsUseTheValidCallingAccountAndSamePersistentQueue() throws Exception {
        Account account=mock(Account.class);when(account.getId()).thenReturn(41L);
        User user=mock(User.class);when(user.getId()).thenReturn(51L);
        CallContext.register(user,account);
        try {
            String[] commands={
            "org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd",
            "org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd",
            "org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbShareCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfSubsystemCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfNamespaceCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiTargetCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbShareCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfNamespaceCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfSubsystemCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsExportCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceConfigBackupCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageIscsiAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.AttachStorageVolumeToFileShareCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbNetworkAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.ResizeStorageFileShareCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.ResizeStorageServiceBackingVolumeCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageServiceConfigBackupCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfNamespaceCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceConfigImportCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.RestoreStorageServiceLastKnownGoodCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiTargetCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfHostAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.VerifyStorageServiceConfigurationCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbNetworkAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbNetworkAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfHostAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsServiceSettingsCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfHostAclCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.PrepareStorageServiceNvmeOfVmCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DetachStorageServiceBackingVolumeCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageIscsiTargetCmd",
            "org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfSubsystemCmd"
            };
            Assert.assertEquals(47,commands.length);
            for (String name:commands) {
                BaseStorageServiceAsyncCmd command=(BaseStorageServiceAsyncCmd)Class.forName(name).getDeclaredConstructor().newInstance();
                Assert.assertEquals(name,41L,command.getEntityOwnerId());
                Assert.assertEquals(name,"StorageServiceInstance",command.getSyncObjType());
            }
        } finally {CallContext.unregister();}
    }
}
