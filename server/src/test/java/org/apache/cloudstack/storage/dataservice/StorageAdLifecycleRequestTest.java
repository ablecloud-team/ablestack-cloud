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

import org.junit.Test;
import org.junit.Assert;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

public class StorageAdLifecycleRequestTest {
    private JsonArray addresses(){JsonArray a=new JsonArray();a.add("10.10.13.240");a.add("10.10.13.241");return a;}
    private JsonObject scope(){JsonObject s=new JsonObject();s.addProperty("instanceUuid","11111111-1111-1111-1111-111111111111");s.addProperty("operationUuid","22222222-2222-2222-2222-222222222222");s.addProperty("maintenanceUuid","22222222-2222-2222-2222-222222222222");s.addProperty("revision",3);return s;}
    @Test public void publicJoinPinsDomainDnsOwnedAliasesSpnsAndDisjointIdmapWithoutCredentials(){JsonObject config=StorageAdLifecycleRequest.publicJoin("example.test","EXAMPLE","STOR1111111111","10.10.13.79",addresses(),"JOIN_EXISTING",null);Assert.assertFalse(config.has("username"));Assert.assertFalse(config.has("password"));Assert.assertEquals("EXAMPLE.TEST",config.get("realm").getAsString());Assert.assertEquals("STOR1111111111",config.get("netbiosName").getAsString());Assert.assertEquals(2,config.getAsJsonArray("servicePrincipals").size());Assert.assertFalse(config.getAsJsonArray("servicePrincipals").get(0).getAsString().contains("@"));Assert.assertEquals(StorageAdLifecycleRequest.defaultsIdmap(),config.get("idmapPolicy"));}
    @Test public void protectedJoinContainsSameOperationServiceScopeAndOnlyRuntimeCredentials(){JsonObject config=StorageAdLifecycleRequest.publicJoin("example.test","EXAMPLE","STOR1111111111","10.10.13.79",addresses(),"JOIN_EXISTING",null);JsonObject request=StorageAdLifecycleRequest.join(scope(),config,"EXAMPLE\\operator","synthetic-test-password",null);Assert.assertEquals(scope().get("operationUuid"),request.get("maintenanceUuid"));Assert.assertTrue(request.has("password"));Assert.assertFalse(config.has("password"));JsonObject foreign=scope();foreign.addProperty("maintenanceUuid","33333333-3333-3333-3333-333333333333");Assert.assertThrows(RuntimeException.class,()->StorageAdLifecycleRequest.join(foreign,config,"operator","test",null));}
    @Test public void freshCloneRejectsSourceComputerAliasAndInvalidDnsBeforeCredentials(){JsonObject source=StorageAdLifecycleRequest.publicJoin("example.test","EXAMPLE","AST111111111111","10.10.13.79",addresses(),"NEW_INSTANCE",null);source.addProperty("domain","example.test");Assert.assertThrows(RuntimeException.class,()->StorageAdLifecycleRequest.publicJoin("example.test","EXAMPLE","AST111111111111","10.10.13.79",addresses(),"NEW_INSTANCE",source));Assert.assertThrows(RuntimeException.class,()->StorageAdLifecycleRequest.publicJoin("example.test","EXAMPLE","STOR1111111111","foreign-dns",addresses(),"JOIN_EXISTING",null));}
    @Test public void nativeIdmapProducerMatchesDefaultCanonicalPolicy() throws Exception {java.nio.file.Path root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!java.nio.file.Files.exists(root.resolve("systemvm/debian/usr/local/lib/ablestack-storage/ad_identity.py")))root=root.getParent();String script="import sys,json;sys.path.insert(0,sys.argv[1]);import ad_identity;print(json.dumps(ad_identity.ad_idmap_configuration('EXAMPLE')['policy']))";Process process=new ProcessBuilder("python3","-c",script,root.resolve("systemvm/debian/usr/local/lib/ablestack-storage").toString()).redirectErrorStream(true).start();String text=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);Assert.assertEquals(text,0,process.waitFor());Assert.assertEquals(com.google.gson.JsonParser.parseString(text),StorageAdLifecycleRequest.defaultsIdmap());}
}
