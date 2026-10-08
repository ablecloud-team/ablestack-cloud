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
    private static void sid(String value) {if(!value.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+(?:-[0-9]+)?"))throw new CloudRuntimeException("AD identity SID proof is invalid");}
    private static void fresh(JsonObject proof,JsonObject scope,double now) {yes(proof,"success",true);yes(proof,"sideEffects",false);if(!scope.equals(proof.get("scope")))throw new CloudRuntimeException("AD identity receipt belongs to another observation scope");if(!text(proof,"bootId").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("AD identity boot proof is invalid");JsonElement epoch=proof.get("generatedEpoch");if(epoch==null||!epoch.isJsonPrimitive()||!epoch.getAsJsonPrimitive().isNumber())throw new CloudRuntimeException("AD identity observation time is unavailable");double observed=epoch.getAsDouble();if(!Double.isFinite(observed)||observed>now+5||observed<now-60)throw new CloudRuntimeException("AD identity observation is stale or from another clock");}
    public static JsonObject joined(JsonObject proof,JsonObject scope,String domain,String expectedDomainSid,JsonObject expectedIdmap,double now) {
        fresh(proof,scope,now);if(!"JOINED".equals(text(proof,"joinState"))||!domain.toLowerCase(Locale.ROOT).equals(text(proof,"domain"))||!domain.toUpperCase(Locale.ROOT).equals(text(proof,"realm")))throw new CloudRuntimeException("AD joined realm does not match the configured domain");
        for(String key:Set.of("trustVerified","identityVerified","dnsAliasesVerified","adSpnsVerified","adIdentity"))yes(proof,key,true);
        String domainSid=text(proof,"domainSid"),machineSid=text(proof,"machineSid"),computerSid=text(proof,"machineAccountSid");sid(domainSid);sid(machineSid);sid(computerSid);if(!domainSid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+")||!machineSid.matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+")||!computerSid.matches(java.util.regex.Pattern.quote(domainSid)+"-[0-9]+")||computerSid.equals(machineSid)||expectedDomainSid!=null&&!expectedDomainSid.equals(domainSid))throw new CloudRuntimeException("AD machine/computer/domain identity differs from its bound receipt");
        for(String field:Set.of("workgroup","netbiosName"))if(!text(proof,field).matches("[A-Z0-9][A-Z0-9_-]{0,14}"))throw new CloudRuntimeException("AD qualified workgroup or machine name is invalid");
        if(!proof.has("idmapPolicy")||!proof.get("idmapPolicy").isJsonObject()||proof.getAsJsonObject("idmapPolicy").size()==0||expectedIdmap!=null&&!expectedIdmap.equals(proof.get("idmapPolicy")))throw new CloudRuntimeException("AD idmap policy is missing or changed");
        if(!proof.has("servicePrincipals")||!proof.get("servicePrincipals").isJsonArray()||proof.getAsJsonArray("servicePrincipals").size()==0||!proof.has("dnsAliases")||!proof.get("dnsAliases").isJsonArray()||proof.getAsJsonArray("dnsAliases").size()==0)throw new CloudRuntimeException("AD service principal or DNS alias proof is unavailable");
        Set<String> principals=new HashSet<>();for(JsonElement value:proof.getAsJsonArray("servicePrincipals"))if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!value.getAsString().matches("(?i)(host|cifs)/[a-z0-9.-]+@[A-Z0-9.-]+")||!principals.add(value.getAsString().toLowerCase(Locale.ROOT)))throw new CloudRuntimeException("AD service principal receipt is malformed or duplicated");
        Set<String> aliases=new HashSet<>();
        for(JsonElement value:proof.getAsJsonArray("dnsAliases")){if(!value.isJsonObject())throw new CloudRuntimeException("AD DNS alias receipt is malformed");
        JsonObject alias=value.getAsJsonObject();
        String hostname=text(alias,"hostname").toLowerCase(Locale.ROOT);
        if(!hostname.endsWith("."+domain.toLowerCase(Locale.ROOT))||!aliases.add(hostname)||!alias.has("addresses")||!alias.get("addresses").isJsonArray()||alias.getAsJsonArray("addresses").size()==0)throw new CloudRuntimeException("AD DNS alias is foreign, duplicated or empty");
        for(String service:Set.of("host","cifs"))if(!principals.contains(service+"/"+hostname+"@"+domain.toLowerCase(Locale.ROOT)))throw new CloudRuntimeException("AD DNS alias lacks its exact host/cifs service principal binding");
        Set<String> addresses=new HashSet<>();
        for(JsonElement address:alias.getAsJsonArray("addresses"))if(!address.isJsonPrimitive()||!address.getAsJsonPrimitive().isString()||!com.cloud.utils.net.NetUtils.isValidIp4(address.getAsString())||!addresses.add(address.getAsString()))throw new CloudRuntimeException("AD DNS alias address receipt is invalid");
        }return proof.deepCopy();

    }
    public static JsonObject principal(JsonObject proof,JsonObject scope,JsonObject joined,String principalType,String requestedPrincipal,double now) {
        fresh(proof,scope,now);yes(proof,"mappingVerified",true);yes(proof,"reverseVerified",true);if(!Set.of("AD_USER","AD_GROUP").contains(principalType)||!principalType.equals(text(proof,"principalType")))throw new CloudRuntimeException("AD principal type differs from the requested identity");
        for(String field:Set.of("domainSid","realm","workgroup","bootId"))if(!text(joined,field).equals(text(proof,field)))throw new CloudRuntimeException("AD principal receipt belongs to another joined realm or boot");
        String qualified=text(proof,"qualifiedName"),workgroup=text(joined,"workgroup"),domain=text(joined,"domain"),requested=requestedPrincipal;int slash=requested.indexOf('\\');if(slash>=0){String prefix=requested.substring(0,slash);if(!prefix.equalsIgnoreCase(workgroup)&&!prefix.equalsIgnoreCase(domain))throw new CloudRuntimeException("AD principal uses a foreign qualified domain");requested=requested.substring(slash+1);}else if(requested.contains("@")){int at=requested.lastIndexOf('@');if(!requested.substring(at+1).equalsIgnoreCase(domain))throw new CloudRuntimeException("AD principal uses a foreign UPN realm");requested=requested.substring(0,at);}else throw new CloudRuntimeException("AD principal must be explicitly domain qualified");
        if(!requested.matches("[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}")||!requested.equals(requested.trim())||!qualified.equalsIgnoreCase(workgroup+"\\"+requested))throw new CloudRuntimeException("AD resolved principal does not match the requested qualified identity");String resolvedSid=text(proof,"sid");sid(resolvedSid);if(!resolvedSid.matches(java.util.regex.Pattern.quote(text(joined,"domainSid"))+"-[0-9]+"))throw new CloudRuntimeException("AD principal SID belongs to another domain");
        long kind=integer(proof,"sidType"),numeric=integer(proof,"numericId");if(numeric<1000||numeric>2147483647L||numeric==65534||"AD_USER".equals(principalType)&&(kind!=1||!"u".equals(text(proof,"kind")))||"AD_GROUP".equals(principalType)&&(!Set.of(2L,4L).contains(kind)||!"g".equals(text(proof,"kind"))))throw new CloudRuntimeException("AD principal SID kind or numeric mapping is invalid");if(!(kind==1?"SID_USER":kind==2?"SID_DOM_GRP":"SID_ALIAS").equals(text(proof,"sidTypeName")))throw new CloudRuntimeException("AD principal SID label differs from its numeric kind");return proof.deepCopy();
    }
}
