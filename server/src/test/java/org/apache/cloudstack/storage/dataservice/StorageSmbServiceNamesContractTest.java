// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;
import java.util.List;
import java.util.Set;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageSmbServiceNameDao;

public class StorageSmbServiceNamesContractTest {
    private StorageSmbServiceNameDao names;
    private StorageServiceProtocolDao protocols;
    private StorageIdentityDomainDao domains;
    private StorageServiceGuestCommandDispatcher guest;
    private StorageServiceInstanceVO instance;
    private StorageSmbServiceNameVO row;
    private StorageServiceProtocolVO endpoint;
    private StorageSmbServiceNames service;
    private final String endpointId="11111111-2222-3333-4444-555555555555";
    private static void field(Object target,String name,Object value) throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Before public void prepare() throws Exception {
        names=mock(StorageSmbServiceNameDao.class);protocols=mock(StorageServiceProtocolDao.class);
        domains=mock(StorageIdentityDomainDao.class);guest=mock(StorageServiceGuestCommandDispatcher.class);
        instance=new StorageServiceInstanceVO("name-contract",null,1,2,13,1L,"SYSTEM_VM");field(instance,"id",9L);instance.setVmId(221L);
        StorageIdentityDomainVO domain=new StorageIdentityDomainVO(9,"ablestack.local",null,"10.10.13.247",StorageServiceInstance.DomainJoinState.JOINED,"OK","{}");
        when(domains.findByInstanceId(9)).thenReturn(domain);
        endpoint=new StorageServiceProtocolVO(9,StorageServiceInstance.Protocol.SMB,true,"10.10.13.220",445);
        endpoint.setState(StorageServiceInstance.ResourceState.Ready);field(endpoint,"uuid",endpointId);
        when(protocols.findByUuid(endpointId)).thenReturn(endpoint);
        when(protocols.listByInstanceIdAndProtocol(9,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(endpoint));
        row=new StorageSmbServiceNameVO();row.setInstanceId(9);row.setUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");row.setFqdn("files.ablestack.local");
        row.setEndpointUuid(endpointId);row.setState(StorageServiceInstance.ResourceState.Ready);
        JsonObject desired=new JsonObject();desired.addProperty("fqdn",row.getFqdn());desired.addProperty("ip","10.10.13.220");
        desired.addProperty("port",445);desired.addProperty("kind","ALIAS");desired.addProperty("endpointUuid",endpointId);
        row.setDesiredJson(desired.toString());
        when(names.listByInstanceId(9)).thenReturn(List.of(row));when(names.findByUuid(row.getUuid())).thenReturn(row);
        service=new StorageSmbServiceNames(names,protocols,domains,guest,x->"10.10.13.220");
    }
    private void denied(String code,Runnable action){
        try{action.run();fail("Expected "+code);}catch(CloudRuntimeException expected){assertEquals(code,expected.getMessage());}
    }
    @Test public void omittedEnrollmentIsNoOpAndDoesNotContactGuest(){
        assertEquals(0,service.enrollment(null,instance,"ablestack.local",false).size());verifyNoInteractions(guest);
    }
    @Test public void enrollmentIsBoundedScopedCanonicalAndRequiresAcknowledgement(){
        denied("SMB_NAME_ENROLLMENT_ACK_REQUIRED",()->service.enrollment("[{\"fqdn\":\"new.ablestack.local\"}]",instance,"ablestack.local",false));
        denied("SMB_NAME_ZONE_MISMATCH",()->service.enrollment("[{\"fqdn\":\"new.foreign.local\"}]",instance,"ablestack.local",true));
        denied("SMB_NAME_DUPLICATE",()->service.enrollment("[{\"fqdn\":\"a.ablestack.local\"},{\"fqdn\":\"A.ablestack.local.\"}]",instance,"ablestack.local",true));
        denied("SMB_NAME_ENROLLMENT_INVALID",()->service.enrollment("[{\"fqdn\":\"a.ablestack.local\",\"password\":\"cannot-be-stored\"}]",instance,"ablestack.local",true));
        JsonArray parsed=service.enrollment("[{\"fqdn\":\"A.ablestack.local.\",\"endpointid\":\""+endpointId+"\"}]",instance,"ablestack.local",true);
        assertEquals("a.ablestack.local",parsed.get(0).getAsJsonObject().get("fqdn").getAsString());verifyNoInteractions(guest);
    }
    @Test public void referencedEndpointCannotMoveOrDeleteButUnrelatedEndpointCan(){
        service.guardEndpoint(instance,endpoint,"10.10.13.220",445,false);
        denied("SMB_NAME_REFERENCES_ENDPOINT",()->service.guardEndpoint(instance,endpoint,"10.10.13.221",445,false));
        denied("SMB_NAME_REFERENCES_ENDPOINT",()->service.guardEndpoint(instance,endpoint,"10.10.13.220",445,true));
        StorageServiceProtocolVO other=new StorageServiceProtocolVO(9,StorageServiceInstance.Protocol.SMB,true,"10.10.13.220",1445);
        service.guardEndpoint(instance,other,"10.10.13.220",1445,true);
        verifyNoInteractions(guest);
    }
    @Test public void restorePreservesLogicalListenerUuidAddressAndPort(){
        JsonObject desired=JsonParser.parseString("{\"listeners\":[{\"uuid\":\""+endpointId+"\",\"protocol\":\"SMB\",\"enabled\":true,\"listenIp\":\"10.10.13.220\",\"port\":445}]}").getAsJsonObject();
        service.guardRestore(instance,desired,Set.of(StorageServiceInstance.Protocol.SMB));
        desired.getAsJsonArray("listeners").get(0).getAsJsonObject().addProperty("listenIp","10.10.13.221");
        denied("SMB_NAME_RESTORE_BINDING_CHANGED",()->service.guardRestore(instance,desired,Set.of(StorageServiceInstance.Protocol.SMB)));
        service.guardRestore(instance,desired,Set.of(StorageServiceInstance.Protocol.NFS));verifyNoInteractions(guest);
    }
    @Test public void deleteCanCleanOwnedNamesWithoutDependingOnAStillReadyEndpoint(){
        when(protocols.findByUuid(endpointId)).thenReturn(null);
        JsonObject intent=JsonParser.parseString("{\"api\":\"deleteStorageSmbServiceName\",\"id\":\""+row.getUuid()+"\"}").getAsJsonObject();
        JsonObject request=service.request(instance,intent);
        assertEquals("DELETE",request.get("action").getAsString());assertEquals(row.getFqdn(),request.getAsJsonObject("entry").get("fqdn").getAsString());
        verifyNoInteractions(guest);verify(protocols,never()).findByUuid(anyString());
    }
    @Test public void contextUsesOnlyCapabilityAndReadOnlyEmptyEntryObservation(){
        when(guest.dispatch(any())).thenAnswer(call->{
            StorageServiceGuestCommand input=call.getArgument(0);
            if(input.getOperation().equals("configuration capability show"))return new StorageServiceGuestCommandResult(true,"capability","{\"smbAdServiceNamesVersion\":1}");
            assertEquals("configuration smb-names observe",input.getOperation());
            JsonObject request=JsonParser.parseString(input.getPayload()).getAsJsonObject();
            assertEquals(instance.getUuid(),request.get("instanceUuid").getAsString());assertEquals(0,request.getAsJsonArray("entries").size());
            return new StorageServiceGuestCommandResult(true,"readonly","{\"context\":{\"status\":\"OBSERVED\",\"fqdn\":\"member.ablestack.local\",\"guid\":\"member-guid\"},\"entries\":[]}");
        });
        JsonObject value=new Gson().toJsonTree(service.context(instance)).getAsJsonObject();
        assertEquals("member-guid",value.getAsJsonObject("context").get("guid").getAsString());verify(guest,times(2)).dispatch(any());
    }
    @Test public void preflightDisplaysTheActiveConfigurationFenceBeforeAnyAdQuery(){
        instance.setActiveConfigurationOperationId(77L);
        JsonObject intent=JsonParser.parseString("{\"api\":\"createStorageSmbServiceName\",\"fqdn\":\"new.ablestack.local\"}").getAsJsonObject();
        JsonObject report=service.preflight(instance,intent,null);
        assertEquals("BLOCKED",report.get("status").getAsString());
        assertEquals("SMB_NAME_CONFIGURATION_WRITER_ACTIVE",report.getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
        verifyNoInteractions(guest);
    }
    @Test public void sameCountRegistryReplyCannotSubstituteAnotherResourceUuid(){
        when(guest.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(true,"wrong-resource","{\"entries\":[{\"uuid\":\"foreign-resource\",\"status\":\"MATCH\"}]}"));
        denied("SMB_NAME_REJOIN_IDENTITY_UNCONFIRMED",()->service.verifyRegistry(instance));
    }
    @Test public void oldHelperGetsExplicitCapabilityGuidanceWithoutUnsupportedObservation(){
        when(guest.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(true,"old-capability","{}"));
        JsonObject value=new Gson().toJsonTree(service.context(instance)).getAsJsonObject();
        assertEquals("SMB_NAME_CAPABILITY_REQUIRED",value.getAsJsonObject("context").get("code").getAsString());verify(guest,times(1)).dispatch(any());
    }
}
