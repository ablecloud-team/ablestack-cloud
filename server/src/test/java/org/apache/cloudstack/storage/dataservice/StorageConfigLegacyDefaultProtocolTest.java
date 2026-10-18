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

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;

public class StorageConfigLegacyDefaultProtocolTest {
    static final String INSTANCE="11111111-1111-4111-8111-111111111111", PEER="22222222-2222-4222-8222-222222222222", DEFAULT="33333333-3333-4333-8333-333333333333";
    final Map<Long,StorageServiceProtocolVO> rows=new LinkedHashMap<>();final Gson gson=new Gson();
    static final class Manager extends StorageServiceManagerImpl {
        @Override protected StorageServiceProtocolResponse createProtocolResponse(StorageServiceProtocolVO row){
            StorageServiceProtocolResponse response=new StorageServiceProtocolResponse();response.setId(row.getUuid());return response;
        }
    }
    final Manager manager=new Manager();
    StorageServiceInstanceVO instance;StorageServiceOperationVO writer;
    StorageServiceProtocolDao protocols;StorageServiceOperationDao operations;
    <T>T copy(T value,Class<T> type){return gson.fromJson(gson.toJson(value),type);}
    static void set(Object value,String field,Object content){ReflectionTestUtils.setField(value,field,content);}
    JsonObject source(boolean wildcard){
        JsonObject row=new JsonObject();row.addProperty("uuid",wildcard?DEFAULT:PEER);row.addProperty("protocol","ISCSI");row.addProperty("enabled",true);
        row.add("listen_ip",wildcard?JsonNull.INSTANCE:new com.google.gson.JsonPrimitive("192.0.2.10"));
        row.addProperty("port",3260);row.addProperty("state","Ready");row.add("config",new JsonObject());return row;
    }
    JsonArray peers(){
        JsonObject evidence=new JsonObject();evidence.add("source",source(false));evidence.addProperty("id",10);JsonArray result=new JsonArray();result.add(evidence);return result;
    }
    void restore(JsonObject desired){manager.restoreConfigurationLegacyDefaultProtocol(instance,desired,peers());}
    @Before public void setup(){
        User user=Mockito.mock(User.class);Mockito.when(user.getId()).thenReturn(2L);Account account=Mockito.mock(Account.class);Mockito.when(account.getId()).thenReturn(2L);org.apache.cloudstack.context.CallContext.register(user,account);
        AccountManager accounts=Mockito.mock(AccountManager.class);Mockito.when(accounts.isRootAdmin(2L)).thenReturn(true);set(manager,"storageAccountManager",accounts);
        instance=new StorageServiceInstanceVO();set(instance,"id",7L);set(instance,"uuid",INSTANCE);set(instance,"accountId",2L);set(instance,"domainId",1L);instance.setVmId(42L);
        writer=new StorageServiceOperationVO();set(writer,"id",9L);writer.setInstanceId(7);writer.setCreatedBy(2);writer.setAction("applystorageserviceconfigrestoreresponse");writer.setState("RUNNING");writer.setPhase("APPLYING");writer.setRevision(2);
        operations=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(operations.findById(9L)).thenAnswer(c->copy(writer,StorageServiceOperationVO.class));set(manager,"storageOperationDao",operations);
        ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"storageWriterOperation")).set(writer);manager.beginConfigurationBatch(7L);
        StorageServiceProtocolVO peer=new StorageServiceProtocolVO(7,StorageServiceInstance.Protocol.ISCSI,true,"192.0.2.10",3260);set(peer,"id",10L);set(peer,"uuid",PEER);peer.setState(StorageServiceInstance.ResourceState.Ready);rows.put(10L,peer);
        protocols=Mockito.mock(StorageServiceProtocolDao.class);Mockito.when(protocols.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.ISCSI)).thenAnswer(c->rows.values().stream().map(v->copy(v,StorageServiceProtocolVO.class)).collect(java.util.stream.Collectors.toList()));
        Mockito.when(protocols.listByInstanceId(7L)).thenAnswer(c->rows.values().stream().map(v->copy(v,StorageServiceProtocolVO.class)).collect(java.util.stream.Collectors.toList()));
        Mockito.when(protocols.findById(Mockito.anyLong())).thenAnswer(c->copy(rows.get(c.<Long>getArgument(0)),StorageServiceProtocolVO.class));
        Mockito.when(protocols.persist(Mockito.any())).thenAnswer(c->{StorageServiceProtocolVO value=copy(c.getArgument(0),StorageServiceProtocolVO.class);set(value,"id",11L);rows.put(11L,value);return copy(value,StorageServiceProtocolVO.class);});set(manager,"storageServiceProtocolDao",protocols);
        NicDao nics=Mockito.mock(NicDao.class);NicVO nic=Mockito.mock(NicVO.class);Mockito.when(nic.getId()).thenReturn(1L);Mockito.when(nic.getIPv4Address()).thenReturn("192.0.2.10");Mockito.when(nic.isDefaultNic()).thenReturn(true);Mockito.when(nics.listByVmId(42L)).thenReturn(List.of(nic));set(manager,"nicDao",nics);
    }
    @After public void close(){((ThreadLocal<?>)ReflectionTestUtils.getField(manager,"serviceMaintenanceOperation")).remove();manager.abortConfigurationBatch();((ThreadLocal<?>)ReflectionTestUtils.getField(manager,"storageWriterOperation")).remove();org.apache.cloudstack.context.CallContext.unregister();}
    @Test public void legacyDefaultUsesTheNormalTargetInitializerWithoutChangingTheSpecificPeer(){
        JsonObject before=gson.toJsonTree(rows.get(10L)).getAsJsonObject();restore(source(true));restore(source(true));
        Assert.assertEquals(2,rows.size());Assert.assertEquals(before,gson.toJsonTree(rows.get(10L)));Assert.assertNull(rows.get(11L).getListenIp());Assert.assertTrue(rows.get(11L).isEnabled());
        Assert.assertEquals(1,manager.buildBlockProtocolListeners(instance,StorageServiceInstance.Protocol.ISCSI).size());
        Assert.assertTrue(manager.buildBlockProtocolEndpointAliases(instance,StorageServiceInstance.Protocol.ISCSI).get(0).getAsJsonObject().get("coveredByWildcard").getAsBoolean());
        Mockito.verify(protocols,Mockito.times(1)).persist(Mockito.any());
    }
    private void serviceWriter(String phase) {
        writer.setAction("SERVICE_MAINTENANCE");writer.setPhase(phase);writer.setRequestKey("SERVICE_MAINTENANCE:reviewed");writer.setPreviousSnapshotJson("{}");
        ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"serviceMaintenanceOperation")).set(writer);
        set(manager,"storageOperationControlDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class));
        Mockito.when(operations.update(Mockito.eq(9L),Mockito.any())).thenReturn(true);
    }
    @Test public void approvedServiceWriterPersistsApplyingWithoutControlAndPreservesDefaultIdentity() {
        serviceWriter("HELD");manager.beginApprovedServiceConfigurationMutation(instance,writer);
        Assert.assertEquals("APPLYING",writer.getPhase());Mockito.verify(operations).update(9L,writer);
        restore(source(true));String original=rows.get(11L).getUuid();restore(source(true));
        Assert.assertEquals(original,rows.get(11L).getUuid());Assert.assertEquals(2,rows.size());Mockito.verify(protocols,Mockito.times(1)).persist(Mockito.any());
    }
    @Test public void serviceDefaultCannotBorrowAnotherThreadLocalOrAnUnheldWriterPhase() {
        serviceWriter("HELD");
        ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"serviceMaintenanceOperation")).set(copy(writer,StorageServiceOperationVO.class));
        Assert.assertThrows(RuntimeException.class,()->manager.beginApprovedServiceConfigurationMutation(instance,writer));
        ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"serviceMaintenanceOperation")).set(writer);
        writer.setPhase("VERIFYING");Assert.assertThrows(RuntimeException.class,()->manager.beginApprovedServiceConfigurationMutation(instance,writer));
        writer.setPhase("APPLYING");((ThreadLocal<?>)ReflectionTestUtils.getField(manager,"serviceMaintenanceOperation")).remove();
        Assert.assertThrows(RuntimeException.class,()->restore(source(true)));Assert.assertEquals(1,rows.size());Mockito.verify(protocols,Mockito.never()).persist(Mockito.any());
        Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }
    @Test public void sourceErrorDiagnosticDoesNotBecomeALiveTargetReadinessRequirement(){
        JsonObject desired=source(true);desired.addProperty("state","Error");JsonObject peer=source(false);peer.addProperty("state","Updating");
        JsonObject evidence=new JsonObject();evidence.add("source",peer);evidence.addProperty("id",10);JsonArray selected=new JsonArray();selected.add(evidence);
        manager.restoreConfigurationLegacyDefaultProtocol(instance,desired,selected);Assert.assertEquals(2,rows.size());
    }
    JsonObject intentPlan(boolean preserve){
        JsonObject plan=new JsonObject();for(String action:List.of("keep","update","create","preserve"))plan.add(action,new JsonArray());
        JsonObject keep=new JsonObject();keep.addProperty("kind","protocols");keep.addProperty("sourceUuid",PEER);keep.addProperty("targetUuid",PEER);keep.add("desired",source(false));plan.getAsJsonArray("keep").add(keep);
        JsonObject create=new JsonObject();create.addProperty("kind","protocols");create.addProperty("sourceUuid",DEFAULT);create.add("desired",source(true));plan.getAsJsonArray("create").add(create);
        if(preserve){JsonObject value=new JsonObject();value.addProperty("kind","protocols");value.addProperty("targetUuid",rows.get(12L).getUuid());plan.getAsJsonArray("preserve").add(value);}
        return plan;
    }
    @Test public void finalIntentRequiresDistinctReviewedIdsAndRejectsAnImplicitUnplannedDefault(){
        JsonObject before=manager.configurationProtocolIntentSnapshot(instance),plan=intentPlan(false);restore(source(true));
        manager.requireConfigurationProtocolIntent(instance,plan,Map.of(PEER,10L,DEFAULT,11L),before);
        Assert.assertThrows(RuntimeException.class,()->manager.requireConfigurationProtocolIntent(instance,plan,Map.of(PEER,10L,DEFAULT,10L),before));
        plan.getAsJsonArray("create").remove(0);
        Assert.assertThrows(RuntimeException.class,()->manager.requireConfigurationProtocolIntent(instance,plan,Map.of(PEER,10L),before));
        rows.get(11L).setEnabled(false);
        Assert.assertThrows(RuntimeException.class,()->manager.requireConfigurationProtocolIntent(instance,intentPlan(false),Map.of(PEER,10L,DEFAULT,11L),before));
    }
    @Test public void finalIntentPreservesUnmappedProtocolIdentityAndSettings(){
        StorageServiceProtocolVO untouched=new StorageServiceProtocolVO(7,StorageServiceInstance.Protocol.ISCSI,true,"192.0.2.11",3261);set(untouched,"id",12L);rows.put(12L,untouched);
        JsonObject before=manager.configurationProtocolIntentSnapshot(instance),plan=intentPlan(true);restore(source(true));
        manager.requireConfigurationProtocolIntent(instance,plan,Map.of(PEER,10L,DEFAULT,11L),before);
        rows.get(12L).setPort(3262);
        Assert.assertThrows(RuntimeException.class,()->manager.requireConfigurationProtocolIntent(instance,plan,Map.of(PEER,10L,DEFAULT,11L),before));
    }
    @Test public void corruptCurrentPeerConfigCannotBeParsedAsAnEmptyLegacyConfig(){
        rows.get(10L).setConfigJson("not-json");
        Assert.assertThrows(RuntimeException.class,()->restore(source(true)));Assert.assertEquals(1,rows.size());
        Mockito.verify(protocols,Mockito.never()).persist(Mockito.any());
    }
    @Test public void normalEnableConflictGuardIsStillUnchanged(){
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class,()->manager.validateBlockProtocolListenerConflict(instance,StorageServiceInstance.Protocol.ISCSI,null,3260,null));
        Assert.assertEquals(1,rows.size());
    }
    @Test public void nonLegacyTuplesAndUnsupportedSchemaNeverFallBackToAnEnable(){
        for(String field:List.of("port","config","enabled","listen_ip","state","extra")){
            JsonObject value=source(true);
            if(field.equals("port"))value.addProperty(field,3261);
            if(field.equals("config"))value.getAsJsonObject(field).addProperty("unknown",true);
            if(field.equals("enabled"))value.addProperty(field,false);
            if(field.equals("listen_ip"))value.addProperty(field,"0.0.0.0");
            if(field.equals("state"))value.addProperty(field,"UNKNOWN");
            if(field.equals("extra"))value.addProperty(field,true);
            Assert.assertThrows(RuntimeException.class,()->restore(value));Assert.assertEquals(1,rows.size());
        }
        Mockito.verify(protocols,Mockito.never()).persist(Mockito.any());
    }
    @Test public void foreignScopeWriterActorAndUnreviewedPeerAreRejectedBeforeAnyDefaultWrite(){
        for(int mode=0;mode<4;mode++){
            long instanceId=instance.getId(),owner=writer.getCreatedBy();String phase=writer.getPhase(),action=writer.getAction();String ip=rows.get(10L).getListenIp();
            if(mode==0)set(instance,"id",8L);if(mode==1)writer.setCreatedBy(3);if(mode==2)writer.setPhase("VERIFYING");if(mode==3)rows.get(10L).setListenIp("192.0.2.11");
            Assert.assertThrows(RuntimeException.class,()->restore(source(true)));Assert.assertEquals(1,rows.size());
            set(instance,"id",instanceId);writer.setCreatedBy(owner);writer.setPhase(phase);writer.setAction(action);rows.get(10L).setListenIp(ip);
        }
        Mockito.verify(protocols,Mockito.never()).persist(Mockito.any());
    }
}
