// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.apache.cloudstack.api.response.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StorageAdDomainApplyContractTest {
    static class Harness extends StorageServiceManagerImpl {
        StorageServiceGuestCommand captured;
        JsonObject result = JsonParser.parseString("{\"success\":false,\"membership\":\"JOINED\",\"serviceApply\":\"FAILED\",\"state\":\"ROLLED_BACK\",\"failureCode\":\"SMB_ENDPOINT_IN_USE\",\"rollback\":\"SUCCEEDED\"}").getAsJsonObject();
        @Override protected JsonObject buildSmbDesiredStatePayload(StorageServiceInstanceVO instance, Map<Long,String> passwords) {
            JsonObject value=new JsonObject();value.addProperty("instanceUuid",instance.getUuid());
            JsonObject identity=new JsonObject();identity.addProperty("joinState","JOINING");value.add("identityDomain",identity);return value;
        }
        Harness() throws Exception {
            StorageServiceGuestCommandDispatcher dispatcher=org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class);
            org.mockito.Mockito.when(dispatcher.dispatch(org.mockito.Mockito.any())).thenAnswer(call -> {
                captured=call.getArgument(0);
                return new StorageServiceGuestCommandResult(false,"non-diagnostic stderr",result.toString());
            });
            java.lang.reflect.Field field=StorageServiceManagerImpl.class.getDeclaredField("guestCommandDispatcher");
            field.setAccessible(true);field.set(this,dispatcher);
        }
    }
    @Test public void sameGuestRequestCarriesMembershipAndSmbCandidateAndPreservesStructuredFailure() throws Exception {
        Harness manager=new Harness();
        StorageServiceInstanceVO instance=new StorageServiceInstanceVO("test",null,1,2,13,1L,"SYSTEM_VM");instance.setVmId(193L);
        StorageIdentityDomainVO domain=new StorageIdentityDomainVO(instance.getId(),"ablestack.local",null,"10.10.13.247",
                StorageServiceInstance.DomainJoinState.JOINING,"UNKNOWN","{\"workgroup\":\"ABLESTACK\"}");
        JsonObject before=new JsonObject();before.addProperty("instanceUuid",instance.getUuid());
        JsonObject result=manager.applyAdJoin(instance,domain,"Administrator","dummy-not-a-live-secret",UUID.randomUUID().toString(),before);
        assertEquals("SMB_ENDPOINT_IN_USE",result.get("failureCode").getAsString());
        assertEquals("JOINED",result.get("membership").getAsString());
        assertEquals("smb domain join",manager.captured.getOperation());
        assertTrue(manager.captured.getMaskedFields().contains("password"));
        JsonObject request=JsonParser.parseString(manager.captured.getPayload()).getAsJsonObject();
        assertEquals(before,request.get("smbBeforeState"));
        assertFalse(request.get("reuseExistingMembership").getAsBoolean());
        JsonObject identity=new JsonObject();identity.addProperty("joinState","JOINED");identity.addProperty("domainName","ablestack.local");
        identity.add("config",JsonParser.parseString("{\"adJoin\":{\"serviceApply\":\"FAILED\"}}"));before.add("identityDomain",identity);
        manager.applyAdJoin(instance,domain,"Administrator","dummy-not-a-live-secret",UUID.randomUUID().toString(),before);
        assertTrue(JsonParser.parseString(manager.captured.getPayload()).getAsJsonObject().get("reuseExistingMembership").getAsBoolean());

        assertEquals("JOINED",request.getAsJsonObject("smbDesiredState").getAsJsonObject("identityDomain").get("joinState").getAsString());
    }
    @Test public void actualApiSerializerKeepsMembershipAndServiceApplyAsSeparateStages() throws Exception {
        JsonObject fixture=new JsonObject();
        for(String service:Arrays.asList("APPLYING","VERIFIED","FAILED")) {
            StorageIdentityDomainResponse response=new StorageIdentityDomainResponse();
            response.setId("domain");response.setInstanceId("instance");response.setDomainName("ablestack.local");
            response.setJoinState("JOINED");response.setHealthState("OK");
            response.setConfig("{\"adJoin\":{\"operationid\":\"operation\",\"jobid\":\"job\",\"membership\":\"JOINED\",\"serviceApply\":\""+service+"\",\"state\":\""+("APPLYING".equals(service)?"RUNNING":"VERIFIED".equals(service)?"COMMITTED":"ROLLED_BACK")+"\",\"rollback\":\""+("FAILED".equals(service)?"SUCCEEDED":"NOT_NEEDED")+"\",\"failureCode\":\""+("FAILED".equals(service)?"SMB_ENDPOINT_IN_USE":"")+"\"}}");
            response.setObjectName("storageidentitydomain");response.setResponseName("joinstorageservicetoaddomainresponse");
            JsonObject wire=JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json")).getAsJsonObject();
            JsonObject value=wire.getAsJsonObject("joinstorageservicetoaddomainresponse").getAsJsonObject("storageidentitydomain");
            assertEquals("JOINED",value.get("joinstate").getAsString());
            assertEquals(service,JsonParser.parseString(value.get("config").getAsString()).getAsJsonObject().getAsJsonObject("adJoin").get("serviceApply").getAsString());
            fixture.add(service,wire);
        }
        String target=System.getProperty("ad.domain.wire.fixture");
        if(target!=null)Files.writeString(Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
    }
}
