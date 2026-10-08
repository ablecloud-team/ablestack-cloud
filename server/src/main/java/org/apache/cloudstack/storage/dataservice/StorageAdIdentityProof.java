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

import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.cloud.utils.exception.CloudRuntimeException;

/** Fresh public identity receipts never substitute guessed UID/GID or a JOINED database flag. */
public final class StorageAdIdentityProof {
    private StorageAdIdentityProof() { }
    private static String text(JsonObject object,String key) {JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new CloudRuntimeException("AD identity string proof is unavailable: "+key);return value.getAsString();}
    private static void yes(JsonObject object,String key,boolean expected) {JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean()||value.getAsBoolean()!=expected)throw new CloudRuntimeException("AD identity literal proof is unavailable: "+key);}
    private static long integer(JsonObject object,String key) {JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]+"))throw new CloudRuntimeException("AD identity integer proof is invalid: "+key);try{return Long.parseLong(value.getAsString());}catch(NumberFormatException invalid){throw new CloudRuntimeException("AD identity integer proof exceeds range",invalid);}}
    private static void sid(String value) {
        if(!value.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+(?:-[0-9]+)?"))throw new CloudRuntimeException("AD identity SID proof is invalid");
        for(String component:value.substring(9).split("-"))try {if(Long.parseLong(component)>4294967295L)throw new CloudRuntimeException("AD SID subauthority exceeds its native unsigned range");}
        catch(NumberFormatException invalid){throw new CloudRuntimeException("AD SID subauthority is invalid",invalid);}
    }
    private static void fresh(JsonObject proof,JsonObject scope,double now) {
        if(scope==null||!scope.keySet().equals(Set.of("instanceUuid","operationUuid","revision")))throw new CloudRuntimeException("AD observation scope must contain the exact operation binding");
        for(String key:Set.of("instanceUuid","operationUuid"))if(!text(scope,key).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("AD observation scope UUID is invalid");
        integer(scope,"revision");if(!Double.isFinite(now))throw new CloudRuntimeException("AD observation clock is invalid");
        yes(proof,"success",true);yes(proof,"sideEffects",false);if(!scope.equals(proof.get("scope")))throw new CloudRuntimeException("AD identity receipt belongs to another observation scope");if(!text(proof,"bootId").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("AD identity boot proof is invalid");JsonElement epoch=proof.get("generatedEpoch");if(epoch==null||!epoch.isJsonPrimitive()||!epoch.getAsJsonPrimitive().isNumber())throw new CloudRuntimeException("AD identity observation time is unavailable");double observed=epoch.getAsDouble();if(!Double.isFinite(observed)||observed>now+5||observed<now-60)throw new CloudRuntimeException("AD identity observation is stale or from another clock");}
    private static JsonObject publicFields(JsonObject proof,Set<String> fields){JsonObject result=new JsonObject();for(String field:fields)if(proof.has(field))result.add(field,proof.get(field).deepCopy());return result;}
    public static JsonObject joined(JsonObject proof,JsonObject scope,String domain,String expectedDomainSid,JsonObject expectedIdmap,double now) {
        fresh(proof,scope,now);if(!"JOINED".equals(text(proof,"joinState"))||!domain.toLowerCase(Locale.ROOT).equals(text(proof,"domain"))||!domain.toUpperCase(Locale.ROOT).equals(text(proof,"realm")))throw new CloudRuntimeException("AD joined realm does not match the configured domain");
        for(String key:Set.of("trustVerified","identityVerified","dnsAliasesVerified","adSpnsVerified","adIdentity"))yes(proof,key,true);
        String domainSid=text(proof,"domainSid"),machineSid=text(proof,"machineSid"),computerSid=text(proof,"machineAccountSid");sid(domainSid);sid(machineSid);sid(computerSid);if(!domainSid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+")||!machineSid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+")||!computerSid.matches(java.util.regex.Pattern.quote(domainSid)+"-[0-9]+")||computerSid.equals(machineSid)||expectedDomainSid!=null&&!expectedDomainSid.equals(domainSid))throw new CloudRuntimeException("AD machine/computer/domain identity differs from its bound receipt");
        for(String field:Set.of("workgroup","netbiosName"))if(!text(proof,field).matches("[A-Z0-9][A-Z0-9_-]{0,14}"))throw new CloudRuntimeException("AD qualified workgroup or machine name is invalid");
        if(!proof.has("idmapPolicy")||!proof.get("idmapPolicy").isJsonObject()||proof.getAsJsonObject("idmapPolicy").size()==0||expectedIdmap!=null&&!expectedIdmap.equals(proof.get("idmapPolicy")))throw new CloudRuntimeException("AD idmap policy is missing or changed");StorageAdLifecycleRequest.validateIdmap(proof.getAsJsonObject("idmapPolicy"));
        if(!proof.has("servicePrincipals")||!proof.get("servicePrincipals").isJsonArray()||proof.getAsJsonArray("servicePrincipals").size()==0||!proof.has("dnsAliases")||!proof.get("dnsAliases").isJsonArray()||proof.getAsJsonArray("dnsAliases").size()==0)throw new CloudRuntimeException("AD service principal or DNS alias proof is unavailable");
        Set<String> principals=new HashSet<>();for(JsonElement value:proof.getAsJsonArray("servicePrincipals"))if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!value.getAsString().matches("(?i)(host|cifs)/[a-z0-9.-]+")||!principals.add(value.getAsString().toLowerCase(Locale.ROOT)))throw new CloudRuntimeException("AD service principal receipt is malformed or duplicated");
        Set<String> aliases=new HashSet<>();
        for(JsonElement value:proof.getAsJsonArray("dnsAliases")){if(!value.isJsonObject())throw new CloudRuntimeException("AD DNS alias receipt is malformed");
        JsonObject alias=value.getAsJsonObject();
        String hostname=text(alias,"hostname").toLowerCase(Locale.ROOT);
        if(!hostname.endsWith("."+domain.toLowerCase(Locale.ROOT))||!aliases.add(hostname)||!alias.has("addresses")||!alias.get("addresses").isJsonArray()||alias.getAsJsonArray("addresses").size()==0)throw new CloudRuntimeException("AD DNS alias is foreign, duplicated or empty");
        for(String service:Set.of("host","cifs"))if(!principals.contains(service+"/"+hostname))throw new CloudRuntimeException("AD DNS alias lacks its exact host/cifs service principal binding");
        Set<String> addresses=new HashSet<>();
        for(JsonElement address:alias.getAsJsonArray("addresses"))if(!address.isJsonPrimitive()||!address.getAsJsonPrimitive().isString()||!com.cloud.utils.net.NetUtils.isValidIp4(address.getAsString())||!addresses.add(address.getAsString()))throw new CloudRuntimeException("AD DNS alias address receipt is invalid");
        }return publicFields(proof,Set.of("success","scope","sideEffects","joinState","domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","dnsAliases","idmapPolicy","trustVerified","identityVerified","dnsAliasesVerified","adSpnsVerified","adIdentity","bootId","generatedEpoch","requiredServicePrincipalsVerified"));

    }
    /** A local leave observation is fresh and remains bound to the immutable PRESTOP local SAM. */
    public static JsonObject notJoined(JsonObject proof,JsonObject scope,JsonObject source,double now) {
        fresh(proof,scope,now);
        if(!"NOT_JOINED".equals(text(proof,"joinState")))throw new CloudRuntimeException("AD leave still observes a joined identity");
        for(String field:Set.of("trustVerified","identityVerified","adIdentity"))yes(proof,field,false);
        bindServiceSource(proof,source);
        return publicFields(proof,Set.of("success","scope","sideEffects","joinState","trustVerified","identityVerified","adIdentity","machineSid","bootId","generatedEpoch"));
    }
    public static void validateServiceSource(JsonObject source) {
        String localSid=text(source,"publicLocalMachineSid"),boot=text(source,"bootId");sid(localSid);
        if(!localSid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+")||!boot.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("AD lifecycle source local SAM or boot identity is invalid");
    }
    public static void bindServiceSource(JsonObject proof,JsonObject source) {
        validateServiceSource(source);
        if(!text(source,"publicLocalMachineSid").equals(text(proof,"machineSid"))||!text(source,"bootId").equals(text(proof,"bootId")))throw new CloudRuntimeException("AD lifecycle receipt changed the stopped local SAM or boot identity");
    }
    public static JsonObject principal(JsonObject proof,JsonObject scope,JsonObject joined,String principalType,String requestedPrincipal,double now) {
        fresh(proof,scope,now);yes(proof,"mappingVerified",true);yes(proof,"reverseVerified",true);if(!Set.of("AD_USER","AD_GROUP").contains(principalType)||!principalType.equals(text(proof,"principalType")))throw new CloudRuntimeException("AD principal type differs from the requested identity");
        for(String field:Set.of("domainSid","realm","workgroup","bootId"))if(!text(joined,field).equals(text(proof,field)))throw new CloudRuntimeException("AD principal receipt belongs to another joined realm or boot");
        String qualified=text(proof,"qualifiedName"),workgroup=text(joined,"workgroup"),domain=text(joined,"domain"),requested=requestedPrincipal;int slash=requested.indexOf('\\');if(slash>=0){String prefix=requested.substring(0,slash);if(!prefix.equalsIgnoreCase(workgroup)&&!prefix.equalsIgnoreCase(domain))throw new CloudRuntimeException("AD principal uses a foreign qualified domain");requested=requested.substring(slash+1);}else if(requested.contains("@")){int at=requested.lastIndexOf('@');if(!requested.substring(at+1).equalsIgnoreCase(domain))throw new CloudRuntimeException("AD principal uses a foreign UPN realm");requested=requested.substring(0,at);}else throw new CloudRuntimeException("AD principal must be explicitly domain qualified");
        if(!requested.matches("[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}")||!requested.equals(requested.trim())||!qualified.equalsIgnoreCase(workgroup+"\\"+requested))throw new CloudRuntimeException("AD resolved principal does not match the requested qualified identity");String resolvedSid=text(proof,"sid");sid(resolvedSid);if(!resolvedSid.matches(java.util.regex.Pattern.quote(text(joined,"domainSid"))+"-[0-9]+"))throw new CloudRuntimeException("AD principal SID belongs to another domain");
        long kind=integer(proof,"sidType"),numeric=integer(proof,"numericId");
        JsonObject idmap=joined.getAsJsonObject("idmapPolicy");
        if(idmap==null||!idmap.has("domain")||!idmap.get("domain").isJsonObject()||!idmap.getAsJsonObject("domain").has("range")||!idmap.getAsJsonObject("domain").get("range").isJsonArray()||idmap.getAsJsonObject("domain").getAsJsonArray("range").size()!=2)throw new CloudRuntimeException("AD principal lacks its exact joined domain numeric range");
        com.google.gson.JsonArray range=idmap.getAsJsonObject("domain").getAsJsonArray("range");
        for(JsonElement bound:range)if(!bound.isJsonPrimitive()||!bound.getAsJsonPrimitive().isNumber()||!bound.getAsString().matches("[0-9]+"))throw new CloudRuntimeException("AD idmap range proof is not literal");
        if(numeric<range.get(0).getAsLong()||numeric>range.get(1).getAsLong())throw new CloudRuntimeException("AD principal numeric mapping is outside its joined domain range");
        if(numeric<1000||numeric>2147483647L||numeric==65534||"AD_USER".equals(principalType)&&(kind!=1||!"u".equals(text(proof,"kind")))||"AD_GROUP".equals(principalType)&&(!Set.of(2L,4L).contains(kind)||!"g".equals(text(proof,"kind"))))throw new CloudRuntimeException("AD principal SID kind or numeric mapping is invalid");
        if(!(kind==1?"SID_USER":kind==2?"SID_DOM_GRP":"SID_ALIAS").equals(text(proof,"sidTypeName")))throw new CloudRuntimeException("AD principal SID label differs from its numeric kind");
        return publicFields(proof,Set.of("success","scope","sideEffects","qualifiedName","sid","principalType","sidType","sidTypeName","numericId","kind","mappingVerified","reverseVerified","domainSid","realm","workgroup","bootId","generatedEpoch"));

    }
}
