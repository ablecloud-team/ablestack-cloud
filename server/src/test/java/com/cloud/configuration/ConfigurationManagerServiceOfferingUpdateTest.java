// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.configuration;

import java.util.ArrayList;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.dao.VMInstanceDao;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConfigurationManagerServiceOfferingUpdateTest {
    private final ConfigurationManagerImpl manager = new ConfigurationManagerImpl();
    private final UpdateServiceOfferingCmd cmd = mock(UpdateServiceOfferingCmd.class);
    private final ServiceOfferingDetailsDao details = mock(ServiceOfferingDetailsDao.class);
    private final ServiceOfferingDao offerings = mock(ServiceOfferingDao.class);
    private final ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
    private final DiskOfferingVO disk = mock(DiskOfferingVO.class);
    private SearchCriteria<ServiceOfferingDetailsVO> criteria;
    private SearchBuilder<ServiceOfferingDetailsVO> builder;

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        EntityManager entities = mock(EntityManager.class);
        AccountDao accounts = mock(AccountDao.class);
        UserDao users = mock(UserDao.class);
        DiskOfferingDao disks = mock(DiskOfferingDao.class);
        DomainHelper domains = mock(DomainHelper.class);
        AccountVO account = mock(AccountVO.class);
        UserVO user = mock(UserVO.class);
        when(account.getId()).thenReturn(1L);
        when(account.getDomainId()).thenReturn(1L);
        when(account.getType()).thenReturn(Account.Type.ADMIN);
        when(user.getId()).thenReturn(1L);
        when(user.getAccountId()).thenReturn(1L);
        CallContext.register(user, account);
        when(accounts.findById(1L)).thenReturn(account);
        when(users.findById(1L)).thenReturn(user);
        when(cmd.getId()).thenReturn(7L);
        when(cmd.getServiceOfferingName()).thenReturn("Renamed");
        when(cmd.getDomainIds()).thenReturn(new ArrayList<>());
        when(cmd.getZoneIds()).thenReturn(new ArrayList<>());
        when(cmd.getFullUrlParams()).thenReturn(Map.of("name", "Renamed"));
        when(entities.findById(ServiceOffering.class, 7L)).thenReturn(offering);
        when(offering.getId()).thenReturn(7L);
        when(offering.getDiskOfferingId()).thenReturn(9L);
        when(offering.getUuid()).thenReturn("offering");
        when(offerings.findById(7L)).thenReturn(offering);
        when(offerings.createForUpdate(7L)).thenReturn(offering);
        when(offerings.update(7L, offering)).thenReturn(true);
        when(disks.findById(9L)).thenReturn(disk);
        when(disk.getId()).thenReturn(9L);
        when(disk.isComputeOnly()).thenReturn(true);
        when(details.findDomainIds(7L)).thenReturn(new ArrayList<>());
        when(details.findZoneIds(7L)).thenReturn(new ArrayList<>());
        when(domains.filterChildSubDomains(anyList())).thenAnswer(invocation -> new ArrayList<>());
        builder = mock(SearchBuilder.class);
        criteria = mock(SearchCriteria.class);
        when(builder.entity()).thenReturn(new ServiceOfferingDetailsVO(7L, "detail", "value", false));
        when(builder.create()).thenReturn(criteria);
        when(details.createSearchBuilder()).thenReturn(builder);
        ReflectionTestUtils.setField(manager, "_entityMgr", entities);
        ReflectionTestUtils.setField(manager, "_serviceOfferingDao", offerings);
        ReflectionTestUtils.setField(manager, "_serviceOfferingDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_diskOfferingDao", disks);
        ReflectionTestUtils.setField(manager, "_accountDao", accounts);
        ReflectionTestUtils.setField(manager, "_userDao", users);
        ReflectionTestUtils.setField(manager, "_vmInstanceDao", mock(VMInstanceDao.class));
        ReflectionTestUtils.setField(manager, "domainHelper", domains);
    }

    @After
    public void cleanup() {
        CallContext.unregister();
    }

    @Test
    public void nameOnlyPreservesEnabledCleanupAndExternalDetails() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of(ServiceOffering.PURGE_DB_ENTITIES_KEY, "true", "External:note", "keep"));
        assertSame(offering, manager.updateServiceOffering(cmd));
        verify(details, never()).createSearchBuilder();
        verify(cmd, never()).isPurgeResources();
    }

    @Test
    public void deactivationPreservesCleanupAndChangesPrivateDiskState() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of(ServiceOffering.PURGE_DB_ENTITIES_KEY, "true"));
        when(cmd.getState()).thenReturn(ServiceOffering.State.Inactive);
        manager.updateServiceOffering(cmd);
        verify(disk).setState(com.cloud.offering.DiskOffering.State.Inactive);
        verify(details, never()).createSearchBuilder();
    }

    @Test
    public void explicitFalseRemovesCleanupOverride() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of(ServiceOffering.PURGE_DB_ENTITIES_KEY, "true"));
        when(cmd.getFullUrlParams()).thenReturn(Map.of("purgeresources", "false"));
        when(cmd.isPurgeResources()).thenReturn(false);
        manager.updateServiceOffering(cmd);
        verify(details).remove(criteria);
        verify(details, never()).persist(any(ServiceOfferingDetailsVO.class));
    }

    @Test
    public void explicitTruePersistsCleanupOverride() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of());
        when(cmd.getFullUrlParams()).thenReturn(Map.of("purgeresources", "true"));
        when(cmd.isPurgeResources()).thenReturn(true);
        manager.updateServiceOffering(cmd);
        verify(details).persist(argThat(row -> ServiceOffering.PURGE_DB_ENTITIES_KEY.equals(row.getName()) && "true".equals(row.getValue())));
    }

    @Test
    public void omissionWithoutRequestMapPreservesEitherStoredValue() {
        when(cmd.getFullUrlParams()).thenReturn(null);
        assertTrue(manager.resolvePurgeResources(cmd, true));
        assertFalse(manager.resolvePurgeResources(cmd, false));
        verify(cmd, never()).isPurgeResources();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void externalReplacementIsRestrictedToTheSelectedOffering() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of("External:note", "old"));
        when(cmd.getExternalDetails()).thenReturn(Map.of("External:note", "new"));
        SearchCriteria<ServiceOfferingDetailsVO> externalCriteria = mock(SearchCriteria.class);
        when(builder.create()).thenReturn(criteria, externalCriteria);
        manager.updateServiceOffering(cmd);
        verify(externalCriteria).setParameters("offeringId", "7");
        verify(externalCriteria).setParameters("detailNameLike", "External:%");
        verify(details).remove(externalCriteria);
        verify(details).persist(argThat(row -> row.getResourceId() == 7L && "External:note".equals(row.getName()) && "new".equals(row.getValue())));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void externalCleanupIsRestrictedToTheSelectedOffering() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of("External:note", "old"));
        when(cmd.isCleanupExternalDetails()).thenReturn(true);
        SearchCriteria<ServiceOfferingDetailsVO> externalCriteria = mock(SearchCriteria.class);
        when(builder.create()).thenReturn(criteria, externalCriteria);
        manager.updateServiceOffering(cmd);
        verify(externalCriteria).setParameters("offeringId", "7");
        verify(externalCriteria).setParameters("detailNameLike", "External:%");
        verify(details).remove(externalCriteria);
        verify(details, never()).persist(any(ServiceOfferingDetailsVO.class));
    }
}
