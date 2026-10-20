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

import org.junit.Assert;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

public class StorageConfigRenderedListenerPortsTest {
    final StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
    JsonObject payload(StorageServiceInstance.Protocol type,String requested,String group){
        JsonObject row=new JsonObject(),config=new JsonObject(),result=new JsonObject();row.addProperty("listenerPorts",requested);
        config.add("listenerGroupPorts",JsonParser.parseString(group));row.add("config",config);
        JsonArray listeners=new JsonArray();for(int port:new int[]{3260,3261,4420}){JsonObject endpoint=new JsonObject();endpoint.addProperty("listenIp","0.0.0.0");endpoint.addProperty("port",port);listeners.add(endpoint);}result.add("listeners",listeners);
        JsonArray members=new JsonArray();members.add(row);
        if(type==StorageServiceInstance.Protocol.ISCSI)result.add("targets",members);
        else{JsonObject subsystem=new JsonObject();subsystem.add("namespaces",members);JsonArray subsystems=new JsonArray();subsystems.add(subsystem);result.add("subsystems",subsystems);}
        return result;
    }
    JsonObject member(JsonObject value,StorageServiceInstance.Protocol type){
        return type==StorageServiceInstance.Protocol.ISCSI?value.getAsJsonArray("targets").get(0).getAsJsonObject():value.getAsJsonArray("subsystems").get(0).getAsJsonObject().getAsJsonArray("namespaces").get(0).getAsJsonObject();
    }
    @Test public void rendererGetsIntegerArraysWhileLegacyAndPublicCsvRemainsUnchanged(){
        for(StorageServiceInstance.Protocol type:new StorageServiceInstance.Protocol[]{StorageServiceInstance.Protocol.ISCSI,StorageServiceInstance.Protocol.NVME_OF}){
            String csv=type==StorageServiceInstance.Protocol.ISCSI?"3260,3261":"4420",array=type==StorageServiceInstance.Protocol.ISCSI?"[3260,3261]":"[4420]";
            JsonObject legacy=payload(type,csv,array),candidate=legacy.deepCopy();
            manager.normalizeRenderedBlockListenerPorts(type,candidate);
            Assert.assertEquals(JsonParser.parseString(array),member(candidate,type).get("listenerPorts"));
            Assert.assertEquals(csv,member(legacy,type).get("listenerPorts").getAsString());
            Assert.assertEquals(csv,manager.listenerPortsAsString(member(candidate,type).getAsJsonObject("config")));
            manager.normalizeRenderedBlockListenerPorts(type,candidate);Assert.assertEquals(JsonParser.parseString(array),member(candidate,type).get("listenerPorts"));
        }
    }
    @Test public void malformedWrongTypedOrUnavailablePortsAreRejectedBeforeNativeStage(){
        for(String[] values:new String[][]{{"bad","[3260]"},{"65536","[65536]"},{"3260","[\"3260\"]"},{"3269","[3269]"},{"3260","[3261]"},{"3260,3261","[3260]"},{"3260","[3260,3260]"}}){
            JsonObject candidate=payload(StorageServiceInstance.Protocol.ISCSI,values[0],values[1]);
            Assert.assertThrows(RuntimeException.class,()->manager.normalizeRenderedBlockListenerPorts(StorageServiceInstance.Protocol.ISCSI,candidate));
        }
    }
    @Test public void absentTargetGroupRetainsTheNormalAllDeclaredListenersMeaning(){
        JsonObject candidate=payload(StorageServiceInstance.Protocol.ISCSI,"3260","[]"),row=member(candidate,StorageServiceInstance.Protocol.ISCSI);
        row.remove("listenerPorts");row.getAsJsonObject("config").remove("listenerGroupPorts");
        manager.normalizeRenderedBlockListenerPorts(StorageServiceInstance.Protocol.ISCSI,candidate);Assert.assertFalse(row.has("listenerPorts"));
    }
}
