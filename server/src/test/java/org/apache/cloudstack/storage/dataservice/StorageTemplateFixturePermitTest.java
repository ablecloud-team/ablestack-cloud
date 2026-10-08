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

import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.storage.VMTemplateVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

public class StorageTemplateFixturePermitTest {
    private final Set<String> excluded=Set.of("11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222","33333333-3333-3333-3333-333333333333","44444444-4444-4444-4444-444444444444","55555555-5555-5555-5555-555555555555","66666666-6666-6666-6666-666666666666","77777777-7777-7777-7777-777777777777");
    private JsonObject request() {
        JsonObject r=new JsonObject();r.addProperty("name","disposable");r.addProperty("accountId",2);r.addProperty("zoneId",3);r.addProperty("templateUuid","template");r.addProperty("templateChecksum","checksum");r.addProperty("templateDetailsSha256",StorageConfigArchive.sha256("{}".getBytes(StandardCharsets.UTF_8)));r.addProperty("rootProvisioningType","SPARSE");r.addProperty("dataProvisioningType","SPARSE");r.addProperty("backingVolumeMode","NEW");return r;
    }
    private JsonObject artifact(JsonObject r) {
        JsonObject a=new JsonObject();a.addProperty("schemaVersion",1);a.addProperty("kind","NEW_SPARSE_PRECREATE");a.addProperty("newDisposableFixture",true);a.addProperty("originalDataExcluded",true);a.addProperty("newDataWithoutBacking",true);a.addProperty("expiresAtMillis",System.currentTimeMillis()+600000);a.addProperty("sourceCommit","a".repeat(40));a.addProperty("expectedCliSha256","b".repeat(64));a.add("request",r.deepCopy());JsonArray ids=new JsonArray();excluded.forEach(ids::add);a.add("excludedInstanceUuids",ids);return a;
    }
    @Test public void strictLiteralNumbersBooleansUniqueExclusionsAndExactRequestAreMandatory() {
        JsonObject r=request(),a=artifact(r);StorageTemplateFixturePermit.verify(a,r,excluded,System.currentTimeMillis());
        for(String field:Set.of("schemaVersion","expiresAtMillis","newDisposableFixture")) {JsonObject wrong=a.deepCopy();wrong.addProperty(field,a.get(field).getAsString());Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.verify(wrong,r,excluded,System.currentTimeMillis()));}
        JsonObject duplicate=a.deepCopy();duplicate.getAsJsonArray("excludedInstanceUuids").add(excluded.iterator().next());Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.verify(duplicate,r,excluded,System.currentTimeMillis()));
        JsonObject changed=r.deepCopy();changed.addProperty("name","foreign");Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.verify(a,changed,excluded,System.currentTimeMillis()));
        Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.verify(a,r,Set.of("88888888-8888-8888-8888-888888888888"),System.currentTimeMillis()));
    }
    @Test public void thinAndExistingDataAreRejectedEvenWhenTheProtectedRequestMatches() {
        for(String field:Set.of("rootProvisioningType","dataProvisioningType","backingVolumeMode")) {JsonObject r=request();r.addProperty(field,field.equals("backingVolumeMode")?"EXISTING":"THIN");Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.verify(artifact(r),r,excluded,System.currentTimeMillis()));}
    }
    @Test public void immutableArtifactClaimPreventsSecondFixtureAndCreatedReceiptBindsVm() throws Exception {
        Path root=Files.createTempDirectory("template-fixture-");root.toFile().setReadable(false,false);root.toFile().setWritable(false,false);root.toFile().setExecutable(false,false);root.toFile().setReadable(true,true);root.toFile().setWritable(true,true);root.toFile().setExecutable(true,true);
        StorageTemplateFixturePermit store=new StorageTemplateFixturePermit(root);String id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",shared="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";JsonObject r=request(),a=artifact(r);byte[] bytes=a.toString().getBytes(StandardCharsets.UTF_8);String sha=StorageConfigArchive.sha256(bytes);new StorageConfigArtifactStore(root).write(id,bytes);
        store.approve(id,sha,r,excluded);store.claim(id,sha,shared,r);store.claim(id,sha,shared,r);Assert.assertThrows(RuntimeException.class,()->store.claim(id,sha,"cccccccc-cccc-cccc-cccc-cccccccccccc",r));
        JsonObject b=new JsonObject();b.addProperty("vmId",4);b.addProperty("vmUuid","vm");store.complete(id,sha,shared,r,b);VMTemplateVO t=Mockito.mock(VMTemplateVO.class);Mockito.when(t.getUuid()).thenReturn("template");Mockito.when(t.getChecksum()).thenReturn("checksum");Mockito.when(t.getDetails()).thenReturn(java.util.Map.of());
        store.requireCreated(shared,4,"vm",2,3,t,excluded);Assert.assertThrows(RuntimeException.class,()->store.requireCreated(shared,5,"vm",2,3,t,excluded));Assert.assertThrows(RuntimeException.class,()->store.requireCreated(shared,4,"foreign",2,3,t,excluded));
    }
    @Test public void concurrentDifferentFixturesCannotOverwriteTheFirstClaim() throws Exception {
        Path root=Files.createTempDirectory("concurrent-template-claim-");Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));StorageTemplateFixturePermit store=new StorageTemplateFixturePermit(root);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger success=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.ExecutorService threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {java.util.List<java.util.concurrent.Future<?>> results=new java.util.ArrayList<>();for(String shared:java.util.List.of("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))results.add(threads.submit(()->{try{start.await();store.claim("cccccccc-cccc-cccc-cccc-cccccccccccc","d".repeat(64),shared,request());success.incrementAndGet();}catch(RuntimeException rejected){ }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}));start.countDown();for(java.util.concurrent.Future<?> result:results)result.get();Assert.assertEquals(1,success.get());}finally{threads.shutdownNow();}
    }
    @Test public void allocatedDataMustMatchApprovedTypeProvisioningSizePoolAndFreshOwnership() {
        JsonObject disk=new JsonObject();disk.addProperty("volumeUuid","disk");disk.addProperty("path","physical");disk.addProperty("poolId",1);disk.addProperty("type","DATADISK");disk.addProperty("accountId",2);disk.addProperty("zoneId",3);disk.addProperty("sizeBytes",20L<<30);disk.addProperty("provisioningType","SPARSE");disk.add("templateId",com.google.gson.JsonNull.INSTANCE);disk.addProperty("state","Ready");disk.addProperty("attachedToFixture",true);disk.addProperty("notRemoved",true);disk.addProperty("newDataWithoutBacking",true);
        StorageTemplateFixturePermit.requireAllocatedDisk(disk,disk.deepCopy());
        for(String key:Set.of("poolId","type","accountId","zoneId","sizeBytes","provisioningType","path","templateId")) {JsonObject changed=disk.deepCopy();changed.addProperty(key,"foreign");Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.requireAllocatedDisk(disk,changed));}
        for(String key:Set.of("attachedToFixture","notRemoved","newDataWithoutBacking")) {JsonObject changed=disk.deepCopy();changed.addProperty(key,false);Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.requireAllocatedDisk(disk,changed));}
        JsonObject changed=disk.deepCopy();changed.addProperty("state","Allocated");Assert.assertThrows(RuntimeException.class,()->StorageTemplateFixturePermit.requireAllocatedDisk(disk,changed));
    }
}
