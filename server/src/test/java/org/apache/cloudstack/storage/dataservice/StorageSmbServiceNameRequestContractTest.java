// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.cloud.user.Account;
import com.cloud.user.User;
import org.apache.cloudstack.context.CallContext;
import com.cloud.utils.db.EntityManager;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.BaseCmd;
import com.cloud.api.dispatch.DispatchTask;
import com.cloud.api.dispatch.ParamProcessWorker;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbServiceNameCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageSmbServiceNameDao;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StorageSmbServiceNameRequestContractTest {
    private static class Input extends ParamProcessWorker {
        @Override protected void doAccessChecks(BaseCmd cmd,Map<Object,AccessType> entities){}
    }
    @Test public void actualMarshallerKeepsEndpointUuidOpaqueAndBothPublicIntentsProduceIdenticalGuestRequests() throws Exception {
        String instanceId="11111111-2222-3333-4444-555555555555",nameId="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",endpointId="99999999-8888-7777-6666-555555555555";
        StorageServiceInstanceVO instance=new StorageServiceInstanceVO("input-contract",null,1,2,13,1L,"SYSTEM_VM");
        ReflectionTestUtils.setField(instance,"id",9L);ReflectionTestUtils.setField(instance,"uuid",instanceId);instance.setVmId(221L);
        StorageSmbServiceNameVO row=new StorageSmbServiceNameVO();ReflectionTestUtils.setField(row,"id",77L);row.setUuid(nameId);row.setInstanceId(9);
        row.setNameKind("ALIAS");row.setFqdn("old.ablestack.local");row.setEndpointUuid(endpointId);row.setState(StorageServiceInstance.ResourceState.Ready);
        row.setDesiredJson("{\"fqdn\":\"old.ablestack.local\",\"kind\":\"ALIAS\",\"ip\":\"10.10.13.220\",\"port\":445,\"endpointUuid\":\""+endpointId+"\"}");
        StorageServiceProtocolVO endpoint=new StorageServiceProtocolVO(9,StorageServiceInstance.Protocol.SMB,true,"10.10.13.220",445);
        ReflectionTestUtils.setField(endpoint,"uuid",endpointId);endpoint.setState(StorageServiceInstance.ResourceState.Ready);
        StorageSmbServiceNameDao names=mock(StorageSmbServiceNameDao.class);when(names.findById(77L)).thenReturn(row);when(names.findByUuid(nameId)).thenReturn(row);when(names.listByInstanceId(9)).thenReturn(List.of(row));
        StorageServiceProtocolDao protocols=mock(StorageServiceProtocolDao.class);when(protocols.findByUuid(endpointId)).thenReturn(endpoint);
        StorageIdentityDomainDao domains=mock(StorageIdentityDomainDao.class);
        when(domains.findByInstanceId(9)).thenReturn(new StorageIdentityDomainVO(9,"ablestack.local",null,null,StorageServiceInstance.DomainJoinState.JOINED,"OK","{}"));
        StorageSmbServiceNames service=new StorageSmbServiceNames(names,protocols,domains,mock(StorageServiceGuestCommandDispatcher.class),x->"10.10.13.220");
        EntityManager entities=mock(EntityManager.class);
        when(entities.findByUuidIncludingRemoved(any(),eq(instanceId))).thenReturn(instance);when(entities.findByUuidIncludingRemoved(any(),eq(nameId))).thenReturn(row);
        Input input=new Input();ReflectionTestUtils.setField(input,"_entityMgr",entities);
        CallContext.register(mock(User.class),mock(Account.class));
        try{
            Map<String,String> params=new HashMap<>();
            params.put("instanceid",instanceId);params.put("id",nameId);params.put("endpointid",endpointId);
            params.put("fqdn","new.ablestack.local");params.put("kind","ALIAS");params.put("expectedrevision","3");
            params.put("username","runtime-operator");params.put("password","runtime-password");
            UpdateStorageSmbServiceNameCmd cmd=new UpdateStorageSmbServiceNameCmd();input.handle(new DispatchTask(cmd,params));
            assertEquals(Long.valueOf(9),cmd.getInstanceId());assertEquals(Long.valueOf(77),cmd.getId());assertEquals(endpointId,cmd.getEndpointId());
            JsonObject fromApi=StorageServiceConfigurationPreflight.commandIntent(cmd);
            JsonObject fromUi=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageSmbServiceName\",\"id\":\""+nameId+"\",\"endpointid\":\""+endpointId+"\",\"fqdn\":\"new.ablestack.local\",\"kind\":\"ALIAS\"}");
            assertEquals(service.request(instance,fromUi),service.request(instance,fromApi));
            verify(entities,never()).findByUuidIncludingRemoved(any(),eq(endpointId));
            String accepted=new Gson().toJson(StorageServiceConfigurationCoordinator.publicRequest(cmd));
            assertFalse(accepted.contains("runtime-operator"));assertFalse(accepted.contains("runtime-password"));
        }finally{CallContext.unregister();}
    }
}
