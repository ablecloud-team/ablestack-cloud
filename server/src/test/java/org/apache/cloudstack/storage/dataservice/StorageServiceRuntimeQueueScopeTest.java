// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StorageServiceRuntimeQueueScopeTest {
    private StorageServiceManagerImpl manager;
    private StorageServiceRuntimeUpgradeDao upgrades;
    private org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao acls;
    private org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao targets;
    private org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao shares;
    private AccountManager accounts;
    private Account callingAccount;
    private StorageServiceInstanceVO scopedInstance;
    @Before public void prepare() {
        User user=mock(User.class); Account account=mock(Account.class);
        when(user.getId()).thenReturn(1L); when(account.getId()).thenReturn(2L);
        CallContext.register(user,account);
        callingAccount = account;
        manager=new StorageServiceManagerImpl();
        StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);
        SharedFSDao shared=mock(SharedFSDao.class);
        upgrades=mock(StorageServiceRuntimeUpgradeDao.class);
        ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
        ReflectionTestUtils.setField(manager,"sharedFSDao",shared);
        ReflectionTestUtils.setField(manager,"storageRuntimeUpgradeDao",upgrades);
        accounts = mock(AccountManager.class);
        acls = mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);
        targets = mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class);
        shares = mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        ReflectionTestUtils.setField(manager, "storageAccountManager", accounts);
        ReflectionTestUtils.setField(manager, "storageAccessRuleDao", acls);
        ReflectionTestUtils.setField(manager, "storageBlockTargetDao", targets);
        ReflectionTestUtils.setField(manager, "storageFileShareDao", shares);
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        scopedInstance = instance;
        when(instance.getId()).thenReturn(7L);
        when(instances.findById(7L)).thenReturn(instance);
        when(instances.findByVmId(41L)).thenReturn(instance);
        SharedFSVO fs=mock(SharedFSVO.class);when(fs.getVmId()).thenReturn(41L);
        when(shared.findById(3L)).thenReturn(fs);
        StorageServiceRuntimeUpgradeVO upgrade=mock(StorageServiceRuntimeUpgradeVO.class);
        when(upgrade.getInstanceId()).thenReturn(7L);when(upgrades.findById(11L)).thenReturn(upgrade);
    }
    @After public void clear(){CallContext.unregister();}
    private void assertScope(BaseStorageServiceAsyncCmd cmd) {
        ReflectionTestUtils.setField(cmd,"storageServiceScope",manager);
        Assert.assertEquals("StorageServiceInstance",cmd.getSyncObjType());
        Assert.assertEquals(Long.valueOf(7),cmd.getSyncObjId());
    }
    @Test public void preflightActivateRollbackShareTheSamePersistentWriterQueue() {
        PreflightStorageServiceRuntimeUpgradeCmd preflight=new PreflightStorageServiceRuntimeUpgradeCmd();
        ReflectionTestUtils.setField(preflight,"sharedFileSystemId",3L);assertScope(preflight);
        UpgradeStorageServiceRuntimeCmd activate=new UpgradeStorageServiceRuntimeCmd();
        ReflectionTestUtils.setField(activate,"upgradeId",11L);assertScope(activate);
        RollbackStorageServiceRuntimeUpgradeCmd rollback=new RollbackStorageServiceRuntimeUpgradeCmd();
        ReflectionTestUtils.setField(rollback,"upgradeId",11L);assertScope(rollback);
    }
    @Test public void unavailableRuntimeTransactionIsRejectedBeforeQueueDispatch() {
        UpgradeStorageServiceRuntimeCmd cmd=new UpgradeStorageServiceRuntimeCmd();
        ReflectionTestUtils.setField(cmd,"upgradeId",99L);
        ReflectionTestUtils.setField(cmd,"storageServiceScope",manager);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class,cmd::getSyncObjId);
    }

    private BaseStorageServiceAsyncCmd aclCommand(String type, Long id) throws Exception {
        BaseStorageServiceAsyncCmd command = (BaseStorageServiceAsyncCmd) Class.forName(
                "org.apache.cloudstack.api.command.user.storage.dataservice." + type).getDeclaredConstructor().newInstance();
        ReflectionTestUtils.setField(command, "id", id);
        ReflectionTestUtils.setField(command, "storageServiceScope", manager);
        return command;
    }

    private StorageAccessRuleVO acl(StorageServiceInstance.AccessResourceType type) {
        StorageAccessRuleVO rule = mock(StorageAccessRuleVO.class);
        when(rule.getResourceType()).thenReturn(type);
        when(rule.getResourceId()).thenReturn(13L);
        when(acls.findById(21L)).thenReturn(rule);
        return rule;
    }

    @Test
    public void realAclUpdateAndDeleteCommandsResolveFileAndBlockOwnership() throws Exception {
        String[] commands = {"UpdateStorageIscsiAclCmd", "DeleteStorageIscsiAclCmd",
                "UpdateStorageNvmeOfHostAclCmd", "DeleteStorageNvmeOfHostAclCmd",
                "UpdateStorageNfsAclCmd", "DeleteStorageNfsAclCmd",
                "UpdateStorageSmbAclCmd", "DeleteStorageSmbAclCmd"};
        for (String type : commands) {
            boolean block = type.contains("Iscsi") || type.contains("Nvme");
            acl(block ? StorageServiceInstance.AccessResourceType.BLOCK_TARGET : StorageServiceInstance.AccessResourceType.FILE_SHARE);
            if (block) {
                StorageBlockTargetVO target = mock(StorageBlockTargetVO.class);
                when(target.getInstanceId()).thenReturn(7L);
                when(targets.findById(13L)).thenReturn(target);
            } else {
                StorageFileShareVO share = mock(StorageFileShareVO.class);
                when(share.getInstanceId()).thenReturn(7L);
                when(share.getProtocol()).thenReturn(type.contains("Nfs") ? StorageServiceInstance.Protocol.NFS : StorageServiceInstance.Protocol.SMB);
                when(shares.findById(13L)).thenReturn(share);
            }
            BaseStorageServiceAsyncCmd command = aclCommand(type, 21L);
            Assert.assertEquals(type, "StorageServiceInstance", command.getSyncObjType());
            Assert.assertEquals(type, Long.valueOf(7L), command.getSyncObjId());
        }
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.times(commands.length)).checkAccess(callingAccount,
                org.apache.cloudstack.acl.SecurityChecker.AccessType.UseEntry, false, scopedInstance);
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.times(commands.length)).checkAccess(callingAccount,
                org.apache.cloudstack.acl.SecurityChecker.AccessType.OperateEntry, false, scopedInstance);
    }

    @Test
    public void missingAclIsRejectedBeforeResourceOrOwnershipLookup() throws Exception {
        BaseStorageServiceAsyncCmd command = aclCommand("UpdateStorageIscsiAclCmd", 99L);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, command::getSyncObjId);
        org.mockito.Mockito.verifyNoInteractions(targets, shares, accounts);
    }

    @Test
    public void missingBlockOrFileResourceIsRejectedWithoutAdoptingSameNumericId() throws Exception {
        acl(StorageServiceInstance.AccessResourceType.BLOCK_TARGET);
        StorageBlockTargetVO unrelated = mock(StorageBlockTargetVO.class);
        when(unrelated.getInstanceId()).thenReturn(91L);
        when(targets.findById(21L)).thenReturn(unrelated);
        BaseStorageServiceAsyncCmd block = aclCommand("UpdateStorageNvmeOfHostAclCmd", 21L);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, block::getSyncObjId);
        org.mockito.Mockito.verify(targets, org.mockito.Mockito.never()).findById(21L);
        acl(StorageServiceInstance.AccessResourceType.FILE_SHARE);
        BaseStorageServiceAsyncCmd file = aclCommand("DeleteStorageNfsAclCmd", 21L);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, file::getSyncObjId);
        org.mockito.Mockito.verifyNoInteractions(accounts);
    }

    @Test
    public void unknownAclResourceTypeIsRejectedWithoutFallback() throws Exception {
        acl(null);
        BaseStorageServiceAsyncCmd command = aclCommand("DeleteStorageNvmeOfHostAclCmd", 21L);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, command::getSyncObjId);
        org.mockito.Mockito.verifyNoInteractions(targets, shares, accounts);
    }

    @Test
    public void foreignAccountCannotAcquireTheResolvedBlockWriterScope() throws Exception {
        acl(StorageServiceInstance.AccessResourceType.BLOCK_TARGET);
        StorageBlockTargetVO target = mock(StorageBlockTargetVO.class);
        when(target.getInstanceId()).thenReturn(7L);
        when(targets.findById(13L)).thenReturn(target);
        org.mockito.Mockito.doThrow(new com.cloud.exception.PermissionDeniedException("public foreign-account fixture"))
                .when(accounts).checkAccess(callingAccount,
                        org.apache.cloudstack.acl.SecurityChecker.AccessType.OperateEntry, false, scopedInstance);
        BaseStorageServiceAsyncCmd command = aclCommand("UpdateStorageIscsiAclCmd", 21L);
        Assert.assertThrows(com.cloud.exception.PermissionDeniedException.class, command::getSyncObjId);
        org.mockito.Mockito.verify(accounts).checkAccess(callingAccount,
                org.apache.cloudstack.acl.SecurityChecker.AccessType.UseEntry, false, scopedInstance);
        org.mockito.Mockito.verify(accounts).checkAccess(callingAccount,
                org.apache.cloudstack.acl.SecurityChecker.AccessType.OperateEntry, false, scopedInstance);
    }
}
