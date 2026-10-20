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

import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class StorageConfigListenerPreflightTest {
    @Test public void primaryUsesOnlyExactlyOneDeclaredDefaultNicAndNoGuestGuess() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();NicDao nics=Mockito.mock(NicDao.class);
        ReflectionTestUtils.setField(manager,"nicDao",nics);StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getVmId()).thenReturn(48L);NicVO primary=Mockito.mock(NicVO.class);Mockito.when(primary.isDefaultNic()).thenReturn(true);
        Mockito.when(primary.getIPv4Address()).thenReturn("10.10.13.224");Mockito.when(nics.listByVmId(48L)).thenReturn(List.of(primary));
        Assert.assertEquals("10.10.13.224",manager.configurationPrimaryAddress(instance));
        Mockito.when(nics.listByVmId(48L)).thenReturn(List.of());Assert.assertThrows(InvalidParameterValueException.class,()->manager.configurationPrimaryAddress(instance));
        Mockito.when(nics.listByVmId(48L)).thenReturn(List.of(primary,primary));Assert.assertThrows(InvalidParameterValueException.class,()->manager.configurationPrimaryAddress(instance));
        Mockito.when(nics.listByVmId(48L)).thenReturn(List.of(primary));Mockito.when(primary.getIPv4Address()).thenReturn(null);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.configurationPrimaryAddress(instance));
    }
    private static JsonObject plan(boolean create,String address) {
        JsonObject plan=new JsonObject();plan.addProperty("targetMode",create?"CREATE_NEW":"RESTORE_EXISTING");
        plan.add("create",new JsonArray());plan.add("preserve",new JsonArray());
        JsonObject mappings=new JsonObject();mappings.addProperty("10.10.13.223",address);plan.add("listenerAddressMappings",mappings);plan.add("blockers",new JsonArray());
        if(create){JsonObject blueprint=new JsonObject();blueprint.addProperty("networkid","network");blueprint.addProperty("networkmode","STATIC");blueprint.addProperty("ipcidr","10.10.13.224/24");plan.add("createNew",blueprint);}
        return plan;
    }
    @Test public void existingLiteralAndPrimaryUseReadonlyNormalListenerValidator() {
        java.util.List<String> requested=new java.util.ArrayList<>();
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl() {
            @Override protected String configurationPrimaryAddress(StorageServiceInstanceVO target){return "10.10.13.224";}
            @Override protected NicVO resolveProtocolListenAddress(StorageServiceInstanceVO target,String address) {
                requested.add(address);if("10.10.13.225".equals(address))throw new InvalidParameterValueException("collision");return null;
            }
        };
        StorageServiceInstanceVO target=Mockito.mock(StorageServiceInstanceVO.class);JsonObject primary=plan(false,"PRIMARY");
        manager.preflightConfigurationListenerMappings(target,primary);Assert.assertEquals(List.of("10.10.13.224"),requested);Assert.assertEquals(0,primary.getAsJsonArray("blockers").size());
        JsonObject conflict=plan(false,"10.10.13.225");manager.preflightConfigurationListenerMappings(target,conflict);
        Assert.assertEquals("LISTENER_MAPPING_CONFLICT:10.10.13.223",conflict.getAsJsonArray("blockers").get(0).getAsString());
    }
    @Test public void newStaticLiteralConflictAndCidrAreBlockedBeforeAnyNicAllocation() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();NetworkDao networks=Mockito.mock(NetworkDao.class);
        NicDao nics=Mockito.mock(NicDao.class);NicSecondaryIpDao secondary=Mockito.mock(NicSecondaryIpDao.class);
        NetworkVO network=Mockito.mock(NetworkVO.class);Mockito.when(network.getId()).thenReturn(9L);Mockito.when(networks.findByUuid("network")).thenReturn(network);
        ReflectionTestUtils.setField(manager,"networkDao",networks);ReflectionTestUtils.setField(manager,"nicDao",nics);ReflectionTestUtils.setField(manager,"nicSecondaryIpDao",secondary);
        StorageServiceInstanceVO target=Mockito.mock(StorageServiceInstanceVO.class);JsonObject primary=plan(true,"PRIMARY");
        manager.preflightConfigurationListenerMappings(target,primary);Assert.assertEquals(0,primary.getAsJsonArray("blockers").size());
        Mockito.when(nics.findByIp4AddressAndNetworkId("10.10.13.225",9L)).thenReturn(Mockito.mock(NicVO.class));
        JsonObject collision=plan(true,"10.10.13.225");manager.preflightConfigurationListenerMappings(target,collision);Assert.assertEquals(1,collision.getAsJsonArray("blockers").size());
        JsonObject outside=plan(true,"10.10.99.1");manager.preflightConfigurationListenerMappings(target,outside);Assert.assertEquals(1,outside.getAsJsonArray("blockers").size());
        Mockito.verify(nics,Mockito.never()).persist(Mockito.any());Mockito.verify(secondary,Mockito.never()).persist(Mockito.any());
    }

    @Test public void reviewedPrimaryDriftRejectsBeforeListenerRegistrationOrDomainApply() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();NicDao nics=Mockito.mock(NicDao.class);
        ReflectionTestUtils.setField(manager,"nicDao",nics);StorageServiceInstanceVO target=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(target.getVmId()).thenReturn(48L);
        NicVO nic=Mockito.mock(NicVO.class);Mockito.when(nic.isDefaultNic()).thenReturn(true);Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.224");
        Mockito.when(nics.listByVmId(48L)).thenReturn(List.of(nic));
        JsonObject plan=new JsonObject(),requirement=new JsonObject();requirement.addProperty("target","PRIMARY");requirement.addProperty("resolvedTarget","10.10.13.224");
        JsonArray rows=new JsonArray();rows.add(requirement);plan.add("listenerAddressRequirements",rows);
        manager.requireConfigurationResolvedPrimary(target,plan);
        Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.225");Mockito.clearInvocations(nics);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireConfigurationResolvedPrimary(target,plan));
        Mockito.verify(nics,Mockito.times(1)).listByVmId(48L);Mockito.verifyNoMoreInteractions(nics);
    }

    @Test public void existingProtocolTupleRequiresReviewedMappingBeforeAnyListenerOrDomainEffect() {
        java.util.Map<String,byte[]> source=new java.util.LinkedHashMap<>(),target=new java.util.LinkedHashMap<>();
        for(String kind:StorageConfigRestorePlan.ROW_KEYS.keySet()) {
            source.put("desired/"+kind+".json","[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            target.put("desired/"+kind+".json","[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        source.put("desired/volumes.json","[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sourceId="11111111-1111-4111-8111-111111111111",targetId="22222222-2222-4222-8222-222222222222";
        source.put("desired/instance.json",("{\"uuid\":\""+sourceId+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String original="{\"uuid\":\""+sourceId+"\",\"protocol\":\"NFS\",\"enabled\":true,\"listen_ip\":null,\"port\":2049,\"config\":{}}";
        String existing=original.replace(sourceId,targetId).replace("null","\"0.0.0.0\"");
        source.put("desired/protocols.json",("["+original+"]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        target.put("desired/protocols.json",("["+existing+"]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();JsonObject mappings=new JsonObject();
        JsonObject collision=StorageConfigRestorePlan.build(source,target,mappings,"RESTORE_EXISTING",targetId,1);
        manager.preflightConfigurationListenerMappings(null,collision);manager.preflightConfigurationListenerMappings(null,collision);
        Assert.assertEquals(1,collision.getAsJsonArray("blockers").size());
        Assert.assertEquals("PROTOCOL_MAPPING_REQUIRED:"+sourceId,collision.getAsJsonArray("blockers").get(0).getAsString());
        JsonObject protocols=new JsonObject();protocols.addProperty(sourceId,targetId);mappings.add("protocols",protocols);
        JsonObject mapped=StorageConfigRestorePlan.build(source,target,mappings,"RESTORE_EXISTING",targetId,1);
        manager.preflightConfigurationListenerMappings(null,mapped);Assert.assertEquals(0,mapped.getAsJsonArray("blockers").size());
        Assert.assertEquals(targetId,mapped.getAsJsonObject("resourceMappings").getAsJsonObject("protocols").get(sourceId).getAsString());
        Assert.assertEquals(0,mapped.getAsJsonArray("preserve").size());
        target.put("desired/protocols.json",("["+existing.replace("2049","2050")+"]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonObject separate=StorageConfigRestorePlan.build(source,target,new JsonObject(),"RESTORE_EXISTING",targetId,1);
        manager.preflightConfigurationListenerMappings(null,separate);Assert.assertEquals(0,separate.getAsJsonArray("blockers").size());
        Assert.assertEquals(1,separate.getAsJsonArray("create").size());Assert.assertEquals(1,separate.getAsJsonArray("preserve").size());
    }

}
