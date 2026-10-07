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

package com.cloud.kubernetes.cluster;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;

public class KubernetesStoragePreflightTest {
    private final KubernetesClusterManagerImpl manager = new KubernetesClusterManagerImpl();
    private ServiceOfferingVO node;
    private ServiceOfferingVO routerOffering;
    private Network network;
    private VolumeVO root;
    private DiskOfferingVO disk;

    @Before
    public void setup() {
        manager.serviceOfferingDao = Mockito.mock(ServiceOfferingDao.class);
        manager.kubernetesDiskOfferingDao = Mockito.mock(DiskOfferingDao.class);
        manager.kubernetesRouterDao = Mockito.mock(DomainRouterDao.class);
        manager.kubernetesVolumeDao = Mockito.mock(VolumeDao.class);
        manager.kubernetesStoragePoolTagsDao = Mockito.mock(StoragePoolTagsDao.class);
        manager.routedIpv4Manager = Mockito.mock(org.apache.cloudstack.network.RoutedIpv4Manager.class);
        node = Mockito.mock(ServiceOfferingVO.class);
        routerOffering = Mockito.mock(ServiceOfferingVO.class);
        disk = Mockito.mock(DiskOfferingVO.class);
        network = Mockito.mock(Network.class);
        root = Mockito.mock(VolumeVO.class);
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        Mockito.when(node.getDiskOfferingStrictness()).thenReturn(true);
        Mockito.when(node.getDiskOfferingId()).thenReturn(9L);
        Mockito.when(routerOffering.getDiskOfferingStrictness()).thenReturn(true);
        Mockito.when(routerOffering.getDiskOfferingId()).thenReturn(9L);
        Mockito.when(disk.getTags()).thenReturn("glue-gfs");
        Mockito.when(manager.kubernetesDiskOfferingDao.findById(9L)).thenReturn(disk);
        Mockito.when(manager.serviceOfferingDao.findById(1L)).thenReturn(node);
        Mockito.when(manager.serviceOfferingDao.findById(2L)).thenReturn(routerOffering);
        Mockito.when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        Mockito.when(network.getId()).thenReturn(5L);
        Mockito.when(network.getVpcId()).thenReturn(null);
        Mockito.when(router.getId()).thenReturn(3L);
        Mockito.when(router.getServiceOfferingId()).thenReturn(2L);
        Mockito.when(manager.kubernetesRouterDao.findByNetwork(5L)).thenReturn(List.of(router));
        Mockito.when(manager.kubernetesVolumeDao.findByInstanceAndType(3L, Volume.Type.ROOT)).thenReturn(List.of(root));
        Mockito.when(root.getPoolId()).thenReturn(10L);
        Mockito.when(root.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(manager.kubernetesStoragePoolTagsDao.getStoragePoolTags(10L)).thenReturn(List.of("glue-gfs"));
    }

    @Test public void matchingRouterOfferingAndActualRootPoolPass() {
        manager.validateKubernetesStoragePreflight(network, Map.of(), 1L, 0L);
    }
    @Test(expected = InvalidParameterValueException.class)
    public void defaultOrNonStrictRouterCannotBypassNodeConstraint() {
        Mockito.when(routerOffering.getDiskOfferingStrictness()).thenReturn(false);
        manager.validateKubernetesStoragePreflight(network, Map.of(), 1L, 0L);
    }
    @Test(expected = InvalidParameterValueException.class)
    public void existingWrongRootPoolFailsBeforeNewClusterResources() {
        Mockito.when(manager.kubernetesStoragePoolTagsDao.getStoragePoolTags(10L)).thenReturn(List.of("clvm"));
        manager.validateKubernetesStoragePreflight(network, Map.of(), 1L, 0L);
    }
    @Test public void unconstrainedAndComputeOnlyDisksDoNotInventRootRestrictions() {
        Mockito.when(node.getDiskOfferingStrictness()).thenReturn(false);
        manager.validateKubernetesStoragePreflight(network, Map.of(), 1L, 0L);
        Mockito.verifyNoInteractions(manager.kubernetesRouterDao);
        Mockito.when(node.getDiskOfferingStrictness()).thenReturn(true);
        Mockito.when(disk.isComputeOnly()).thenReturn(true);
        Assert.assertTrue(manager.strictRootStorageTags(node).isEmpty());
    }
    @Test public void sharedNetworkHasNoCloudRouterRequirement() {
        Mockito.when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        manager.validateKubernetesStoragePreflight(network, Map.of(), 1L, 0L);
        Mockito.verifyNoInteractions(manager.kubernetesRouterDao);
    }
    @Test(expected = InvalidParameterValueException.class)
    public void unknownRouterCannotClaimMatchingStorage() {
        manager.requireRouterStorageTags(null, Set.of("glue-gfs"));
    }
}
