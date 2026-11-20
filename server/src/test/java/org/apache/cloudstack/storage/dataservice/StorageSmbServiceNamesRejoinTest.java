// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.JoinStorageServiceToAdDomainCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.response.StorageSmbServiceNameResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageSmbServiceNameDao;

public class StorageSmbServiceNamesRejoinTest {
    private StorageServiceManagerImpl manager;
    private StorageServiceInstanceVO instance;
    private StorageIdentityDomainVO domain;
    private StorageSmbServiceNameVO row;
    private StorageIdentityDomainDao domains;
    private StorageServiceGuestCommandDispatcher guest;
    private final java.util.concurrent.atomic.AtomicBoolean repaired=new java.util.concurrent.atomic.AtomicBoolean();
    private JsonObject previous;
    private static void field(Object target,Class<?> type,String name,Object value) throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Before public void prepare() throws Exception {
        manager=spy(new StorageServiceManagerImpl());
        StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);
        instance=new StorageServiceInstanceVO("rejoin-name-contract",null,1,2,13,1L,"SYSTEM_VM");
        field(instance,instance.getClass(),"id",9L);instance.setVmId(221L);instance.setConfigurationRevision(3);
        when(instances.findById(9L)).thenReturn(instance);field(manager,StorageServiceManagerImpl.class,"storageServiceInstanceDao",instances);
        StorageSmbServiceNameDao names=mock(StorageSmbServiceNameDao.class);
        try(var in=getClass().getResourceAsStream("/storage-config/smb-ad-names-rollback-actual.json")){
            JsonObject data=JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            previous=data.getAsJsonObject("request").getAsJsonObject("previous").deepCopy();
        }
        row=new StorageSmbServiceNameVO();row.setInstanceId(9);field(row,row.getClass(),"id",1L);
        row.setUuid(previous.get("uuid").getAsString());row.setFqdn(previous.get("fqdn").getAsString());row.setNameKind("ALIAS");
        row.setEndpointUuid(previous.get("endpointUuid").getAsString());row.setDesiredJson(StorageSmbServiceNames.withoutOwnership(previous).toString());
        row.setOwnershipJson(previous.get("ownership").toString());row.setState(StorageServiceInstance.ResourceState.Ready);
        when(names.listByInstanceId(9)).thenReturn(List.of(row));when(names.findByUuid(row.getUuid())).thenReturn(row);
        when(names.findById(1L)).thenReturn(row);field(manager,StorageServiceManagerImpl.class,"smbServiceNameDao",names);
        StorageServiceProtocolDao protocols=mock(StorageServiceProtocolDao.class);
        StorageServiceProtocolVO endpoint=new StorageServiceProtocolVO(9,StorageServiceInstance.Protocol.SMB,true,"10.10.13.220",445);
        field(endpoint,endpoint.getClass(),"uuid",row.getEndpointUuid());endpoint.setState(StorageServiceInstance.ResourceState.Ready);
        when(protocols.findByUuid(row.getEndpointUuid())).thenReturn(endpoint);
        field(manager,StorageServiceManagerImpl.class,"storageServiceProtocolDao",protocols);
        domains=mock(StorageIdentityDomainDao.class);
        domain=new StorageIdentityDomainVO(9,"ablestack.local",null,"10.10.13.247",StorageServiceInstance.DomainJoinState.JOINED,"OK","{\"adJoin\":{\"state\":\"COMMITTED\",\"membership\":\"JOINED\",\"serviceApply\":\"VERIFIED\"}}");
        when(domains.findByInstanceId(9)).thenReturn(domain);field(manager,StorageServiceManagerImpl.class,"storageIdentityDomainDao",domains);
        guest=mock(StorageServiceGuestCommandDispatcher.class);field(manager,StorageServiceManagerImpl.class,"guestCommandDispatcher",guest);
    }
    private boolean invoke(String status,String code) throws Exception {
        JoinStorageServiceToAdDomainCmd parent=new JoinStorageServiceToAdDomainCmd();Object job=new Object();parent.setJob(job);
        field(parent,parent.getClass(),"username","one-use-rejoin-user");field(parent,parent.getClass(),"password","one-use-rejoin-password");
        when(guest.dispatch(any())).thenAnswer(call->{
            StorageServiceGuestCommand input=call.getArgument(0);JsonObject out=new JsonObject();
            if("configuration smb-names observe".equals(input.getOperation())){
                JsonObject item=new JsonObject();item.addProperty("uuid",row.getUuid());item.addProperty("status",repaired.get()?"MATCH":status);
                if(code!=null)item.addProperty("code",code);JsonArray entries=new JsonArray();entries.add(item);out.add("entries",entries);
            }else{
                assertEquals("configuration smb-names preview",input.getOperation());
                JsonObject request=JsonParser.parseString(input.getPayload()).getAsJsonObject();
                JsonObject observed=new JsonObject();observed.add("entry",StorageSmbServiceNames.withoutOwnership(previous));observed.add("ownership",previous.get("ownership"));
                out.add("entry",observed);out.addProperty("fingerprint","repair-confirmed");
                assertEquals("UPDATE",request.get("action").getAsString());
            }
            return new StorageServiceGuestCommandResult(true,"rejoin-native-observation-contract",out.toString());
        });
        doAnswer(call->{
            BaseStorageSmbServiceNameCmd child=call.getArgument(0);assertTrue(child instanceof UpdateStorageSmbServiceNameCmd);
            assertEquals(Long.valueOf(1),child.getId());assertEquals(row.getFqdn(),child.getFqdn());
            assertEquals("repair-confirmed",child.getImpactAck());assertEquals("one-use-rejoin-password",child.getPassword());
            assertSame(job,child.getJob());repaired.set(true);return new StorageSmbServiceNameResponse();
        }).when(manager).mutateStorageSmbServiceName(any());
        Method method=StorageServiceManagerImpl.class.getDeclaredMethod("reconcileJoinedSmbNames",StorageServiceInstanceVO.class,StorageIdentityDomainVO.class,JoinStorageServiceToAdDomainCmd.class,String.class);
        method.setAccessible(true);return (boolean)method.invoke(manager,instance,domain,parent,"join-attempt");
    }
    @Test public void sameComputerMissingNameIsRepairedThroughNormalGuardedUpdateWithoutRejoiningAgain() throws Exception {
        assertTrue(invoke("DRIFT",null));verify(manager,times(1)).mutateStorageSmbServiceName(any());
        JsonObject ad=JsonParser.parseString(domain.getConfigJson()).getAsJsonObject().getAsJsonObject("adJoin");
        assertEquals("JOINED",ad.get("membership").getAsString());assertEquals("VERIFIED",ad.get("serviceApply").getAsString());
        assertEquals("VERIFIED",ad.get("namesApply").getAsString());assertFalse(domain.getConfigJson().contains("one-use-rejoin"));
    }
    @Test public void foreignComputerNeverGetsAdoptedAndMembershipSuccessRemainsDistinct() throws Exception {
        assertFalse(invoke("BLOCKED","SMB_NAME_COMPUTER_IDENTITY_CHANGED"));verify(manager,never()).mutateStorageSmbServiceName(any());
        JsonObject ad=JsonParser.parseString(domain.getConfigJson()).getAsJsonObject().getAsJsonObject("adJoin");
        assertEquals("JOINED",ad.get("membership").getAsString());assertEquals("VERIFIED",ad.get("serviceApply").getAsString());
        assertEquals("FAILED",ad.get("namesApply").getAsString());assertEquals("SMB_NAME_COMPUTER_IDENTITY_CHANGED",ad.get("namesFailureCode").getAsString());
    }
    @Test public void matchingNamesRequireNoMutation() throws Exception {
        assertTrue(invoke("MATCH",null));verify(manager,never()).mutateStorageSmbServiceName(any());assertFalse(repaired.get());
    }
}
