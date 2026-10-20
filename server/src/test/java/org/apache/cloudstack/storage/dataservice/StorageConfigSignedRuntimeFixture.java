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

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Assert;
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

/** Only download/trust-provider and installed guest file I/O are fixtures; verification is the real manager and Ed25519 verifier. */
final class StorageConfigSignedRuntimeFixture extends StorageServiceRuntimeUpgradeManagerImpl {
    final StorageServiceRuntimeBundleVO bundle;
    final Path installed;
    final Map<String,byte[]> contents=new LinkedHashMap<>();
    final byte[] archive,manifest,signature,publicKey;
    final String cliSha256;
    StorageConfigSignedRuntimeFixture(Path repository,Path root,long id,String uuid)throws Exception{
        installed=Files.createDirectory(root.resolve("installed-runtime"));
        for(String name:new String[]{"ablestack-storagectl","ablestack-storage-boot-reconcile","ablestack-storage-monitor"}){
            byte[] bytes=Files.readAllBytes(repository.resolve("systemvm/debian/usr/local/bin/"+name));
            contents.put(name,bytes);Path copy=installed.resolve(name);Files.write(copy,bytes);
            Files.setPosixFilePermissions(copy,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Path metadata=root.resolve("signed-fixture-manifest.json");
        String producer=Files.readString(repository.resolve("tools/build/build-storage-runtime-bundle.sh")).split("<<'PY'\n",2)[1].split("\nPY",2)[0];
        Process child=new ProcessBuilder("python3","-B","-c",producer,installed.toString(),metadata.toString(),
                "clone-flow-fixture","1","1","NONE","fixture-key","a".repeat(40),"fixture-time").redirectError(root.resolve("manifest-safe-stderr.txt").toFile()).start();
        Assert.assertEquals("normal manifest producer fixture",0,child.waitFor());manifest=Files.readAllBytes(metadata);
        JsonObject parsed=com.google.gson.JsonParser.parseString(new String(manifest,StandardCharsets.UTF_8)).getAsJsonObject();
        String cli=null;for(JsonElement row:parsed.getAsJsonArray("files"))if("ablestack-storagectl".equals(row.getAsJsonObject().get("path").getAsString()))cli=row.getAsJsonObject().get("sha256").getAsString();
        Assert.assertEquals(cli,StorageConfigArchive.sha256(contents.get("ablestack-storagectl")));cliSha256=cli;
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(TarArchiveOutputStream tar=new TarArchiveOutputStream(new GZIPOutputStream(buffer))){
            for(Map.Entry<String,byte[]> row:contents.entrySet()){
                TarArchiveEntry entry=new TarArchiveEntry(row.getKey());entry.setMode(0755);entry.setSize(row.getValue().length);
                tar.putArchiveEntry(entry);tar.write(row.getValue());tar.closeArchiveEntry();
            }
        }
        archive=buffer.toByteArray();KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKey=("-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.US_ASCII);
        Signature signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());signer.update(manifest);signature=signer.sign();
        bundle=new StorageServiceRuntimeBundleVO("clone-flow-fixture","1","1",StorageServiceRuntimeBundleVO.ServiceImpact.NONE,
                "fixture:archive","fixture:manifest","fixture:signature",(long)archive.length,StorageConfigArchive.sha256(archive),StorageConfigArchive.sha256(manifest),"fixture-key");
        org.springframework.test.util.ReflectionTestUtils.setField(bundle,"id",id);org.springframework.test.util.ReflectionTestUtils.setField(bundle,"uuid",uuid);
        bundle.setState(StorageServiceRuntimeBundleVO.State.AVAILABLE);
    }
    @Override protected byte[] download(String name,int maximum){
        byte[] value="fixture:archive".equals(name)?archive:"fixture:manifest".equals(name)?manifest:"fixture:signature".equals(name)?signature:null;
        Assert.assertNotNull(name,value);Assert.assertTrue(value.length<=maximum);return value.clone();
    }
    @Override protected byte[] trustedKey(String name){Assert.assertEquals("fixture-key",name);return publicKey.clone();}
    private boolean verifyInstalled(){
        JsonObject checked=new StorageServiceRuntimeBundleVerifier().verify(bundle,archive,manifest,signature,publicKey);
        for(JsonElement item:checked.getAsJsonObject("manifest").getAsJsonArray("files")){
            JsonObject file=item.getAsJsonObject();
            try{if(!file.get("sha256").getAsString().equals(StorageConfigArchive.sha256(Files.readAllBytes(installed.resolve(file.get("path").getAsString())))))return false;}
            catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }
        return checked.get("verified").getAsBoolean();
    }
    @Override protected JsonObject invoke(StorageServiceInstanceVO instance,StorageServiceRuntimeOperation operation,String transaction,JsonObject request){
        Assert.assertTrue(operation==StorageServiceRuntimeOperation.CAPABILITIES||operation==StorageServiceRuntimeOperation.READBACK);
        boolean verified=verifyInstalled();JsonObject result=new JsonObject();
        result.addProperty("success",verified);result.addProperty("signedRuntimeReadback",verified);
        result.addProperty("updaterSha256",StorageConfigArchive.sha256(resource("/storage-runtime/bootstrap/runtime_updater.py")));
        if(operation==StorageServiceRuntimeOperation.CAPABILITIES){result.addProperty("platformVersionKnown",false);return result;}
        Assert.assertEquals("fixture-installed-transaction",transaction);
        result.addProperty("signedRuntimeVerified",verified);result.addProperty("installedFilesVerified",verified);result.addProperty("entrypointsVerified",verified);
        result.addProperty("currentVersion",bundle.getVersion());result.addProperty("archiveSha256",bundle.getSha256());result.addProperty("manifestSha256",bundle.getManifestSha256());return result;
    }
}
