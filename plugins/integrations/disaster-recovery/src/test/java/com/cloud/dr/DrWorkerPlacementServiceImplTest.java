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

package com.cloud.dr;

import java.util.Arrays;
import java.util.Date;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrResourceLeaseDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;

@RunWith(MockitoJUnitRunner.class)
public class DrWorkerPlacementServiceImplTest {
    @Mock private DrSiteDao drSiteDao;
    @Mock private DataCenterDao dataCenterDao;
    @Mock private HostDao hostDao;
    @Mock private HostDetailsDao hostDetailsDao;
    @Mock private DrResourceLeaseDao drResourceLeaseDao;
    @InjectMocks private DrWorkerPlacementServiceImpl service;

    private DrPlanVO plan;
    private DrSiteVO targetSite;
    private HostVO first;
    private HostVO second;

    @Before
    public void setUp() {
        plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setCoordinatorWorkerHostId(999L);
        plan.setSourceWorkerHostId(998L);
        plan.setTargetWorkerHostId(997L);
        targetSite = new DrSiteVO("target", "ABLESTACK", "KVM");
        targetSite.setZoneId(20L);
        DataCenterVO targetZone = zone(20L, "target-zone-uuid");
        Mockito.lenient().when(dataCenterDao.findById(20L)).thenReturn(targetZone);
        first = host(11L, 20L);
        second = host(12L, 20L);
        Mockito.when(drSiteDao.findById(2L)).thenReturn(targetSite);
        Mockito.when(hostDao.listAllHostsUpByZoneAndHypervisor(20L, HypervisorType.KVM))
                .thenReturn(Arrays.asList(first, second));
        Mockito.lenient().when(hostDao.findById(11L)).thenReturn(first);
        Mockito.lenient().when(hostDao.findById(12L)).thenReturn(second);
    }

    @Test
    public void ignoresLegacyPlanWorkerBindings() {
        Long selected = service.resolveWorkerHostId(plan, DrWorkerRole.TARGET);
        Assert.assertTrue(selected == 11L || selected == 12L);
        Assert.assertNotEquals(Long.valueOf(997L), selected);
        Mockito.verify(hostDao, Mockito.never()).findById(997L);
    }

    @Test
    public void reusesEligibleRunLeaseWithoutPersistingItToPlan() {
        DrRunVO run = Mockito.mock(DrRunVO.class);
        Mockito.when(run.getId()).thenReturn(41L);
        DrResourceLeaseVO lease = new DrResourceLeaseVO("HOST:12:TRANSITION", "TRANSITION",
                1L, 41L, new Date(System.currentTimeMillis() + 60_000L));
        Mockito.when(drResourceLeaseDao.findActiveByRunId(Mockito.eq(41L), Mockito.any(Date.class)))
                .thenReturn(lease);

        Assert.assertEquals(Long.valueOf(12L), service.resolveWorkerHostId(plan, run, DrWorkerRole.TARGET));
        Assert.assertEquals(Long.valueOf(997L), plan.getTargetWorkerHostId());
    }

    @Test
    public void vddkRoleFiltersWorkersWithoutDetectedLibrary() {
        plan = new DrPlanVO("vmware", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        Mockito.when(hostDetailsDao.findDetail(11L, Host.HOST_VDDK_LIB_DIR)).thenReturn(null);
        Mockito.when(hostDetailsDao.findDetail(11L, Host.HOST_VDDK_SUPPORT)).thenReturn(null);
        Mockito.when(hostDetailsDao.findDetail(12L, Host.HOST_VDDK_LIB_DIR))
                .thenReturn(new DetailVO(12L, Host.HOST_VDDK_LIB_DIR, "/opt/vmware-vix-disklib/lib64"));
        Mockito.when(hostDetailsDao.findDetail(12L, Host.HOST_VDDK_SUPPORT))
                .thenReturn(new DetailVO(12L, Host.HOST_VDDK_SUPPORT, "true"));

        Assert.assertEquals(Long.valueOf(12L), service.resolveWorkerHostId(plan, DrWorkerRole.VDDK_DATA_PLANE));
    }

    @Test
    public void resolvesTargetWorkerFromPlanMappingWhenSiteZoneIsMissing() {
        targetSite.setZoneId(null);
        plan.setMappingJson("{\"target\":{\"zoneId\":20}}");

        Long selected = service.resolveWorkerHostId(plan, DrWorkerRole.TARGET);

        Assert.assertTrue(selected == 11L || selected == 12L);
        Mockito.verify(hostDao).listAllHostsUpByZoneAndHypervisor(20L, HypervisorType.KVM);
    }

    @Test
    public void resolvesTargetWorkerFromPlanZoneUuid() {
        targetSite.setZoneId(null);
        plan.setMappingJson("{\"targetZoneId\":\"target-zone-uuid\"}");
        DataCenterVO targetZone = zone(20L, "target-zone-uuid");
        DataCenterVO otherZone = zone(30L, "other-zone");
        Mockito.when(dataCenterDao.listEnabledZones())
                .thenReturn(Arrays.asList(targetZone, otherZone));

        Long selected = service.resolveWorkerHostId(plan, DrWorkerRole.COORDINATOR);

        Assert.assertTrue(selected == 11L || selected == 12L);
    }

    @Test
    public void infersOnlyEnabledZoneWithoutPersistingAHostBinding() {
        targetSite.setZoneId(null);
        DataCenterVO targetZone = zone(20L, "target-zone-uuid");
        Mockito.when(dataCenterDao.listEnabledZones())
                .thenReturn(Arrays.asList(targetZone));

        Long selected = service.resolveWorkerHostId(plan, DrWorkerRole.TARGET);

        Assert.assertTrue(selected == 11L || selected == 12L);
        Assert.assertEquals(Long.valueOf(997L), plan.getTargetWorkerHostId());
    }

    @Test
    public void rejectsAmbiguousZonesWithoutAnExplicitMapping() {
        targetSite.setZoneId(null);
        DataCenterVO targetZone = zone(20L, "target-zone-uuid");
        DataCenterVO otherZone = zone(30L, "other-zone");
        Mockito.when(dataCenterDao.listEnabledZones())
                .thenReturn(Arrays.asList(targetZone, otherZone));

        Assert.assertNull(service.resolveWorkerHostId(plan, DrWorkerRole.TARGET));
        Mockito.verify(hostDao, Mockito.never())
                .listAllHostsUpByZoneAndHypervisor(Mockito.anyLong(), Mockito.any(HypervisorType.class));
    }

    private HostVO host(long id, long zoneId) {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getId()).thenReturn(id);
        Mockito.when(host.getDataCenterId()).thenReturn(zoneId);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        return host;
    }

    private DataCenterVO zone(long id, String uuid) {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(id);
        Mockito.lenient().when(zone.getUuid()).thenReturn(uuid);
        return zone;
    }
}
