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
package org.apache.cloudstack.storage.volume;

import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionService;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.exception.InvalidParameterValueException;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationLifecycleGuardTest {
    @InjectMocks private VolumeServiceImpl service;
    @Mock private VolumeDetailsDao details;
    @Mock private VolumeInfo volume;
    @Mock private SnapshotInfo snapshot;
    @Mock private DataStore store;
    @Mock private DataMotionService motion;
    @Before public void setup() {
        when(volume.getId()).thenReturn(1L); when(snapshot.getVolumeId()).thenReturn(2L);
        when(details.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null));
        when(details.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null));
    }
    private void blocked(Runnable action) {
        try { action.run(); fail("Must reject before storage mutation"); }
        catch(InvalidParameterValueException e) { assertTrue(e.getMessage().contains("ALLOCATION_OPERATION_UNSUPPORTED")); }
        verifyNoInteractions(store,motion);
        verify(volume,never()).processEvent(org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event.ResizeRequested);
    }
    @Test public void directStorageResizeCannotBypassApi() { blocked(() -> service.resize(volume)); }
    @Test public void directCopyCannotLoseFrozenPolicy() { blocked(() -> service.copyVolume(volume,store)); }
    @Test public void directMigrationStopsBeforeStoreOrStateChange() { blocked(() -> service.migrateVolume(volume,store)); }
    @Test public void restoreTargetStopsBeforeCreation() { blocked(() -> service.createVolumeFromSnapshot(volume,store,snapshot)); }
    @Test public void cloneTargetStopsBeforeCreation() { blocked(() -> service.cloneVolumeFromSnapshot(volume,store,snapshot)); }
    @Test public void restoreChecksSourceEvenWhenTargetIsLegacy() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of()); blocked(() -> service.createVolumeFromSnapshot(volume,store,snapshot)); }
    @Test public void cloneChecksSourceEvenWhenTargetIsLegacy() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of()); blocked(() -> service.cloneVolumeFromSnapshot(volume,store,snapshot)); }
    @Test public void multiVolumeMigrationCannotBypassOrchestration() { blocked(() -> service.migrateVolumes(Map.of(volume,store),null,null,null)); }
    @Test public void directUploadRegistrationCannotInventAllocationEvidence() { blocked(() -> service.registerVolume(volume,store)); }
    @Test public void directBrowserUploadCannotInventAllocationEvidence() { blocked(() -> service.registerVolumeForPostUpload(volume,store)); }

}
