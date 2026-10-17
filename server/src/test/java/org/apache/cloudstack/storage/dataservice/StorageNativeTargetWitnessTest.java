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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageNativeTargetWitnessTest {
    private static class Fixture extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final List<String> events = new ArrayList<>();
        boolean failWitness;
        Fixture() {
            Mockito.when(instance.getId()).thenReturn(9L);
            Mockito.when(instance.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
            ReflectionTestUtils.setField(this, "storageOperationControlDao",
                    Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class));
        }
        @Override public Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd command) { return 9L; }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
        @Override protected void persistTargetFinalizationWitness(StorageServiceInstanceVO scope, StorageServiceOperationVO operation) {
            Assert.assertSame(instance, scope);events.add("witness");
            if (failWitness) throw new CloudRuntimeException("Witness persistence rejected");
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO scope, StorageServiceOperationVO operation, String action) {
            Assert.assertSame(instance, scope);events.add(action);return new JsonObject();
        }
        DesiredStateChange.Runtime runtime() {
            var command = new org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd();
            AtomicReference<DesiredStateChange.Runtime> captured = new AtomicReference<>();
            try (MockedStatic<StorageServiceRequestFingerprint> fingerprint = Mockito.mockStatic(StorageServiceRequestFingerprint.class);
                    MockedConstruction<DesiredStateChange> engines = Mockito.mockConstruction(DesiredStateChange.class,
                            (engine, context) -> Mockito.when(engine.execute(ArgumentMatchers.eq(9L), ArgumentMatchers.anyString(),
                                    ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(Long.class),
                                    ArgumentMatchers.eq("witness-fingerprint"), ArgumentMatchers.eq(String.class),
                                    ArgumentMatchers.any(), ArgumentMatchers.any())).thenAnswer(call -> {
                                        captured.set(call.getArgument(7));return "captured";
                                    }))) {
                fingerprint.when(() -> StorageServiceRequestFingerprint.of(command)).thenReturn("witness-fingerprint");
                Assert.assertEquals("captured", executeDesiredChange(command, String.class,
                        () -> { throw new AssertionError("No mutation in Runtime capture"); }));
                Assert.assertEquals(1, engines.constructed().size());
            }
            Assert.assertNotNull(captured.get());return captured.get();
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        void rendered() throws Exception {
            Class<?> type = Class.forName(StorageServiceManagerImpl.class.getName() + "$RenderedBatch");
            ThreadLocal local = (ThreadLocal) ReflectionTestUtils.getField(this, "renderedBatch");
            local.set(Mockito.mock(type));
        }
    }
    private StorageServiceOperationVO checkpoint() {
        StorageServiceOperationVO operation = new StorageServiceOperationVO();
        operation.setPreviousSnapshotJson("{\"nativeGeneration\":{}}");return operation;
    }
    @Test public void actualFactoryControlOffStillPersistsWitnessBeforeVerifyAndCommit() {
        Fixture fixture = new Fixture();fixture.runtime().verifyNativeGeneration(checkpoint());
        Assert.assertEquals(List.of("witness", "verify", "commit"), fixture.events);
    }
    @Test public void actualFactoryWitnessFailurePreventsEveryNativeCommitEffect() {
        Fixture fixture = new Fixture();fixture.failWitness = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> fixture.runtime().verifyNativeGeneration(checkpoint()));
        Assert.assertEquals(List.of("witness"), fixture.events);
    }
    @Test public void absentNativeCheckpointDoesNotInventWitnessOrGeneration() {
        Fixture fixture = new Fixture();fixture.runtime().verifyNativeGeneration(new StorageServiceOperationVO());
        Assert.assertTrue(fixture.events.isEmpty());
    }
    @Test public void renderedBatchRetainsItsCommitOwnershipAfterWitness() throws Exception {
        Fixture fixture = new Fixture();DesiredStateChange.Runtime runtime = fixture.runtime();fixture.rendered();
        runtime.verifyNativeGeneration(checkpoint());Assert.assertEquals(List.of("witness"), fixture.events);
    }
}
