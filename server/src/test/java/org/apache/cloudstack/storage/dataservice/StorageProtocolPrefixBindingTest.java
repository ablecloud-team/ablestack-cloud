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

import java.lang.reflect.Field;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class StorageProtocolPrefixBindingTest {
    private static final String IP="10.10.13.227",MAC="02:0d:00:cc:00:2e";
    private Probe manager;private StorageServiceInstanceVO instance;private NicVO nic;private SharedFSVO fs;private NetworkVO network;
    private static class Probe extends StorageServiceManagerImpl {
        JsonObject proof;JsonObject lastRequest;int calls;
        JsonObject payload(StorageServiceInstanceVO i,NicVO n,int port){return protocolListenAddressPayload(i,IP,n,port);}
        void preflight(StorageServiceInstanceVO i,JsonObject c,JsonObject source){validateRenderedEndpointBindings(i,c,source);}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO i,String command,JsonObject request,int timeout){
            Assert.assertEquals("network endpoints inspect",command);calls++;lastRequest=request.deepCopy();
            if(proof!=null)return proof;
            JsonObject out=new JsonObject();out.addProperty("success",true);out.addProperty("sideEffects",false);out.addProperty("bindingsValidated",true);
            JsonArray rows=new JsonArray();
            for(JsonElement e:request.getAsJsonArray("expectedBindings")){JsonObject row=e.getAsJsonObject().deepCopy();row.addProperty("active",true);rows.add(row);}
            out.add("endpoints",rows);return out;
        }
    }
    private static void set(Object object,String name,Object value)throws Exception {
        Class<?> type=object.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);return;}catch(NoSuchFieldException e){type=type.getSuperclass();}}
        throw new NoSuchFieldException(name);
    }
    @Before public void setup()throws Exception{
        manager=new Probe();instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(80L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        nic=Mockito.mock(NicVO.class);Mockito.when(nic.getId()).thenReturn(32L);Mockito.when(nic.getNetworkId()).thenReturn(20L);
        Mockito.when(nic.isDefaultNic()).thenReturn(true);Mockito.when(nic.getMacAddress()).thenReturn(MAC);Mockito.when(nic.getIPv4Address()).thenReturn(IP);
        NicDao nics=Mockito.mock(NicDao.class);Mockito.when(nics.listByVmId(80L)).thenReturn(java.util.List.of(nic));set(manager,"nicDao",nics);set(manager,"nicSecondaryIpDao",Mockito.mock(com.cloud.vm.dao.NicSecondaryIpDao.class));
        fs=Mockito.mock(SharedFSVO.class);Mockito.when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);Mockito.when(fs.getIpAddress()).thenReturn(IP);Mockito.when(fs.getCidr()).thenReturn("10.10.0.0/16");
        SharedFSDao shared=Mockito.mock(SharedFSDao.class);Mockito.when(shared.findByVm(80L)).thenReturn(fs);set(manager,"sharedFSDao",shared);
        network=Mockito.mock(NetworkVO.class);NetworkDao networks=Mockito.mock(NetworkDao.class);Mockito.when(networks.findById(20L)).thenReturn(network);set(manager,"networkDao",networks);
        DataCenterVO zone=Mockito.mock(DataCenterVO.class);Mockito.when(zone.getGuestNetworkCidr()).thenReturn("10.1.1.0/24");DataCenterDao zones=Mockito.mock(DataCenterDao.class);Mockito.when(zones.findById(1L)).thenReturn(zone);set(manager,"dataCenterDao",zones);
    }
    private JsonObject source(int prefix){
        JsonObject primary=new JsonObject();primary.addProperty("ipAddress",IP);primary.addProperty("macAddress",MAC);primary.addProperty("cidr","10.10.0.0/"+prefix);
        JsonObject desired=new JsonObject();desired.add("sharedfs-network.json",primary);JsonObject source=new JsonObject();source.add("configurationDesiredState",desired);return source;
    }
    private JsonObject canonical(int prefix){
        JsonObject row=new JsonObject();row.addProperty("listenIp",IP);row.addProperty("primaryIp",IP);row.addProperty("macAddress",MAC);row.addProperty("prefixlen",prefix);
        JsonArray rows=new JsonArray();rows.add(row);JsonObject endpoints=new JsonObject();endpoints.add("endpoints",rows);JsonObject c=new JsonObject();c.add("network-endpoints.json",endpoints);return c;
    }
    @Test public void l2NullNetmaskUsesStaticDeclarationForAllFourPrimaryPorts(){
        for(String cidr:java.util.List.of("10.10.0.0/16","10.10.0.0/20","10.10.13.0/24")){
            Mockito.when(fs.getCidr()).thenReturn(cidr);
            for(int port:new int[]{2049,445,3260,4420}){
                JsonObject row=manager.payload(instance,nic,port);
                Assert.assertEquals(Integer.parseInt(cidr.split("/")[1]),row.get("prefixlen").getAsInt());Assert.assertEquals(IP,row.get("primaryIp").getAsString());
                Assert.assertEquals(cidr,row.get("networkCidr").getAsString());Assert.assertFalse(row.has("port"));
            }
        }
    }
    @Test public void staticPrimaryIsAvailableBeforeCloudNicIpSynchronization(){
        Mockito.when(nic.getIPv4Address()).thenReturn(null);Assert.assertEquals(IP,manager.payload(instance,nic,2049).get("primaryIp").getAsString());
    }
    @Test public void unknownPrefixIsRejectedInsteadOfAssuming24(){
        Mockito.when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);
        CloudRuntimeException e=Assert.assertThrows(CloudRuntimeException.class,()->manager.payload(instance,nic,2049));Assert.assertTrue(e.getMessage().contains("ENDPOINT_PREFIX_UNAVAILABLE"));
    }
    @Test public void observedNicMaskRemainsAuthoritativeForDhcp(){
        Mockito.when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);Mockito.when(nic.getIPv4Netmask()).thenReturn("255.255.240.0");
        Assert.assertEquals(20,manager.payload(instance,nic,445).get("prefixlen").getAsInt());
    }
    @Test public void invalidAndNoncontiguousMasksAreNotTurnedInto24(){
        for(String mask:java.util.List.of("invalid","255.0.255.0","255.255.255.1")){
            Mockito.when(nic.getIPv4Netmask()).thenReturn(mask);
            Assert.assertThrows(CloudRuntimeException.class,()->manager.payload(instance,nic,2049));
        }
    }
    @Test public void wildcardCandidateNeedsNoNetworkMutationOrInspection(){
        manager.preflight(instance,new JsonObject(),source(16));Assert.assertEquals(0,manager.calls);
    }
    @Test public void wrongPrefixFailsWithPublicValuesBeforeDispatch(){
        CloudRuntimeException e=Assert.assertThrows(CloudRuntimeException.class,()->manager.preflight(instance,canonical(24),source(16)));
        Assert.assertTrue(e.getMessage().contains("ENDPOINT_PREFIX_MISMATCH"));Assert.assertTrue(e.getMessage().contains("requestedPrefix=24; sourcePrefix=16"));Assert.assertEquals(0,manager.calls);
    }
    @Test public void matchingSourceUsesOnlyReadOnlyInspection(){
        manager.preflight(instance,canonical(16),source(16));Assert.assertEquals(1,manager.calls);Assert.assertEquals(16,manager.lastRequest.getAsJsonArray("expectedBindings").get(0).getAsJsonObject().get("prefixlen").getAsInt());
    }
    @Test public void macMismatchIsStillRejectedBeforeDispatch(){
        JsonObject c=canonical(16);Mockito.when(nic.getMacAddress()).thenReturn("02:0d:00:cc:00:ff");
        Assert.assertTrue(Assert.assertThrows(CloudRuntimeException.class,()->manager.preflight(instance,c,source(16))).getMessage().contains("ENDPOINT_MAC_MISMATCH"));Assert.assertEquals(0,manager.calls);
    }
    @Test public void anUnverifiedOrSideEffectingProofCannotAuthorizeStage(){
        for(String key:java.util.List.of("success","bindingsValidated","sideEffects")){
            JsonObject p=new JsonObject();p.addProperty("success",true);p.addProperty("bindingsValidated",true);p.addProperty("sideEffects",false);JsonArray rows=new JsonArray();JsonObject row=canonical(16).getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject();row.addProperty("active",true);rows.add(row);p.add("endpoints",rows);p.addProperty(key,key.equals("sideEffects"));manager.proof=p;
            Assert.assertThrows(CloudRuntimeException.class,()->manager.preflight(instance,canonical(16),source(16)));
        }
    }
    @Test public void primaryMustBeActiveAndReadBackAtTheExactPrefix(){
        for(int variant=0;variant<3;variant++){
            JsonObject row=canonical(16).getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject();
            row.addProperty("active",variant!=0);if(variant==1)row.addProperty("prefixlen",24);if(variant==2)row.addProperty("primaryIp","10.10.13.228");
            JsonArray rows=new JsonArray();rows.add(row);JsonObject p=new JsonObject();p.addProperty("success",true);p.addProperty("sideEffects",false);p.addProperty("bindingsValidated",true);p.add("endpoints",rows);manager.proof=p;
            Assert.assertThrows(CloudRuntimeException.class,()->manager.preflight(instance,canonical(16),source(16)));
        }
    }
}
