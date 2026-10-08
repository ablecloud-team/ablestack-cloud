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
import java.util.Set;

public class StorageAdIdentityProofTest {
    private JsonObject scope(){JsonObject s=new JsonObject();s.addProperty("instanceUuid","11111111-1111-1111-1111-111111111111");s.addProperty("operationUuid","22222222-2222-2222-2222-222222222222");s.addProperty("revision",1);return s;}
    private JsonObject joined(){JsonObject p=new JsonObject();
        p.addProperty("success",true);
        p.addProperty("sideEffects",false);
        p.add("scope",scope());
        p.addProperty("bootId","11111111-1111-1111-1111-111111111111");
        p.addProperty("generatedEpoch",1000.25);
        p.addProperty("joinState","JOINED");
        p.addProperty("domain","example.test");
        p.addProperty("realm","EXAMPLE.TEST");
        p.addProperty("workgroup","EXAMPLE");
        p.addProperty("netbiosName","ASTINSTANCE");
        p.addProperty("domainSid","S-1-5-21-1-2-3");
        p.addProperty("machineSid","S-1-5-21-4-5-6");
        p.addProperty("machineAccountSid","S-1-5-21-1-2-3-1001");
        for(String key:Set.of("trustVerified","identityVerified","dnsAliasesVerified","adSpnsVerified","adIdentity"))p.addProperty(key,true);
        JsonObject map=com.google.gson.JsonParser.parseString("{\"default\":{\"backend\":\"tdb\",\"range\":[10000,60000]},\"domain\":{\"backend\":\"rid\",\"range\":[1000000,1999999],\"baseRid\":0}}").getAsJsonObject();
        p.add("idmapPolicy",map);
        JsonArray spns=new JsonArray();
        spns.add("cifs/storage.example.test");spns.add("host/storage.example.test");
        p.add("servicePrincipals",spns);
        JsonArray aliases=new JsonArray();
        JsonObject alias=new JsonObject();
        alias.addProperty("hostname","storage.example.test");
        JsonArray ips=new JsonArray();
        ips.add("10.10.13.240");
        alias.add("addresses",ips);
        aliases.add(alias);
        p.add("dnsAliases",aliases);
        return p;
        }
    private JsonObject principal(String type,long sidType){JsonObject p=new JsonObject();p.addProperty("success",true);p.addProperty("sideEffects",false);p.add("scope",scope());p.addProperty("bootId",joined().get("bootId").getAsString());p.addProperty("generatedEpoch",1000.25);p.addProperty("domainSid","S-1-5-21-1-2-3");p.addProperty("realm","EXAMPLE.TEST");p.addProperty("workgroup","EXAMPLE");p.addProperty("qualifiedName","EXAMPLE\\alice");p.addProperty("principalType",type);p.addProperty("sid","S-1-5-21-1-2-3-1201");p.addProperty("sidType",sidType);p.addProperty("sidTypeName",sidType==1?"SID_USER":sidType==2?"SID_DOM_GRP":"SID_ALIAS");p.addProperty("numericId",1001201);p.addProperty("kind",type.equals("AD_USER")?"u":"g");p.addProperty("reverseVerified",true);p.addProperty("mappingVerified",true);return p;}
    private JsonObject source(){JsonObject source=new JsonObject();source.addProperty("publicLocalMachineSid","S-1-5-21-4-5-6");source.addProperty("bootId",joined().get("bootId").getAsString());return source;}
    private JsonObject notJoined(){JsonObject proof=new JsonObject();proof.addProperty("success",true);proof.addProperty("sideEffects",false);proof.add("scope",scope());proof.addProperty("joinState","NOT_JOINED");for(String field:Set.of("trustVerified","identityVerified","adIdentity"))proof.addProperty(field,false);proof.addProperty("machineSid","S-1-5-21-4-5-6");proof.addProperty("bootId",source().get("bootId").getAsString());proof.addProperty("generatedEpoch",1000.25);return proof;}
    @Test public void leaveObservationRequiresFreshClockExactBootAndPreservedLocalSam(){
        Assert.assertEquals(notJoined(),StorageAdIdentityProof.notJoined(notJoined(),scope(),source(),1001));
        for(String field:Set.of("generatedEpoch","bootId","machineSid","trustVerified","sideEffects")){
            JsonObject wrong=notJoined();
            if(field.equals("generatedEpoch"))wrong.addProperty(field,939.0);
            else if(field.equals("bootId"))wrong.addProperty(field,"33333333-3333-3333-3333-333333333333");
            else if(field.equals("machineSid"))wrong.addProperty(field,"S-1-5-21-4-5-7");
            else wrong.addProperty(field,"false");
            Assert.assertThrows(field,RuntimeException.class,()->StorageAdIdentityProof.notJoined(wrong,scope(),source(),1001));
        }
        JsonObject privateProof=notJoined();privateProof.addProperty("password","synthetic-secret");
        Assert.assertFalse(StorageAdIdentityProof.notJoined(privateProof,scope(),source(),1001).has("password"));
    }
    @Test public void observationRejectsUnboundScopeAndUnsignedSidOverflowBeforePublication(){
        for(String field:Set.of("instanceUuid","operationUuid","revision")){
            JsonObject wrongScope=scope();wrongScope.addProperty(field,"unbound");
            JsonObject proof=joined();proof.add("scope",wrongScope);
            Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.joined(proof,wrongScope,"example.test",null,null,1001));
        }
        JsonObject overflow=joined();overflow.addProperty("machineSid","S-1-5-21-4294967296-5-6");
        Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.joined(overflow,scope(),"example.test",null,null,1001));
        JsonObject mixed=joined();mixed.getAsJsonObject("idmapPolicy").getAsJsonObject("default").addProperty("backend","rid");
        Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.joined(mixed,scope(),"example.test",null,null,1001));
    }
    @Test public void freshlyJoinedProofRequiresActualTrustSidSpnDnsAndIdmap(){JsonObject proof=joined();Assert.assertEquals(proof,StorageAdIdentityProof.joined(proof,scope(),"example.test","S-1-5-21-1-2-3",proof.getAsJsonObject("idmapPolicy"),1001));for(String key:Set.of("trustVerified","dnsAliasesVerified","identityVerified")){JsonObject wrong=proof.deepCopy();wrong.addProperty(key,"true");Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.joined(wrong,scope(),"example.test",null,null,1001));}Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.joined(proof,scope(),"foreign.test",null,null,1001));Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.joined(proof,scope(),"example.test",null,null,1061));}
    @Test public void userAndBothGroupSidKindsResolveOnlyInTheExactJoinedRealm(){for(long sidType:new long[]{1,2,4}){String type=sidType==1?"AD_USER":"AD_GROUP";JsonObject proof=principal(type,sidType);Assert.assertEquals(proof,StorageAdIdentityProof.principal(proof,scope(),joined(),type,"EXAMPLE\\alice",1001));}}
    @Test public void foreignPrincipalSidWrongKindOrNumericFallbackIsRejected(){JsonObject proof=principal("AD_USER",1);Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.principal(proof,scope(),joined(),"AD_USER","FOREIGN\\alice",1001));Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.principal(proof,scope(),joined(),"AD_USER","alice@foreign.test",1001));for(long uid:new long[]{0,65534,2147483648L}){JsonObject wrong=proof.deepCopy();wrong.addProperty("numericId",uid);Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.principal(wrong,scope(),joined(),"AD_USER","EXAMPLE\\alice",1001));}JsonObject wrong=proof.deepCopy();wrong.addProperty("sidType",2);Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.principal(wrong,scope(),joined(),"AD_USER","EXAMPLE\\alice",1001));}
    @Test public void staleCrossBootStringNumericAndReverseUnverifiedReceiptsCannotBeUsed(){for(String key:Set.of("bootId","numericId","reverseVerified")){JsonObject wrong=principal("AD_GROUP",2);if(key.equals("bootId"))wrong.addProperty(key,"22222222-2222-2222-2222-222222222222");if(key.equals("numericId"))wrong.addProperty(key,"1001201");if(key.equals("reverseVerified"))wrong.addProperty(key,false);Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.principal(wrong,scope(),joined(),"AD_GROUP","EXAMPLE\\alice",1001));}}
    @Test public void realNativeKeytabPublicMetadataProducerMatchesJavaReceiptSchema() throws Exception {
        java.nio.file.Path source=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!java.nio.file.Files.exists(source.resolve("systemvm/debian/usr/local/lib/ablestack-storage/ad_identity.py")))source=source.getParent();
        String script="import sys,json;sys.path.insert(0,sys.argv[1]);import ad_identity;print(json.dumps(ad_identity.keytab_principals('1 host/storage.example.test@EXAMPLE.TEST\\n1 cifs/storage.example.test@EXAMPLE.TEST\\n','example.test',['host/storage.example.test','cifs/storage.example.test'],'ASTINSTANCE')))";
        Process process=new ProcessBuilder("python3","-c",script,source.resolve("systemvm/debian/usr/local/lib/ablestack-storage").toString()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);Assert.assertEquals(output,0,process.waitFor());JsonObject metadata=com.google.gson.JsonParser.parseString(output).getAsJsonObject();JsonObject proof=joined();proof.add("servicePrincipals",metadata.get("servicePrincipals"));proof.add("realm",metadata.get("realm"));Assert.assertEquals(proof,StorageAdIdentityProof.joined(proof,scope(),"example.test","S-1-5-21-1-2-3",proof.getAsJsonObject("idmapPolicy"),1001));Assert.assertFalse(metadata.getAsJsonArray("servicePrincipals").get(0).getAsString().contains("@"));
    }
    @Test public void undocumentedSecretFieldsNeverEnterPersistedPublicReceipts(){JsonObject proof=joined();proof.addProperty("password","synthetic-secret");proof.addProperty("keytabBytes","synthetic-key");JsonObject sanitized=StorageAdIdentityProof.joined(proof,scope(),"example.test",null,null,1001);Assert.assertFalse(sanitized.has("password"));Assert.assertFalse(sanitized.has("keytabBytes"));JsonObject mapped=principal("AD_USER",1);mapped.addProperty("privateKey","synthetic-key");Assert.assertFalse(StorageAdIdentityProof.principal(mapped,scope(),joined(),"AD_USER","EXAMPLE\\alice",1001).has("privateKey"));}
}
