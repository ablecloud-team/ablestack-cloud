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

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public class StorageSmbIdentityRepairProofTest {
    static JsonObject scope() {JsonObject value=new JsonObject();value.addProperty("instanceUuid","instance");value.addProperty("operationUuid","repair");value.addProperty("revision",9);return value;}
    static JsonObject inspection() {
        JsonObject value=new JsonObject();value.addProperty("success",true);value.addProperty("smbIdentitySupported",true);value.add("scope",scope());value.addProperty("ownershipVerified",true);value.addProperty("identityDatabaseAligned",false);value.addProperty("identityRestoreSafe",false);value.addProperty("bootId","same-boot");value.addProperty("configurationSha256","a".repeat(64));
        JsonObject generation=new JsonObject();generation.addProperty("instanceUuid","instance");generation.addProperty("revision",9);generation.addProperty("configurationSha256","b".repeat(64));value.add("generation",generation);
        JsonObject databases=new JsonObject();for(String name:new String[]{"PASSDB","SECRETS"}){JsonObject file=new JsonObject();file.addProperty("path","/protected/"+name);file.addProperty("present",true);file.addProperty("device",1);file.addProperty("inode",name.equals("PASSDB")?100:101);file.addProperty("uid",0);file.addProperty("gid",0);file.addProperty("mode","0600");databases.add(name,file);}value.add("databases",databases);
        JsonObject master=new JsonObject();master.addProperty("pid",1291);master.addProperty("startTicks",10000);master.addProperty("unit","smbd.service");master.addProperty("lockingDatabasesAligned",true);JsonArray masters=new JsonArray();masters.add(master);value.add("masters",masters);
        JsonArray endpoints=new JsonArray();for(String ip:new String[]{"10.10.1.1","10.10.1.2"}){JsonObject endpoint=new JsonObject();endpoint.addProperty("listenIp",ip);endpoint.addProperty("port",445);endpoint.addProperty("listenerOwned",true);endpoint.addProperty("listening",true);endpoint.addProperty("tcpReady",true);endpoints.add(endpoint);}value.add("ownedEndpoints",endpoints);
        JsonObject sessions=new JsonObject();sessions.addProperty("available",true);sessions.addProperty("lockingDatabasesAligned",true);sessions.addProperty("safeToRebind",true);for(String count:new String[]{"establishedTcpCount","synRecvTcpCount","smbSessionCount","treeConnectionCount","openFileCount","byteLockOpenFileCount"})sessions.addProperty(count,0);value.add("sessions",sessions);return value;
    }
    static JsonObject result() {JsonObject value=new JsonObject();value.addProperty("success",true);value.add("scope",scope());value.addProperty("rebound",true);value.addProperty("databasesUnchanged",true);value.addProperty("configurationUnchanged",true);value.addProperty("generationAdvanced",false);return value;}
    @Test public void staleAuthenticationDescriptorsCanBeReboundOnlyWithIndependentZeroSessionProof() {StorageSmbIdentityRepairProof.requirePreflight(scope(),inspection());}
    @Test public void socketConnectionsUnavailableSessionsAndDeletedLockingDatabasesBlockBeforeRestart() {
        for(String count:new String[]{"establishedTcpCount","synRecvTcpCount","smbSessionCount","treeConnectionCount","openFileCount","byteLockOpenFileCount"}){JsonObject value=inspection();value.getAsJsonObject("sessions").addProperty(count,1);Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requirePreflight(scope(),value));}
        JsonObject unavailable=inspection();unavailable.getAsJsonObject("sessions").addProperty("available",false);Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requirePreflight(scope(),unavailable));
        JsonObject locking=inspection();locking.getAsJsonObject("sessions").addProperty("lockingDatabasesAligned",false);Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requirePreflight(scope(),locking));
    }
    @Test public void alignedRebindMustPreserveExactDatabaseGenerationAndBootIdentities() {
        JsonObject expected=inspection(),current=inspection();current.addProperty("identityDatabaseAligned",true);StorageSmbIdentityRepairProof.requireVerified(expected,result(),current);
        current.getAsJsonObject("databases").getAsJsonObject("PASSDB").addProperty("inode",999);Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requireVerified(expected,result(),current));
        JsonObject changed=inspection();changed.addProperty("identityDatabaseAligned",true);changed.getAsJsonObject("generation").addProperty("revision",10);Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requireVerified(expected,result(),changed));
    }
    @Test public void restoreSafetyMustBeEstablishedBeforeDatabaseSnapshotRollback() {
        Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requireRestoreSafe(scope(),inspection()));
        JsonObject quiesced=inspection();quiesced.addProperty("identityRestoreSafe",true);StorageSmbIdentityRepairProof.requireRestoreSafe(scope(),quiesced);
        quiesced.getAsJsonObject("scope").addProperty("operationUuid","foreign");Assert.assertThrows(CloudRuntimeException.class,()->StorageSmbIdentityRepairProof.requireRestoreSafe(scope(),quiesced));
    }
}
