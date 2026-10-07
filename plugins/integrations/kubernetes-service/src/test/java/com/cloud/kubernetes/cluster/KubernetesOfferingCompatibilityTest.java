/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.kubernetes.cluster;

import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.kubernetes.version.KubernetesSupportedVersion;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesOfferingCompatibilityTest {
    private KubernetesClusterManagerImpl manager;
    private KubernetesClusterVO cluster;
    private ServiceOfferingVO current;
    private ServiceOfferingVO target;
    private KubernetesSupportedVersion version;

    @Before public void setup() {
        manager = new KubernetesClusterManagerImpl();
        manager.serviceOfferingDao = Mockito.mock(ServiceOfferingDao.class);
        cluster = Mockito.mock(KubernetesClusterVO.class);
        current = Mockito.mock(ServiceOfferingVO.class);
        target = Mockito.mock(ServiceOfferingVO.class);
        version = Mockito.mock(KubernetesSupportedVersion.class);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Stopped);
        Mockito.when(cluster.getServiceOfferingId()).thenReturn(1L);
        Mockito.when(cluster.getWorkerNodeServiceOfferingId()).thenReturn(3L);
        Mockito.when(manager.serviceOfferingDao.findById(3L)).thenReturn(current);
        Mockito.when(manager.serviceOfferingDao.findById(2L)).thenReturn(target);
        Mockito.when(target.getCpu()).thenReturn(6);
        Mockito.when(target.getRamSize()).thenReturn(12288);
        Mockito.when(current.getDiskOfferingStrictness()).thenReturn(true);
        Mockito.when(target.getDiskOfferingStrictness()).thenReturn(true);
        Mockito.when(current.getDiskOfferingId()).thenReturn(11L);
        Mockito.when(target.getDiskOfferingId()).thenReturn(11L);
    }

    private void validate() {
        manager.validateServiceOfferingsForNodeTypesScale(Map.of("WORKER", 2L), null, cluster, version);
    }
    private void rejects(String reason) {
        try { validate(); fail("Incompatible offering accepted"); }
        catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("WORKER"));
            assertTrue(e.getMessage().contains(reason));
        }
    }
    @Test public void stoppedStrictnessMismatchRejectedBeforeScale() {
        Mockito.when(target.getDiskOfferingStrictness()).thenReturn(false);
        rejects("strictness");
    }
    @Test public void strictDiskIdMismatchRejectedBeforeScale() {
        Mockito.when(target.getDiskOfferingId()).thenReturn(12L);
        rejects("disk offering ID");
    }
    @Test public void compatibleStrictOfferingAccepted() { validate(); }
    @Test public void nonStrictDifferentDiskOfferingRetainsCompatibility() {
        Mockito.when(current.getDiskOfferingStrictness()).thenReturn(false);
        Mockito.when(target.getDiskOfferingStrictness()).thenReturn(false);
        Mockito.when(target.getDiskOfferingId()).thenReturn(12L);
        validate();
    }
    @Test public void missingCurrentRoleOfferingRejected() {
        Mockito.when(manager.serviceOfferingDao.findById(3L)).thenReturn(null);
        rejects("current service offering");
    }
    @Test public void defaultOfferingFallbackUsesActualCurrentDisk() {
        Mockito.when(cluster.getWorkerNodeServiceOfferingId()).thenReturn(null);
        Mockito.when(manager.serviceOfferingDao.findById(1L)).thenReturn(current);
        Mockito.when(target.getDiskOfferingId()).thenReturn(12L);
        rejects("disk offering ID");
    }
}
