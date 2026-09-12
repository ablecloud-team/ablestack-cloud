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
//

package com.cloud.template;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import com.cloud.storage.DataStoreRole;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.command.TemplateOrVolumePostUploadCommand;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.org.Grouping;
import com.cloud.server.StatsCollector;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.TemplateProfile;
import com.cloud.storage.VMTemplateVO;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;

public class EuropaPostUploadAllocationTest {
    private HypervisorTemplateAdapter adapter;
    private TemplateProfile profile;
    private VMTemplateVO template;
    private DataStore firstStore;
    private DataStore secondStore;
    private DataCenterVO firstZone;
    private DataStoreManager stores;
    private EndPointSelector endpoints;
    private StatsCollector stats;
    private ImageStoreVO firstStoreRow;

    @Before
    public void setup() {
        adapter = spy(new HypervisorTemplateAdapter());
        profile = mock(TemplateProfile.class);
        when(profile.getZoneIdList()).thenReturn(null);
        template = mock(VMTemplateVO.class);
        stores = mock(DataStoreManager.class);
        endpoints = mock(EndPointSelector.class);
        stats = mock(StatsCollector.class);
        adapter.storeMgr = stores;
        adapter._epSelector = endpoints;
        adapter._statsCollector = stats;
        adapter._dcDao = mock(DataCenterDao.class);
        ((TemplateAdapterBase) adapter)._dcDao = adapter._dcDao;
        adapter._imgStoreDao = mock(ImageStoreDao.class);
        adapter.imageFactory = mock(TemplateDataFactory.class);
        adapter._configDao = mock(ConfigurationDao.class);
        adapter._accountDao = mock(AccountDao.class);
        adapter._domainDao = mock(DomainDao.class);
        adapter._resourceLimitMgr = mock(ResourceLimitService.class);
        AccountVO account = mock(AccountVO.class);
        when(adapter._accountDao.findById(anyLong())).thenReturn(account);
        when(adapter._domainDao.findById(anyLong())).thenReturn(mock(DomainVO.class));
        when(template.getId()).thenReturn(123L);
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        firstZone = zone(1L);
        DataCenterVO secondZone = zone(2L);
        when(adapter._dcDao.listAll()).thenReturn(List.of(firstZone, secondZone));
        firstStore = store(11L, 1L);
        secondStore = store(22L, 2L);
        firstStoreRow = adapter._imgStoreDao.findById(11L);
        when(stores.getImageStoresByZoneIds(1L)).thenReturn(List.of(firstStore));
        when(stores.getImageStoresByZoneIds(2L)).thenReturn(List.of(secondStore));
        doReturn(null).when(adapter).verifyHeuristicRulesForZone(any(), anyLong());
        doReturn(1).when(adapter).getSecStorageCopyLimit(any(), anyLong());
    }

    private DataCenterVO zone(long id) {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(id);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        when(adapter._dcDao.findById(id)).thenReturn(zone);
        return zone;
    }

    private DataStore store(long id, long zoneId) {
        DataStore store = mock(DataStore.class);
        when(store.getId()).thenReturn(id);
        when(store.getScope()).thenReturn(new ZoneScope(zoneId));
        when(store.getRole()).thenReturn(DataStoreRole.Image);
        when(store.getUri()).thenReturn("nfs://fixture/store/" + id);
        ImageStoreVO row = mock(ImageStoreVO.class);
        when(adapter._imgStoreDao.findById(id)).thenReturn(row);
        when(stats.imageStoreHasEnoughCapacity(store)).thenReturn(true);
        EndPoint endpoint = mock(EndPoint.class);
        when(endpoint.getPublicAddr()).thenReturn("192.0.2." + zoneId);
        when(endpoints.select(store)).thenReturn(endpoint);
        TemplateInfo info = mock(TemplateInfo.class);
        when(info.getDataStore()).thenReturn(store);
        when(info.getType()).thenReturn(com.cloud.agent.api.to.DataObjectType.TEMPLATE);
        when(adapter.imageFactory.getTemplate(123L, store)).thenReturn(info);
        when(store.create(info)).thenReturn(info);
        return store;
    }

    private void assertSecondZoneSelected() {
        List<TemplateOrVolumePostUploadCommand> result = adapter.allocatePostUpload(profile, template);
        assertEquals(1, result.size());
        verify(firstStore, never()).create(any());
        verify(secondStore).create(any());
        verify(template, never()).setCrossZones(false);
    }

    @Test
    public void emptyFirstZoneFallsBack() {
        when(stores.getImageStoresByZoneIds(1L)).thenReturn(List.of());
        assertSecondZoneSelected();
    }

    @Test
    public void disabledFirstZoneFallsBack() {
        when(firstZone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        assertSecondZoneSelected();
    }

    @Test
    public void readOnlyFirstStoreFallsBack() {
        when(firstStoreRow.isReadonly()).thenReturn(true);
        assertSecondZoneSelected();
    }

    @Test
    public void fullFirstStoreFallsBack() {
        when(stats.imageStoreHasEnoughCapacity(firstStore)).thenReturn(false);
        assertSecondZoneSelected();
    }

    @Test
    public void missingSsvmLeavesNoReferenceAndFallsBack() {
        when(endpoints.select(firstStore)).thenReturn(null);
        assertSecondZoneSelected();
    }

    @Test
    public void missingPublicEndpointFallsBack() {
        EndPoint endpoint = mock(EndPoint.class);
        when(endpoints.select(firstStore)).thenReturn(endpoint);
        assertSecondZoneSelected();
    }

    @Test
    public void firstSuccessfulZoneIsTheOnlyUploadPivot() {
        assertEquals(1, adapter.allocatePostUpload(profile, template).size());
        verify(firstStore).create(any());
        verify(secondStore, never()).create(any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void explicitUnavailableZoneDoesNotEscapeRequestedScope() {
        when(profile.getZoneIdList()).thenReturn(List.of(1L));
        when(endpoints.select(firstStore)).thenReturn(null);
        try {
            adapter.allocatePostUpload(profile, template);
        } finally {
            verify(secondStore, never()).create(any());
            verify(firstStore, never()).create(any());
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void allUnavailableFailsWithoutCreatingReferences() {
        when(endpoints.select(firstStore)).thenReturn(null);
        when(endpoints.select(secondStore)).thenReturn(null);
        try {
            adapter.allocatePostUpload(profile, template);
        } finally {
            verify(firstStore, never()).create(any());
            verify(secondStore, never()).create(any());
        }
    }
}
