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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import java.util.Map;
public class StorageSmbEndpointObservationTest {
    private JsonObject health(){JsonObject root=new JsonObject();root.addProperty("success",true);root.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);JsonObject smb=new JsonObject();smb.addProperty("available",true);JsonArray endpoints=new JsonArray();for(String ip:new String[]{"10.10.13.240","10.10.13.241"}){JsonObject endpoint=new JsonObject();endpoint.addProperty("listenIp",ip);endpoint.addProperty("port",445);endpoint.addProperty("available",true);endpoint.addProperty("listenerOwned",true);endpoint.addProperty("tcpReady",true);endpoints.add(endpoint);}smb.add("runtimeEndpoints",endpoints);root.add("smbRuntime",smb);return root;}
    @Test public void oneReadyListenerCannotMakeAnotherMissingIpReady(){JsonObject health=health();health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").get(1).getAsJsonObject().addProperty("listenerOwned",false);Assert.assertEquals("READY",StorageSmbEndpointObservation.project(health,"10.10.13.240",445,System.currentTimeMillis()).get("runtimeState").getAsString());Assert.assertEquals("DEGRADED",StorageSmbEndpointObservation.project(health,"10.10.13.241",445,System.currentTimeMillis()).get("runtimeState").getAsString());Assert.assertEquals("DEGRADED",StorageSmbEndpointObservation.aggregate(health,System.currentTimeMillis()).get("runtimeState").getAsString());}
    @Test public void processExitSuccessAndOwnedSocketWithoutTcpReadinessCannotClaimReady(){JsonObject health=health();health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").get(0).getAsJsonObject().addProperty("tcpReady",false);Assert.assertEquals("DEGRADED",StorageSmbEndpointObservation.project(health,"10.10.13.240",445,System.currentTimeMillis()).get("runtimeState").getAsString());}
    @Test public void staleOrUnobservableRuntimeMasksDatabaseReady(){JsonObject old=health();old.addProperty("generatedEpoch",0);Assert.assertEquals("UNAVAILABLE",StorageSmbEndpointObservation.project(old,"10.10.13.240",445,System.currentTimeMillis()).get("runtimeState").getAsString());JsonObject unavailable=health();unavailable.getAsJsonObject("smbRuntime").addProperty("available",false);Assert.assertEquals("UNAVAILABLE",StorageSmbEndpointObservation.project(unavailable,"10.10.13.240",445,System.currentTimeMillis()).get("runtimeState").getAsString());}
    @Test public void exactIpAndPortAndUniqueObservationAreMandatory(){JsonObject health=health();Assert.assertEquals("DEGRADED",StorageSmbEndpointObservation.project(health,"10.10.13.241",1445,System.currentTimeMillis()).get("runtimeState").getAsString());health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").add(health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").get(0).deepCopy());Assert.assertEquals("UNAVAILABLE",StorageSmbEndpointObservation.project(health,"10.10.13.240",445,System.currentTimeMillis()).get("runtimeState").getAsString());}
    @Test public void serviceWideSharedConfigRequiresEveryDeclaredListenerEvenWhenObservationOmitsOne() {
        JsonObject health=health();JsonArray declared=health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").deepCopy();health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").remove(1);
        JsonObject result=StorageSmbEndpointObservation.aggregate(health,declared,System.currentTimeMillis());Assert.assertEquals("SERVICE",result.get("listenerScope").getAsString());Assert.assertEquals("DEGRADED",result.get("runtimeState").getAsString());Assert.assertEquals(2,result.getAsJsonArray("endpoints").size());
    }
    @Test public void explicitSelectedScopeIsUnaffectedByUnrelatedEndpointFailure() {
        JsonObject health=health();JsonArray scope=new JsonArray();scope.add(health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").get(0).deepCopy());health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints").get(1).getAsJsonObject().addProperty("tcpReady",false);
        Assert.assertEquals("READY",StorageSmbEndpointObservation.aggregate(health,scope,System.currentTimeMillis()).get("runtimeState").getAsString());
    }

    @SuppressWarnings("unchecked")
    private ThreadLocal<StorageServiceOperationVO> writer(StorageServiceManagerImpl target) {
        return (ThreadLocal<StorageServiceOperationVO>) ReflectionTestUtils.getField(target,"storageWriterOperation");
    }
    private StorageServiceInstanceVO instance() {
        StorageServiceInstanceVO value=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(value.getId()).thenReturn(7L);Mockito.when(value.getVmId()).thenReturn(70L);
        Mockito.when(value.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");return value;
    }
    private StorageServiceOperationVO operation(long instanceId) {
        StorageServiceOperationVO value=Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(value.getInstanceId()).thenReturn(instanceId);
        Mockito.when(value.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
        Mockito.when(value.getRevision()).thenReturn(4L);return value;
    }
    private JsonObject desired(StorageServiceInstanceVO instance,String label) {
        JsonObject value=new JsonObject();value.addProperty("instanceUuid",instance.getUuid());value.addProperty("label",label);
        value.add("shares",new JsonArray());return value;
    }
    private StorageServiceManagerImpl manager(StorageServiceGuestCommandDispatcher dispatcher) {
        StorageServiceManagerImpl value=Mockito.spy(new StorageServiceManagerImpl());
        ReflectionTestUtils.setField(value,"guestCommandDispatcher",dispatcher);return value;
    }
    @Test public void firstSmbWriterScopeIsTransportOnlyAndCredentialMaskingRemainsExact() {
        StorageServiceInstanceVO instance=instance();StorageServiceGuestCommandDispatcher dispatcher=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceManagerImpl manager=manager(dispatcher);Map<Long,String> credentials=Map.of(9L,"isolated-fixture-value");
        JsonObject source=desired(instance,"first");JsonObject privateRecord=new JsonObject();privateRecord.addProperty("password",credentials.get(9L));source.add("fixtureCredential",privateRecord);
        JsonObject before=source.deepCopy();Mockito.doReturn(source).when(manager).buildSmbDesiredPayload(instance,credentials);
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"","{}"));
        StorageServiceOperationVO operation=operation(instance.getId());writer(manager).set(operation);
        try {
            manager.applySmbDesiredState(instance,credentials);
            org.mockito.ArgumentCaptor<StorageServiceGuestCommand> sent=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);Mockito.verify(dispatcher).dispatch(sent.capture());
            Assert.assertEquals("smb share apply",sent.getValue().getOperation());Assert.assertTrue(sent.getValue().getMaskedFields().contains("password"));
            JsonObject payload=com.google.gson.JsonParser.parseString(sent.getValue().getPayload()).getAsJsonObject();
            Assert.assertEquals(manager.operationReservationScope(instance,operation),payload.remove("operationScope"));
            Assert.assertEquals(before,payload);Assert.assertEquals(before.toString(),source.toString());Assert.assertEquals("isolated-fixture-value",credentials.get(9L));
        } finally {writer(manager).remove();}
    }
    @Test public void smbPreviousReplayAfterFailureUsesSameOriginalPendingScope() {
        StorageServiceInstanceVO instance=instance();StorageServiceGuestCommandDispatcher dispatcher=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceManagerImpl manager=manager(dispatcher);Map<Long,String> credentials=Map.of();JsonObject target=desired(instance,"target"),previous=desired(instance,"previous");
        Mockito.doReturn(target).doReturn(previous).when(manager).buildSmbDesiredPayload(instance,credentials);
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(false,"owned endpoint start rejected","{}")).thenReturn(new StorageServiceGuestCommandResult(true,"","{}"));
        StorageServiceOperationVO operation=operation(instance.getId());writer(manager).set(operation);
        try {
            Assert.assertThrows(CloudRuntimeException.class,()->manager.applySmbDesiredState(instance,credentials));manager.applySmbDesiredState(instance,credentials);
            org.mockito.ArgumentCaptor<StorageServiceGuestCommand> sent=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);Mockito.verify(dispatcher,Mockito.times(2)).dispatch(sent.capture());
            for(int i=0;i<2;i++) {
                JsonObject payload=com.google.gson.JsonParser.parseString(sent.getAllValues().get(i).getPayload()).getAsJsonObject();
                Assert.assertEquals(manager.operationReservationScope(instance,operation),payload.remove("operationScope"));Assert.assertEquals(i==0?target:previous,payload);
                Assert.assertFalse(payload.has("rollbackApproved"));
            }
            Assert.assertFalse(target.has("operationScope"));Assert.assertFalse(previous.has("operationScope"));
        } finally {writer(manager).remove();}
    }
    @Test public void foreignSmbWriterCannotDispatchAndAbsentWriterCannotInventScope() {
        StorageServiceInstanceVO instance=instance();StorageServiceGuestCommandDispatcher dispatcher=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceManagerImpl manager=manager(dispatcher);Map<Long,String> credentials=Map.of();JsonObject source=desired(instance,"unchanged");
        Mockito.doReturn(source).when(manager).buildSmbDesiredPayload(instance,credentials);writer(manager).set(operation(8L));
        try {Assert.assertThrows(CloudRuntimeException.class,()->manager.applySmbDesiredState(instance,credentials));Mockito.verifyNoInteractions(dispatcher);}
        finally {writer(manager).remove();}
        Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"","{}"));manager.applySmbDesiredState(instance,credentials);
        org.mockito.ArgumentCaptor<StorageServiceGuestCommand> sent=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);Mockito.verify(dispatcher).dispatch(sent.capture());
        Assert.assertEquals(source,com.google.gson.JsonParser.parseString(sent.getValue().getPayload()));Assert.assertFalse(source.has("operationScope"));
    }
}
