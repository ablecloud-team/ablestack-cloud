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
package com.cloud.server;

import java.util.List;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.api.ApiDBUtils;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.ApiConstants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

public class ManagementServerMigrationScopeTest {
    @InjectMocks private ManagementServerImpl manager = new ManagementServerImpl();
    @Mock private AccountManager accountMgr;
    @Mock private HostDao hostDao;
    @Mock private HostDetailsDao detailsDao;
    @Mock private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock private ServiceOfferingDao offeringDao;
    @Mock private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;
    @Mock private VolumeDao volumeDao;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock private AffinityGroupVMMapDao affinityDao;
    @Mock private HighAvailabilityManager haMgr;
    @Mock private DpdkHelper dpdkHelper;
    @Mock private DeploymentPlanningManager dpMgr;
    @Mock private DataCenterDao dcDao;
    @Mock private HostAllocator allocator;
    private VirtualMachine vm;
    private HostVO source;
    private HostVO destination;
    private SearchCriteria<HostVO> criteria;
    private AutoCloseable mocks;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        Account caller = mock(Account.class);
        when(caller.getId()).thenReturn(1L);
        CallContext.register(mock(User.class), caller);
        when(accountMgr.isRootAdmin(1L)).thenReturn(true);
        vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getHostId()).thenReturn(10L);
        when(vm.getServiceOfferingId()).thenReturn(20L);
        when(vm.getDataCenterId()).thenReturn(3L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        source = mock(HostVO.class);
        destination = mock(HostVO.class);
        when(source.getId()).thenReturn(10L);
        when(source.getDataCenterId()).thenReturn(3L);
        when(source.getPodId()).thenReturn(7L);
        when(source.getClusterId()).thenReturn(8L);
        when(source.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(source.getType()).thenReturn(Host.Type.Routing);
        when(destination.getId()).thenReturn(30L);
        when(destination.getClusterId()).thenReturn(9L);
        when(hostDao.findById(10L)).thenReturn(source);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getVgpuProfileId()).thenReturn(null);
        when(offeringDao.findById(1L,20L)).thenReturn(offering);
        when(hypervisorCapabilitiesDao.isStorageMotionSupported(HypervisorType.KVM, "")).thenReturn(true);
        SearchBuilder<HostVO> builder = mock(SearchBuilder.class);
        criteria = mock(SearchCriteria.class);
        when(hostDao.createSearchBuilder()).thenReturn(builder);
        when(builder.entity()).thenReturn(mock(HostVO.class));
        when(builder.create()).thenReturn(criteria);
        when(hostDao.searchAndCount(eq(criteria),any())).thenReturn(new Pair<>(new ArrayList<>(List.of(destination)),1));
        ReflectionTestUtils.setField(manager,"hostAllocators",List.of(allocator));
        when(allocator.allocateTo(any(),any(),any(),any(),any(),anyInt(),anyBoolean())).thenReturn(new ArrayList<>(List.of(destination)));
    }

    @After
    public void tearDown() throws Exception { CallContext.unregister(); mocks.close(); }

    @Test
    public void userStorageMotionKeepsOtherClustersAndPodsEligible() {
        try (MockedStatic<ApiDBUtils> api = mockStatic(ApiDBUtils.class)) {
            assertEquals(List.of(destination),manager.listHostsForMigrationOfVM(vm,0L,50L,null,List.of()).second());
        }
        ArgumentCaptor<DataCenterDeployment> plan = ArgumentCaptor.forClass(DataCenterDeployment.class);
        verify(allocator).allocateTo(any(),plan.capture(),any(),any(),eq(List.of(destination)),anyInt(),anyBoolean());
        assertNull(plan.getValue().getClusterId());
        assertNull(plan.getValue().getPodId());
        verify(criteria,never()).setParameters(eq("clusterId"),any());
        verify(criteria,never()).setParameters(eq("podId"),any());
        verify(hypervisorCapabilitiesDao).isStorageMotionSupported(HypervisorType.KVM, "");
    }

    @Test
    public void systemStorageMotionKeepsPodBoundary() {
        when(vm.getType()).thenReturn(VirtualMachine.Type.ConsoleProxy);
        try (MockedStatic<ApiDBUtils> api = mockStatic(ApiDBUtils.class)) {
            manager.listHostsForMigrationOfVM(vm,0L,50L,null,List.of());
        }
        ArgumentCaptor<DataCenterDeployment> plan = ArgumentCaptor.forClass(DataCenterDeployment.class);
        verify(allocator).allocateTo(any(),plan.capture(),any(),any(),any(),anyInt(),anyBoolean());
        assertNull(plan.getValue().getClusterId());
        assertEquals(Long.valueOf(7),plan.getValue().getPodId());
        verify(criteria).setParameters("podId",7L);
    }

    @Test
    public void noStorageMotionKeepsSourceClusterBoundary() {
        when(hypervisorCapabilitiesDao.isStorageMotionSupported(HypervisorType.KVM, "")).thenReturn(false);
        try (MockedStatic<ApiDBUtils> api = mockStatic(ApiDBUtils.class)) {
            manager.listHostsForMigrationOfVM(vm,0L,50L,null,List.of());
        }
        ArgumentCaptor<DataCenterDeployment> plan = ArgumentCaptor.forClass(DataCenterDeployment.class);
        verify(allocator).allocateTo(any(),plan.capture(),any(),any(),any(),anyInt(),anyBoolean());
        assertEquals(Long.valueOf(8),plan.getValue().getClusterId());
        verify(criteria).setParameters("clusterId",8L);
    }

    @Test(expected=com.cloud.exception.PermissionDeniedException.class)
    public void nonAdminCannotEnumerateMigrationTargets() {
        when(accountMgr.isRootAdmin(1L)).thenReturn(false);
        try { manager.listHostsForMigrationOfVM(vm,0L,50L,null,List.of()); }
        finally { verifyNoInteractions(allocator); verify(hostDao,never()).createSearchBuilder(); }
    }

    @Test
    public void uefiWithoutCompatibleHostStopsBeforeAllocation() {
        when(vmInstanceDetailsDao.findDetail(1L,ApiConstants.BootType.UEFI.toString()))
                .thenReturn(new VMInstanceDetailVO(1L,"UEFI","LEGACY",false));
        assertTrue(manager.listHostsForMigrationOfVM(vm,0L,50L,null,List.of()).second().isEmpty());
        verifyNoInteractions(allocator);
    }
}
