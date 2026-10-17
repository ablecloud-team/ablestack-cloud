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
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
public class StorageNfsPermissionEditGateTest {
    @Test public void existingOwnerAndModeChangesRequireAnAppliedCommonPolicy(){StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageFileShareVO share=Mockito.mock(StorageFileShareVO.class);Mockito.when(share.getConfigJson()).thenReturn("{\"ownerUid\":65534,\"ownerGid\":65534,\"mode\":\"0775\"}");Assert.assertThrows(InvalidParameterValueException.class,()->manager.validateExistingNfsPermissionEdit(share,null,0,0,"0770",false));manager.validateExistingNfsPermissionEdit(share,7L,0,0,"0770",false);manager.validateExistingNfsPermissionEdit(share,null,65534,65534,"775",false);Assert.assertThrows(InvalidParameterValueException.class,()->manager.validateExistingNfsPermissionEdit(share,7L,0,0,"0770",true));}
    @SuppressWarnings("unchecked")
    private ThreadLocal<StorageServiceOperationVO> nfsWriter(StorageServiceManagerImpl target) {
        return (ThreadLocal<StorageServiceOperationVO>) ReflectionTestUtils.getField(target, "storageWriterOperation");
    }

    private StorageServiceInstanceVO nfsInstance() {
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getId()).thenReturn(7L);
        Mockito.when(instance.getVmId()).thenReturn(70L);
        Mockito.when(instance.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        return instance;
    }

    private StorageServiceOperationVO nfsOperation(long instanceId) {
        StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(operation.getInstanceId()).thenReturn(instanceId);
        Mockito.when(operation.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
        Mockito.when(operation.getRevision()).thenReturn(9L);
        return operation;
    }

    private JsonObject nfsDesired(StorageServiceInstanceVO instance, String name) {
        JsonObject desired = new JsonObject();
        desired.addProperty("instanceUuid", instance.getUuid());
        desired.addProperty("name", name);
        desired.add("exports", new com.google.gson.JsonArray());
        return desired;
    }

    private StorageServiceManagerImpl nfsManager(StorageServiceInstanceVO instance, StorageServiceGuestCommandDispatcher dispatcher) {
        StorageServiceManagerImpl target = Mockito.spy(new StorageServiceManagerImpl());
        ReflectionTestUtils.setField(target, "guestCommandDispatcher", dispatcher);
        Mockito.doNothing().when(target).requireNfsNamedPolicyCapability(instance);
        return target;
    }

    @Test
    public void nfsOwnedWriterScopeIsTransportOnlyAndCanonicalPayloadRemainsUnchanged() {
        StorageServiceInstanceVO instance = nfsInstance();
        StorageServiceGuestCommandDispatcher dispatcher = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceManagerImpl target = nfsManager(instance, dispatcher);
        JsonObject desired = nfsDesired(instance, "target"), before = desired.deepCopy();
        Mockito.doReturn(desired).when(target).buildNfsDesiredPayload(instance, null, false);
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true, "", "{\"runtimeReady\":true}"));
        StorageServiceOperationVO operation = nfsOperation(instance.getId());
        nfsWriter(target).set(operation);
        try {
            target.applyNfsDesiredState(instance);
            org.mockito.ArgumentCaptor<StorageServiceGuestCommand> sent = org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
            Mockito.verify(dispatcher).dispatch(sent.capture());
            JsonObject payload = com.google.gson.JsonParser.parseString(sent.getValue().getPayload()).getAsJsonObject();
            Assert.assertEquals("nfs export apply", sent.getValue().getOperation());
            Assert.assertEquals(target.operationReservationScope(instance, operation), payload.remove("operationScope"));
            Assert.assertEquals(before, payload);
            Assert.assertEquals(before.toString(), desired.toString());
        } finally { nfsWriter(target).remove(); }
    }

    @Test
    public void nfsFailureAndPreviousReplayKeepTheOriginalWriterScopeWithoutChangingDesiredAuthority() {
        StorageServiceInstanceVO instance = nfsInstance();
        StorageServiceGuestCommandDispatcher dispatcher = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceManagerImpl target = nfsManager(instance, dispatcher);
        JsonObject next = nfsDesired(instance, "next"), previous = nfsDesired(instance, "previous");
        Mockito.doReturn(next).doReturn(previous).when(target).buildNfsDesiredPayload(instance, null, false);
        Mockito.when(dispatcher.dispatch(Mockito.any()))
                .thenReturn(new StorageServiceGuestCommandResult(false, "owned start rejected", "{}"))
                .thenReturn(new StorageServiceGuestCommandResult(true, "", "{\"runtimeReady\":true}"));
        StorageServiceOperationVO operation = nfsOperation(instance.getId());
        nfsWriter(target).set(operation);
        try {
            Assert.assertThrows(CloudRuntimeException.class, () -> target.applyNfsDesiredState(instance));
            target.applyNfsDesiredState(instance);
            org.mockito.ArgumentCaptor<StorageServiceGuestCommand> sent = org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
            Mockito.verify(dispatcher, Mockito.times(2)).dispatch(sent.capture());
            for (int i = 0; i < 2; i++) {
                JsonObject payload = com.google.gson.JsonParser.parseString(sent.getAllValues().get(i).getPayload()).getAsJsonObject();
                Assert.assertEquals(target.operationReservationScope(instance, operation), payload.remove("operationScope"));
                Assert.assertEquals(i == 0 ? next : previous, payload);
                Assert.assertFalse(payload.has("rollbackApproved"));
            }
            Assert.assertFalse(next.has("operationScope"));
            Assert.assertFalse(previous.has("operationScope"));
        } finally { nfsWriter(target).remove(); }
    }

    @Test
    public void nfsForeignWriterCannotDispatchAndUnscopedLegacyRequestCannotInventAnOperation() {
        StorageServiceInstanceVO instance = nfsInstance();
        StorageServiceGuestCommandDispatcher dispatcher = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceManagerImpl target = nfsManager(instance, dispatcher);
        JsonObject desired = nfsDesired(instance, "unchanged");
        Mockito.doReturn(desired).when(target).buildNfsDesiredPayload(instance, null, false);
        nfsWriter(target).set(nfsOperation(8L));
        try {
            Assert.assertThrows(CloudRuntimeException.class, () -> target.applyNfsDesiredState(instance));
            Mockito.verifyNoInteractions(dispatcher);
            Assert.assertFalse(desired.has("operationScope"));
        } finally { nfsWriter(target).remove(); }
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true, "", "{\"runtimeReady\":true}"));
        target.applyNfsDesiredState(instance);
        org.mockito.ArgumentCaptor<StorageServiceGuestCommand> sent = org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
        Mockito.verify(dispatcher).dispatch(sent.capture());
        Assert.assertEquals(desired, com.google.gson.JsonParser.parseString(sent.getValue().getPayload()));
    }
}
