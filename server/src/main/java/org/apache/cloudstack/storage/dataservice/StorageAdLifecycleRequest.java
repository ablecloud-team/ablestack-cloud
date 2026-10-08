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

import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.cloud.utils.exception.CloudRuntimeException;

/** AD credentials stay in protected RPC input; the public configuration is separately durable. */
public final class StorageAdLifecycleRequest {
    private StorageAdLifecycleRequest() { }
    private static String domain(String value){String name=value==null?"":value.toLowerCase(Locale.ROOT);if(name.length()>253||!name.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"))throw new CloudRuntimeException("AD domain name is invalid");return name;}
    public static JsonObject defaultsIdmap(){JsonObject policy=new JsonObject(),fallback=new JsonObject(),joined=new JsonObject();fallback.addProperty("backend","tdb");JsonArray range=new JsonArray();range.add(10000);range.add(60000);fallback.add("range",range);joined.addProperty("backend","rid");range=new JsonArray();range.add(1000000);range.add(1999999);joined.add("range",range);joined.addProperty("baseRid",0);policy.add("default",fallback);policy.add("domain",joined);return policy;}
    public static void validateIdmap(JsonObject value) {
        if(value==null||!value.keySet().equals(Set.of("default","domain")))throw new CloudRuntimeException("AD idmap requires exact default and domain policies");
        long[][] limits=new long[2][];
        int index=0;
        for(String scope:new String[]{"default","domain"}){JsonObject row=value.getAsJsonObject(scope);
        String backend=row.has("backend")&&row.get("backend").isJsonPrimitive()&&row.get("backend").getAsJsonPrimitive().isString()?row.get("backend").getAsString():null;
        if(!("default".equals(scope)?"tdb":"rid").equals(backend)||!row.keySet().equals(backend.equals("rid")?Set.of("backend","range","baseRid"):Set.of("backend","range"))||!row.has("range")||!row.get("range").isJsonArray()||row.getAsJsonArray("range").size()!=2)throw new CloudRuntimeException("AD idmap backend fields are invalid");
        long[] range=new long[2];
        for(int i=0;
        i<2;
        i++){JsonElement bound=row.getAsJsonArray("range").get(i);
        if(!bound.isJsonPrimitive()||!bound.getAsJsonPrimitive().isNumber()||!bound.getAsString().matches("[0-9]+"))throw new CloudRuntimeException("AD idmap range must use literal integer values");
        range[i]=bound.getAsLong();
        }if(range[0]<1000||range[0]>range[1]||range[1]>2147483647L||range[0]<=65534&&range[1]>=65534)throw new CloudRuntimeException("AD idmap includes a protected or invalid range");
        if(backend.equals("rid")){JsonElement base=row.get("baseRid");
        if(!base.isJsonPrimitive()||!base.getAsJsonPrimitive().isNumber()||!base.getAsString().matches("[0-9]+")||base.getAsLong()>2147483647L)throw new CloudRuntimeException("AD RID base is invalid");
        }limits[index++]=range;
        }if(Math.max(limits[0][0],limits[1][0])<=Math.min(limits[0][1],limits[1][1]))throw new CloudRuntimeException("AD idmap default and domain ranges overlap");

    }
    public static JsonObject publicJoin(String domainName,String workgroup,String netbiosName,String dnsServers,JsonArray addresses,String identityMode,JsonObject sourceIdentity) {
        String domain=domain(domainName),group=workgroup==null?domain.substring(0,domain.indexOf('.')).toUpperCase(Locale.ROOT):workgroup.toUpperCase(Locale.ROOT),machine=netbiosName==null?"":netbiosName.toUpperCase(Locale.ROOT);
        if(!group.matches("[A-Z0-9][A-Z0-9_-]{0,14}")||!machine.matches("[A-Z0-9][A-Z0-9_-]{0,14}")||!Set.of("JOIN_EXISTING","NEW_INSTANCE").contains(identityMode))throw new CloudRuntimeException("AD workgroup, machine name or identity mode is invalid");
        JsonArray dns=new JsonArray();Set<String> seen=new HashSet<>();for(String server:(dnsServers==null?"":dnsServers).split(",")){String address=server.trim();if(!com.cloud.utils.net.NetUtils.isValidIp4(address)||!seen.add(address))throw new CloudRuntimeException("AD DNS servers must be explicit unique IPv4 addresses");dns.add(address);}if(dns.size()==0||addresses==null||addresses.size()==0)throw new CloudRuntimeException("AD DNS and owned SMB endpoint addresses are required");
        seen.clear();JsonArray bound=new JsonArray();for(JsonElement address:addresses){if(!address.isJsonPrimitive()||!address.getAsJsonPrimitive().isString()||!com.cloud.utils.net.NetUtils.isValidIp4(address.getAsString())||!seen.add(address.getAsString()))throw new CloudRuntimeException("AD owned endpoint address is malformed or duplicated");bound.add(address.deepCopy());}
        String hostname=machine.toLowerCase(Locale.ROOT)+"."+domain;if("NEW_INSTANCE".equals(identityMode)&&sourceIdentity!=null){if(sourceIdentity.has("netbiosName")&&machine.equals(sourceIdentity.get("netbiosName").getAsString()))throw new CloudRuntimeException("AD new instance cannot reuse the source computer identity");if(sourceIdentity.has("dnsAliases"))for(JsonElement old:sourceIdentity.getAsJsonArray("dnsAliases"))if(old.isJsonObject()&&hostname.equals(old.getAsJsonObject().get("hostname").getAsString()))throw new CloudRuntimeException("AD new instance cannot reuse source aliases");}JsonObject alias=new JsonObject();alias.addProperty("hostname",hostname);alias.add("addresses",bound);JsonArray aliases=new JsonArray();aliases.add(alias);JsonArray principals=new JsonArray();principals.add("cifs/"+hostname);principals.add("host/"+hostname);
        JsonObject result=new JsonObject();result.addProperty("domainName",domain);result.addProperty("realm",domain.toUpperCase(Locale.ROOT));result.addProperty("workgroup",group);result.addProperty("netbiosName",machine);result.add("dnsServers",dns);result.add("dnsAliases",aliases);result.add("servicePrincipals",principals);result.add("idmapPolicy",sourceIdentity!=null&&sourceIdentity.has("idmapPolicy")?sourceIdentity.get("idmapPolicy").deepCopy():defaultsIdmap());validateIdmap(result.getAsJsonObject("idmapPolicy"));result.addProperty("identityMode",identityMode);if(sourceIdentity!=null)result.add("sourceIdentity",sourceIdentity.deepCopy());return result;
    }
    public static void validateOrganizationalUnit(String value){if(value!=null&&(!value.matches("[A-Za-z0-9_ .,-/]{1,512}")||!value.equals(value.trim())))throw new CloudRuntimeException("AD organizational unit is invalid");}
    public static void validateCredentials(String username,String password) {if(username==null||username.isEmpty()||username.length()>256||username.chars().anyMatch(c->!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='.'||c=='@'||c==(char)92||c=='-'))||password==null||password.isEmpty()||password.length()>4096||password.indexOf('\n')>=0||password.indexOf('\r')>=0||password.indexOf(0)>=0)throw new CloudRuntimeException("AD credential input is invalid");}
    public static JsonObject join(JsonObject serviceScope,JsonObject publicConfiguration,String username,String password,String organizationalUnit) {
        if(serviceScope==null||!serviceScope.keySet().equals(Set.of("instanceUuid","operationUuid","maintenanceUuid","revision"))||!serviceScope.get("operationUuid").equals(serviceScope.get("maintenanceUuid")))throw new CloudRuntimeException("AD join requires its exact owned SERVICE maintenance scope");
        for(String field:Set.of("instanceUuid","operationUuid","maintenanceUuid"))if(!serviceScope.get(field).isJsonPrimitive()||!serviceScope.get(field).getAsJsonPrimitive().isString()||!serviceScope.get(field).getAsString().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("AD SERVICE scope UUID is invalid");JsonElement revision=serviceScope.get("revision");if(!revision.isJsonPrimitive()||!revision.getAsJsonPrimitive().isNumber()||!revision.getAsString().matches("[0-9]+"))throw new CloudRuntimeException("AD SERVICE scope revision is invalid");
        validateCredentials(username,password);validateOrganizationalUnit(organizationalUnit);
        JsonObject request=serviceScope.deepCopy();for(java.util.Map.Entry<String,JsonElement> entry:publicConfiguration.entrySet())if(!entry.getKey().equals("realm"))request.add(entry.getKey(),entry.getValue().deepCopy());request.addProperty("username",username);request.addProperty("password",password);if(organizationalUnit!=null)request.addProperty("organizationalUnit",organizationalUnit);return request;
    }
}
