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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.cloud.storage.VMTemplateVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A protected, single-fixture creation authorization; template detail strings cannot grant access. */
public final class StorageTemplateFixturePermit {
    private final Path root;
    private final StorageConfigArtifactStore artifacts;
    public StorageTemplateFixturePermit() {
        this(Path.of(System.getProperty("cloudstack.storage.template.fixture.path", "/var/lib/cloudstack-management/storage-template-fixtures")));
    }
    public StorageTemplateFixturePermit(Path root) {
        this.root=root;this.artifacts=new StorageConfigArtifactStore(root);
    }
    private static String text(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new CloudRuntimeException("Fixture string field is invalid: "+key);
        return value.getAsString();
    }
    private static long number(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]+"))throw new CloudRuntimeException("Fixture integer field is invalid: "+key);
        try{return Long.parseLong(value.getAsString());}catch(NumberFormatException invalid){throw new CloudRuntimeException("Fixture integer exceeds range",invalid);}
    }
    private static void yes(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean()||!value.getAsBoolean())throw new CloudRuntimeException("Fixture boolean proof is invalid: "+key);
    }
    public static String detailsSha256(VMTemplateVO template) {
        JsonObject details=new JsonObject();new java.util.TreeMap<>(template.getDetails()).forEach(details::addProperty);
        return StorageConfigArchive.sha256(details.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static void verify(JsonObject artifact,JsonObject request,Set<String> existingInstances,long now) {
        if(number(artifact,"schemaVersion")!=1||!"NEW_SPARSE_PRECREATE".equals(text(artifact,"kind")))throw new CloudRuntimeException("Unsupported private template fixture authorization");
        yes(artifact,"newDisposableFixture");yes(artifact,"originalDataExcluded");yes(artifact,"newDataWithoutBacking");
        long expiry=number(artifact,"expiresAtMillis");if(expiry<=now||expiry>now+86400000L)throw new CloudRuntimeException("Private template fixture authorization expired");
        for(String key:Set.of("sourceCommit","expectedCliSha256"))if(!text(artifact,key).matches(key.equals("sourceCommit")?"[a-f0-9]{40}":"[a-f0-9]{64}"))throw new CloudRuntimeException("Private template source pin is invalid");
        if(!artifact.has("request")||!artifact.get("request").equals(request))throw new CloudRuntimeException("Private template fixture request binding changed");
        if(!artifact.has("excludedInstanceUuids")||!artifact.get("excludedInstanceUuids").isJsonArray())throw new CloudRuntimeException("Private template original exclusions are unavailable");
        Set<String> excluded=new HashSet<>();for(JsonElement value:artifact.getAsJsonArray("excludedInstanceUuids")) {
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!value.getAsString().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!excluded.add(value.getAsString()))throw new CloudRuntimeException("Private template exclusion set is invalid");
        }
        if(excluded.size()<7||!excluded.containsAll(existingInstances))throw new CloudRuntimeException("Private template fixture must exclude every existing instance");
        for(String key:Set.of("rootProvisioningType","dataProvisioningType"))if(!Set.of("SPARSE","FAT").contains(text(request,key)))throw new CloudRuntimeException("Private template fixture requires SPARSE/FAT disks");
        if(!"NEW".equals(text(request,"backingVolumeMode")))throw new CloudRuntimeException("Private template fixture cannot reuse existing DATA");
    }
    public static void requireAllocatedDisk(JsonObject expected,JsonObject observed) {
        for(String key:Set.of("volumeUuid","path","poolId","type","accountId","zoneId","sizeBytes","provisioningType","templateId")) {
            if(!expected.has(key)||!observed.has(key)||!expected.get(key).equals(observed.get(key)))throw new CloudRuntimeException("Private fixture allocation changed: "+key);
        }
        if(!Set.of("SPARSE","FAT").contains(text(observed,"provisioningType"))||!"Ready".equals(text(observed,"state")))throw new CloudRuntimeException("Private fixture allocated disk is not Ready SPARSE/FAT");
        yes(observed,"attachedToFixture");yes(observed,"notRemoved");
        if("DATADISK".equals(text(observed,"type")))yes(observed,"newDataWithoutBacking");
    }
    public static void verifyRootTarget(JsonObject artifact,JsonObject request,Set<String> existingInstances,long now) {
        if(number(artifact,"schemaVersion")!=1||!"NEW_SPARSE_ROOT_TARGET".equals(text(artifact,"kind")))throw new CloudRuntimeException("Unsupported private ROOT target authorization");
        JsonObject creation=artifact.deepCopy();creation.addProperty("kind","NEW_SPARSE_PRECREATE");verify(creation,request,existingInstances,now);
        for(String field:Set.of("sharedFsUuid","instanceUuid","vmUuid","sourceRootVolumeUuid","sourceTemplateUuid","targetTemplateUuid","targetTemplateChecksum","targetTemplateDetailsSha256","sourceConfigurationSha256","rootDiskOfferingUuid"))if(text(request,field).isBlank())throw new CloudRuntimeException("Private ROOT target scope is incomplete");
        if(!text(artifact,"expectedCliSha256").equals(text(request,"expectedCliSha256"))||!text(artifact,"sourceCommit").equals(text(request,"targetSourceCommit")))throw new CloudRuntimeException("Private ROOT target signed CLI or template source pin changed");
        if(number(request,"desiredRevision")<0||!text(request,"sourceConfigurationSha256").matches("[a-f0-9]{64}"))throw new CloudRuntimeException("Private ROOT target source generation is invalid");
    }
    public void claimRootTarget(String artifactUuid,String sha256,String upgradeUuid,JsonObject request) {
        JsonObject claim=new JsonObject();claim.addProperty("artifactUuid",artifactUuid);claim.addProperty("artifactSha256",sha256);claim.addProperty("templateUpgradeUuid",upgradeUuid);claim.add("request",request.deepCopy());immutable(UUID.nameUUIDFromBytes(("root-target-claim:"+artifactUuid).getBytes(StandardCharsets.UTF_8)).toString(),claim);
    }
    public void requireRootTargetClaim(String artifactUuid,String sha256,String upgradeUuid) {
        JsonObject claim=readProtected(UUID.nameUUIDFromBytes(("root-target-claim:"+artifactUuid).getBytes(StandardCharsets.UTF_8)).toString());
        if(!artifactUuid.equals(text(claim,"artifactUuid"))||!sha256.equals(text(claim,"artifactSha256"))||!upgradeUuid.equals(text(claim,"templateUpgradeUuid")))throw new CloudRuntimeException("Private ROOT target approval belongs to another transaction");
    }
    public JsonObject approveRootTarget(String artifactUuid,String sha256,JsonObject request,Set<String> existingInstances) {
        JsonObject artifact=JsonParser.parseString(new String(artifacts.read(artifactUuid,sha256),StandardCharsets.UTF_8)).getAsJsonObject();verifyRootTarget(artifact,request,existingInstances,System.currentTimeMillis());return artifact;
    }
    public JsonObject approve(String artifactUuid,String sha256,JsonObject request,Set<String> existingInstances) {
        JsonObject artifact=JsonParser.parseString(new String(artifacts.read(artifactUuid,sha256),StandardCharsets.UTF_8)).getAsJsonObject();
        verify(artifact,request,existingInstances,System.currentTimeMillis());return artifact;
    }
    private static String claimUuid(String artifactUuid) {return UUID.nameUUIDFromBytes(("template-fixture-claim:"+artifactUuid).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String receiptUuid(String sharedUuid) {return UUID.nameUUIDFromBytes(("template-fixture-receipt:"+sharedUuid).getBytes(StandardCharsets.UTF_8)).toString();}
    private JsonObject readProtected(String uuid) {
        Path path=root.resolve(uuid+".zip");
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new CloudRuntimeException("Private template fixture receipt is unavailable");
            String sha=StorageConfigArchive.sha256(Files.readAllBytes(path));return JsonParser.parseString(new String(artifacts.read(uuid,sha),StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException failure){throw new CloudRuntimeException("Private template fixture receipt is unavailable",failure);}
    }
    private void immutable(String uuid,JsonObject value) {
        if(Files.exists(root.resolve(uuid+".zip"),LinkOption.NOFOLLOW_LINKS)) {if(!readProtected(uuid).equals(value))throw new CloudRuntimeException("Private template fixture approval was already consumed");}
        else artifacts.write(uuid,value.toString().getBytes(StandardCharsets.UTF_8));
    }
    public void claim(String artifactUuid,String sha256,String sharedUuid,JsonObject request) {
        JsonObject claim=new JsonObject();claim.addProperty("artifactUuid",artifactUuid);claim.addProperty("artifactSha256",sha256);claim.addProperty("sharedFsUuid",sharedUuid);claim.add("request",request.deepCopy());
        immutable(claimUuid(artifactUuid),claim);
    }
    public void complete(String artifactUuid,String sha256,String sharedUuid,JsonObject request,JsonObject bindings) {
        claim(artifactUuid,sha256,sharedUuid,request);
        JsonObject receipt=new JsonObject();receipt.addProperty("artifactUuid",artifactUuid);receipt.addProperty("artifactSha256",sha256);receipt.addProperty("sharedFsUuid",sharedUuid);receipt.add("request",request.deepCopy());receipt.add("bindings",bindings.deepCopy());
        immutable(receiptUuid(sharedUuid),receipt);
    }
    public JsonObject createdReceipt(String sharedUuid) {return readProtected(receiptUuid(sharedUuid));}
    public JsonObject requireCreated(String sharedUuid,long vmId,String vmUuid,long accountId,long zoneId,VMTemplateVO target,Set<String> existingInstances) {
        JsonObject receipt=readProtected(receiptUuid(sharedUuid)),request=receipt.getAsJsonObject("request"),bindings=receipt.getAsJsonObject("bindings");
        approve(text(receipt,"artifactUuid"),text(receipt,"artifactSha256"),request,existingInstances);
        if(!sharedUuid.equals(text(receipt,"sharedFsUuid"))||number(bindings,"vmId")!=vmId||!vmUuid.equals(text(bindings,"vmUuid"))||number(request,"accountId")!=accountId||number(request,"zoneId")!=zoneId
                ||!target.getUuid().equals(text(request,"templateUuid"))||!java.util.Objects.equals(target.getChecksum(),text(request,"templateChecksum"))||!detailsSha256(target).equals(text(request,"templateDetailsSha256")))throw new CloudRuntimeException("Private template fixture creation binding changed");
        JsonObject claim=readProtected(claimUuid(text(receipt,"artifactUuid")));if(!sharedUuid.equals(text(claim,"sharedFsUuid")))throw new CloudRuntimeException("Private template fixture claim changed");return receipt;
    }
}
