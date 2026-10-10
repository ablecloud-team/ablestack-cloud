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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbNetworkAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbNetworkAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbNetworkAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbNetworkAclsCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import com.cloud.exception.InvalidParameterValueException;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SmbNetworkAclCrudTest {
    private final Map<Long,StorageAccessRuleVO> rows=new LinkedHashMap<>();
    private StorageServiceInstanceVO instance;
    private StorageFileShareVO share;
    private Harness manager;
    private class Harness extends StorageServiceManagerImpl {
        int applies;
        @Override protected <T> T executeDesiredChange(BaseCmd cmd,Class<T> type,java.util.function.Supplier<T> action) { return action.get(); }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
        @Override protected StorageFileShareVO requireSmbShare(Long id) { return share; }
        @Override protected boolean smbNetworkIpv6Capability(StorageServiceInstanceVO value) { return false; }
        @Override protected boolean canReadStorageInstance(StorageServiceInstanceVO value) { return true; }
        @Override protected void applySmbDesiredState(StorageServiceInstanceVO value) { applies++; }
    }
    @Before public void setUp() {
        rows.clear();manager=new Harness();instance=mock(StorageServiceInstanceVO.class);share=mock(StorageFileShareVO.class);
        when(instance.getId()).thenReturn(3L);when(share.getId()).thenReturn(7L);when(share.getInstanceId()).thenReturn(3L);when(share.getUuid()).thenReturn("share-uuid");when(share.getProtocol()).thenReturn(StorageServiceInstance.Protocol.SMB);
        StorageAccessRuleDao rules=mock(StorageAccessRuleDao.class);StorageFileShareDao shares=mock(StorageFileShareDao.class);StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);
        when(rules.listByResource(any(),anyLong())).thenAnswer(call->new ArrayList<>(rows.values()));when(rules.listAll()).thenAnswer(call->new ArrayList<>(rows.values()));
        when(rules.findById(anyLong())).thenAnswer(call->rows.get(call.getArgument(0)));
        when(rules.persist(any())).thenAnswer(call->{StorageAccessRuleVO rule=call.getArgument(0);long id=rows.size()+1;ReflectionTestUtils.setField(rule,"id",id);rows.put(id,rule);return rule;});
        when(rules.remove(anyLong())).thenAnswer(call->rows.remove(call.getArgument(0))!=null);
        when(shares.findById(7L)).thenReturn(share);when(instances.findById(3L)).thenReturn(instance);
        ReflectionTestUtils.setField(manager,"storageAccessRuleDao",rules);ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
    }
    private CreateStorageSmbNetworkAclCmd create(String sources) {
        CreateStorageSmbNetworkAclCmd cmd=new CreateStorageSmbNetworkAclCmd();ReflectionTestUtils.setField(cmd,"shareId",7L);ReflectionTestUtils.setField(cmd,"principals",sources);return cmd;
    }
    @Test public void connectPermissionCannotGrantNfsBlockOrAccountAccess() {
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.parseNfsPermission("CONNECT"));
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.parseBlockPermission("CONNECT"));
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.parseSmbPermission("CONNECT"));
    }
    @Test public void bulkCreateDeduplicatesAndUsesOnlyConnectPermission() {
        Assert.assertEquals(2,manager.createStorageSmbNetworkAcl(create("10.1.1.9/24,10.1.1.0/24,10.1.1.9")).getResponses().size());
        manager.createStorageSmbNetworkAcl(create("10.1.1.0/24,10.1.1.9"));Assert.assertEquals(2,rows.size());
        for (StorageAccessRuleVO row:rows.values()) Assert.assertEquals(StorageServiceInstance.Permission.CONNECT,row.getPermission());
    }
    @Test public void accountRulesAreExcludedFromNetworkReadsAndDeletes() {
        rows.put(5L,new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE,7L,StorageServiceInstance.PrincipalType.LOCAL_USER,"user",StorageServiceInstance.Permission.READ_WRITE,StorageServiceInstance.ResourceState.Ready,"{}"));
        Assert.assertTrue(manager.listStorageSmbNetworkAcls(new ListStorageSmbNetworkAclsCmd()).getResponses().isEmpty());
        DeleteStorageSmbNetworkAclCmd cmd=new DeleteStorageSmbNetworkAclCmd();ReflectionTestUtils.setField(cmd,"id",5L);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.deleteStorageSmbNetworkAcl(cmd));Assert.assertEquals(1,rows.size());
    }
    @Test public void duplicateAndBulkUpdatesAreRejectedAndLastRuleCanBeRemoved() {
        manager.createStorageSmbNetworkAcl(create("10.1.1.9,10.1.1.10"));
        UpdateStorageSmbNetworkAclCmd update=new UpdateStorageSmbNetworkAclCmd();ReflectionTestUtils.setField(update,"id",1L);ReflectionTestUtils.setField(update,"principal","10.1.1.10");
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.updateStorageSmbNetworkAcl(update));
        ReflectionTestUtils.setField(update,"principal","10.1.1.11,10.1.1.12");Assert.assertThrows(InvalidParameterValueException.class,()->manager.updateStorageSmbNetworkAcl(update));
        DeleteStorageSmbNetworkAclCmd delete=new DeleteStorageSmbNetworkAclCmd();ReflectionTestUtils.setField(delete,"id",1L);Assert.assertTrue(manager.deleteStorageSmbNetworkAcl(delete));Assert.assertEquals(1,rows.size());
    }
}
