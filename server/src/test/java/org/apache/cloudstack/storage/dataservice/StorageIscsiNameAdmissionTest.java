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

import java.util.*;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import com.cloud.exception.InvalidParameterValueException;

public class StorageIscsiNameAdmissionTest {
    static final String IQN="iqn.2026-10.local.ablestack:custom-target",CLIENT="iqn.2026-10.local.ablestack:client";
    static class Manager extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance=new StorageServiceInstanceVO();
        final Map<Long,StorageBlockTargetVO> targets=new HashMap<>();
        final Map<Long,StorageAccessRuleVO> acls=new HashMap<>();
        final org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao targetDao=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao aclDao=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);
        int writers;
        Manager() {
            ReflectionTestUtils.setField(instance,"id",7L);
            ReflectionTestUtils.setField(this,"storageBlockTargetDao",targetDao);ReflectionTestUtils.setField(this,"storageAccessRuleDao",aclDao);
            Mockito.when(targetDao.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenAnswer(call->new ArrayList<>(targets.values()));
            Mockito.when(aclDao.listByResource(Mockito.any(),Mockito.anyLong())).thenAnswer(call->acls.values().stream().filter(row->row.getResourceId()==call.<Long>getArgument(1)).collect(java.util.stream.Collectors.toList()));
        }
        @Override protected StorageServiceInstanceVO requireInstance(Long id){if(id!=7L)throw new InvalidParameterValueException("not owned");return instance;}
        @Override protected StorageBlockTargetVO requireBlockTarget(Long id,StorageServiceInstance.Protocol protocol){return targets.get(id);}
        @Override protected StorageAccessRuleVO requireBlockAcl(Long id,StorageServiceInstance.Protocol protocol){return acls.get(id);}
        @Override protected <T>T executeDesiredChange(BaseCmd cmd,Class<T> response,java.util.function.Supplier<T> change){writers++;return null;}
        @Override protected void validateIscsiEndpointPolicy(String mode,String ports) { }
        @Override protected void validateIscsiListenerPortsExist(StorageServiceInstanceVO instance,String ports) { }
        StorageBlockTargetVO target(long id,long owner,String name,String lun) {
            StorageBlockTargetVO row=new StorageBlockTargetVO(owner,StorageServiceInstance.Protocol.ISCSI,name,lun,1L,StorageServiceInstance.ResourceState.Ready,"{}");
            ReflectionTestUtils.setField(row,"id",id);targets.put(id,row);return row;
        }
        StorageAccessRuleVO acl(long id,long target,String principal) {
            StorageAccessRuleVO row=new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.BLOCK_TARGET,target,
                    StorageServiceInstance.PrincipalType.ISCSI_INITIATOR_IQN,principal,StorageServiceInstance.Permission.READ_WRITE,StorageServiceInstance.ResourceState.Ready,"{}");
            ReflectionTestUtils.setField(row,"id",id);acls.put(id,row);return row;
        }
        void noWrite() {
            Assert.assertEquals(0,writers);Mockito.verify(targetDao,Mockito.never()).persist(Mockito.any());Mockito.verify(targetDao,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
            Mockito.verify(aclDao,Mockito.never()).persist(Mockito.any());Mockito.verify(aclDao,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        }
    }
    CreateStorageIscsiTargetCmd create(String name,String lun) {
        CreateStorageIscsiTargetCmd c=Mockito.mock(CreateStorageIscsiTargetCmd.class);Mockito.when(c.getInstanceId()).thenReturn(7L);
        Mockito.when(c.getTargetName()).thenReturn(name);Mockito.when(c.getLun()).thenReturn(lun);return c;
    }
    CreateStorageIscsiAclCmd grant(long id,String principal) {
        CreateStorageIscsiAclCmd c=Mockito.mock(CreateStorageIscsiAclCmd.class);Mockito.when(c.getTargetId()).thenReturn(id);Mockito.when(c.getInitiatorIqn()).thenReturn(principal);return c;
    }
    @Test public void publicCustomNamespaceAndTargetOnly65535AreSupported() {
        Manager m=new Manager();m.createStorageIscsiTarget(create(IQN,"65535"));Assert.assertEquals(1,m.writers);
        Assert.assertEquals("1",StorageIscsiNamePolicy.targetLun("0001"));Assert.assertEquals("0",StorageIscsiNamePolicy.targetLun(null));
    }
    @Test public void invalidTargetNameOrLunRejectBeforeAnyWriterOrBackingPreparation() {
        for(String name:new String[]{"bad"," iqn.2026-10.example:x","iqn.2026-1.example:x","iqn.2026-10.example:x/y","iqn.2026-10.example:x\n"}) {
            Manager m=new Manager();Assert.assertThrows(InvalidParameterValueException.class,()->m.createStorageIscsiTarget(create(name,"0")));m.noWrite();
        }
        for(String lun:new String[]{"-1","65536","2.5",""," 0","999999999999999999999"}) {
            Manager m=new Manager();Assert.assertThrows(InvalidParameterValueException.class,()->m.createStorageIscsiTarget(create(IQN,lun)));m.noWrite();
        }
        String prefix="iqn.2026-10.example:";Assert.assertTrue(StorageIscsiNamePolicy.valid(prefix+"x".repeat(223-prefix.length())));
        Assert.assertFalse(StorageIscsiNamePolicy.valid(prefix+"x".repeat(224-prefix.length())));
    }
    @Test public void existingBadTargetAndForeignTargetRejectAclBeforeWriterCreation() {
        Manager m=new Manager();m.target(1,7,"bad","0");
        Assert.assertThrows(InvalidParameterValueException.class,()->m.createStorageIscsiAcl(grant(1,CLIENT)));m.noWrite();
        Manager foreign=new Manager();foreign.target(1,8,IQN,"0");
        Assert.assertThrows(InvalidParameterValueException.class,()->foreign.createStorageIscsiAcl(grant(1,CLIENT)));foreign.noWrite();
    }
    @Test public void invalidInitiatorAndStoredBadPrincipalRejectBeforeAnyWriter() {
        Manager m=new Manager();m.target(1,7,IQN,"0");Assert.assertThrows(InvalidParameterValueException.class,()->m.createStorageIscsiAcl(grant(1,"bad")));m.noWrite();
        m.acl(2,1,"bad");UpdateStorageIscsiAclCmd edit=Mockito.mock(UpdateStorageIscsiAclCmd.class);Mockito.when(edit.getId()).thenReturn(2L);
        Assert.assertThrows(InvalidParameterValueException.class,()->m.updateStorageIscsiAcl(edit));m.noWrite();
    }
    @Test public void firstAclChecksEveryActiveLunInTheWholeIqnGroup() {
        Manager m=new Manager();m.target(1,7,IQN,"0");m.target(2,7,IQN,"65535");
        InvalidParameterValueException e=Assert.assertThrows(InvalidParameterValueException.class,()->m.createStorageIscsiAcl(grant(1,CLIENT)));
        Assert.assertEquals("ISCSI_MAPPED_LUN_UNSUPPORTED",e.getMessage());m.noWrite();
        m.targets.get(2L).setState(StorageServiceInstance.ResourceState.Disabled);m.createStorageIscsiAcl(grant(1,CLIENT));Assert.assertEquals(1,m.writers);
    }
    @Test public void activeGroupAclLimitsNewAndUpdatedTargetLunWithoutMutatingOriginal() {
        Manager m=new Manager();StorageBlockTargetVO target=m.target(1,7,IQN,"0");m.acl(2,1,CLIENT);
        Assert.assertThrows(InvalidParameterValueException.class,()->m.createStorageIscsiTarget(create(IQN,"256")));m.noWrite();
        UpdateStorageIscsiTargetCmd edit=Mockito.mock(UpdateStorageIscsiTargetCmd.class);Mockito.when(edit.getId()).thenReturn(1L);Mockito.when(edit.getLun()).thenReturn("65535");
        Assert.assertThrows(InvalidParameterValueException.class,()->m.updateStorageIscsiTarget(edit));Assert.assertEquals("0",target.getLunOrNamespace());m.noWrite();
    }
}
