// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbServiceNamesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameContextResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageSmbServiceNameDao;

/** AD names do not render SMB, change accounts/ACLs, or adopt an external identity. */
final class StorageSmbServiceNames {
    static final String OPERATION="smb service names apply";
    static final String TYPE="SmbAdServiceName";
    private static final Gson JSON=new Gson();
    private final StorageSmbServiceNameDao names;
    private final StorageServiceProtocolDao protocols;
    private final StorageIdentityDomainDao domains;
    private final StorageServiceGuestCommandDispatcher guest;
    private final Function<StorageServiceInstanceVO,String> primary;

    StorageSmbServiceNames(StorageSmbServiceNameDao names,StorageServiceProtocolDao protocols,
            StorageIdentityDomainDao domains,StorageServiceGuestCommandDispatcher guest,
            Function<StorageServiceInstanceVO,String> primary){
        this.names=names;this.protocols=protocols;this.domains=domains;this.guest=guest;this.primary=primary;
    }
    static boolean command(BaseStorageServiceConfigurationCmd cmd){return cmd instanceof BaseStorageSmbServiceNameCmd;}
    static boolean api(String value){return Arrays.asList("createStorageSmbServiceName","updateStorageSmbServiceName","deleteStorageSmbServiceName").contains(value);}
    private static void require(boolean valid,String code){if(!valid)throw new CloudRuntimeException(code);}
    private static JsonObject object(String raw){return StorageServiceConfigurationCoordinator.parsed(raw);}
    private static JsonElement field(JsonObject value,String key){
        JsonElement item=value.get(key);return item==null || item.isJsonNull()?JsonNull.INSTANCE:item;
    }
    static String canonicalFqdn(String value){
        require(value!=null,"SMB_NAME_FQDN_REQUIRED");
        String name=value.trim().toLowerCase(Locale.ROOT).replaceAll("\\.$","");
        require(name.length()<=253 && name.contains(".") && Arrays.stream(name.split("\\.",-1))
            .allMatch(label->label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")),"SMB_NAME_INVALID");
        return name;
    }
    static JsonObject entry(StorageSmbServiceNameVO row){
        JsonObject value=object(row.getDesiredJson());value.addProperty("uuid",row.getUuid());
        JsonObject owned=object(row.getOwnershipJson());if(owned.size()>0)value.add("ownership",owned);
        return value;
    }
    private StorageSmbServiceNameVO selected(StorageServiceInstanceVO instance,JsonObject intent){
        if(!intent.has("id"))return null;
        String id=intent.get("id").getAsString();
        StorageSmbServiceNameVO row=id.matches("[0-9]+")?names.findById(Long.valueOf(id)):names.findByUuid(id);
        require(row!=null && row.getInstanceId()==instance.getId(),"SMB_NAME_OUTSIDE_INSTANCE");
        return row;
    }
    JsonObject request(StorageServiceInstanceVO instance,JsonObject intent){
        require(instance.getVmId()!=null,"SMB_NAME_SYSTEM_VM_REQUIRED");
        StorageIdentityDomainVO domain=domains.findByInstanceId(instance.getId());
        require(domain!=null && domain.getJoinState()==StorageServiceInstance.DomainJoinState.JOINED,"SMB_NAME_AD_JOIN_REQUIRED");
        JsonObject ad=object(domain.getConfigJson());
        if(ad.has("adJoin")){
            JsonObject lease=ad.getAsJsonObject("adJoin");
            require(!lease.has("state") || !Arrays.asList("RUNNING","RECOVERY_REQUIRED").contains(lease.get("state").getAsString()),"SMB_NAME_AD_RECOVERY_REQUIRED");
        }
        String api=intent.get("api").getAsString();
        require(api(api),"SMB_NAME_ACTION_INVALID");
        StorageSmbServiceNameVO row=selected(instance,intent);
        require(api.startsWith("create")?row==null:row!=null,"SMB_NAME_RESOURCE_REQUIRED");
        JsonObject original=row==null?null:entry(row);
        JsonObject value=original==null?new JsonObject():original.deepCopy();
        String name=canonicalFqdn(intent.has("fqdn")?intent.get("fqdn").getAsString():row==null?null:row.getFqdn());
        require(name.endsWith("."+domain.getDomainName().toLowerCase(Locale.ROOT)),"SMB_NAME_ZONE_MISMATCH");
        if(row!=null && "BASE".equals(row.getNameKind()))require(name.equals(row.getFqdn()),"SMB_NAME_BASE_IMMUTABLE");
        String kind=intent.has("kind")?intent.get("kind").getAsString():row==null?"ALIAS":row.getNameKind();
        require(Arrays.asList("BASE","ALIAS").contains(kind),"SMB_NAME_KIND_INVALID");
        if(row!=null)require(kind.equals(row.getNameKind()),"SMB_NAME_KIND_IMMUTABLE");
        for(StorageSmbServiceNameVO other:names.listByInstanceId(instance.getId()))
            require(other.getId()==(row==null?-1:row.getId()) || !name.equals(other.getFqdn()),"SMB_NAME_DUPLICATE");
        if(row==null)require(names.listByInstanceId(instance.getId()).size()<32,"SMB_NAME_LIMIT");
        if(api.startsWith("delete")){
            require(new HashSet<>(Arrays.asList("api","id")).containsAll(intent.keySet()),"SMB_NAME_DELETE_SCOPE_INVALID");
            JsonObject deletion=new JsonObject();deletion.addProperty("action","DELETE");deletion.addProperty("instanceUuid",instance.getUuid());deletion.add("entry",original);return deletion;
        }
        value.addProperty("fqdn",name);value.addProperty("kind",kind);
        if(original!=null && !name.equals(row.getFqdn()))value.remove("ownership");
        StorageServiceProtocolVO endpoint=null;
        if(intent.has("endpointid")){
            String id=intent.get("endpointid").getAsString();
            require(id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"SMB_NAME_ENDPOINT_INVALID");
            endpoint=protocols.findByUuid(id.toLowerCase(Locale.ROOT));
            require(endpoint!=null,"SMB_NAME_ENDPOINT_INVALID");
        }else if(original!=null && original.has("endpointUuid") && !original.get("endpointUuid").isJsonNull()){
            endpoint=protocols.findByUuid(original.get("endpointUuid").getAsString());
            require(endpoint!=null,"SMB_NAME_ENDPOINT_INVALID");
        }else{
            String address=primary.apply(instance);
            for(StorageServiceProtocolVO p:protocols.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.SMB))
                if(p.isEnabled() && p.getState()==StorageServiceInstance.ResourceState.Ready && Objects.equals(p.getListenIp(),address)
                        && (p.getPort()==null || p.getPort()==445)){endpoint=p;break;}
        }
        if(endpoint!=null){
            require(endpoint.getInstanceId()==instance.getId() && endpoint.getProtocol()==StorageServiceInstance.Protocol.SMB
                    && endpoint.isEnabled() && endpoint.getState()==StorageServiceInstance.ResourceState.Ready
                    && endpoint.getListenIp()!=null && !"0.0.0.0".equals(endpoint.getListenIp()),"SMB_NAME_ENDPOINT_NOT_READY");
            value.addProperty("endpointUuid",endpoint.getUuid());value.addProperty("ip",endpoint.getListenIp());
            value.addProperty("port",endpoint.getPort()==null?445:endpoint.getPort());
        }else{
            value.remove("endpointUuid");value.addProperty("ip",primary.apply(instance));value.addProperty("port",445);
        }
        JsonObject request=new JsonObject();request.addProperty("action",api.startsWith("delete")?"DELETE":api.startsWith("update")?"UPDATE":"CREATE");
        request.addProperty("instanceUuid",instance.getUuid());request.add("entry",value);
        if(original!=null && !api.startsWith("delete"))request.add("previous",original);
        return request;
    }
    JsonArray enrollment(String raw,StorageServiceInstanceVO instance,String domain,boolean acknowledged){
        JsonArray result=new JsonArray();
        if(raw==null || raw.isBlank())return result;
        require(raw.getBytes(StandardCharsets.UTF_8).length<=16384,"SMB_NAME_LIMIT");
        JsonArray input;
        try{input=JsonParser.parseString(raw).getAsJsonArray();}catch(RuntimeException error){throw new CloudRuntimeException("SMB_NAME_ENROLLMENT_INVALID");}
        require(input.size()<=32 && (input.isEmpty() || acknowledged),"SMB_NAME_ENROLLMENT_ACK_REQUIRED");
        Set<String> unique=new HashSet<>();
        for(JsonElement item:input){
            require(item.isJsonObject(),"SMB_NAME_ENROLLMENT_INVALID");JsonObject value=item.getAsJsonObject();
            require(new HashSet<>(Arrays.asList("fqdn","endpointid")).containsAll(value.keySet()) && value.has("fqdn"),"SMB_NAME_ENROLLMENT_INVALID");
            String name=canonicalFqdn(value.get("fqdn").getAsString());require(unique.add(name),"SMB_NAME_DUPLICATE");
            require(domain!=null && name.endsWith("."+domain.toLowerCase(Locale.ROOT)),"SMB_NAME_ZONE_MISMATCH");
            JsonObject selected=new JsonObject();selected.addProperty("fqdn",name);
            if(value.has("endpointid") && !value.get("endpointid").isJsonNull()){
                StorageServiceProtocolVO endpoint=protocols.findByUuid(value.get("endpointid").getAsString());
                require(endpoint!=null && endpoint.getInstanceId()==instance.getId() && endpoint.getProtocol()==StorageServiceInstance.Protocol.SMB
                    && endpoint.isEnabled() && endpoint.getState()==StorageServiceInstance.ResourceState.Ready,"SMB_NAME_ENDPOINT_NOT_READY");
                selected.addProperty("endpointid",endpoint.getUuid());
            }
            for(StorageSmbServiceNameVO registered:names.listByInstanceId(instance.getId()))
                if(name.equals(registered.getFqdn()) && selected.has("endpointid"))
                    require(selected.get("endpointid").getAsString().equals(registered.getEndpointUuid()),"SMB_NAME_EXISTING_BINDING_CHANGED");
            result.add(selected);
        }
        return result;
    }
    JsonObject observeRegistry(StorageServiceInstanceVO instance){
        List<StorageSmbServiceNameVO> rows=names.listByInstanceId(instance.getId());
        if(rows.isEmpty()){JsonObject empty=new JsonObject();empty.add("entries",new JsonArray());return empty;}
        JsonObject query=new JsonObject();query.addProperty("instanceUuid",instance.getUuid());JsonArray entries=new JsonArray();
        rows.forEach(row->entries.add(entry(row)));query.add("entries",entries);
        JsonObject report=rpc(instance,"configuration smb-names observe",query);
        require(report.has("entries") && report.getAsJsonArray("entries").size()==rows.size(),"SMB_NAME_REJOIN_IDENTITY_UNCONFIRMED");
        Set<String> expected=new HashSet<>();rows.forEach(row->expected.add(row.getUuid()));
        for(JsonElement item:report.getAsJsonArray("entries"))
            require(item.isJsonObject() && item.getAsJsonObject().has("uuid") && expected.remove(item.getAsJsonObject().get("uuid").getAsString()),"SMB_NAME_REJOIN_IDENTITY_UNCONFIRMED");
        require(expected.isEmpty(),"SMB_NAME_REJOIN_IDENTITY_UNCONFIRMED");
        return report;
    }
    void verifyRegistry(StorageServiceInstanceVO instance){
        JsonObject report=observeRegistry(instance);
        require(report.getAsJsonArray("entries").asList().stream().allMatch(e->"MATCH".equals(e.getAsJsonObject().get("status").getAsString())),"SMB_NAME_REJOIN_IDENTITY_UNCONFIRMED");
    }
    void guardEndpoint(StorageServiceInstanceVO instance,StorageServiceProtocolVO endpoint,String ip,Integer port,boolean deleting){
        for(StorageSmbServiceNameVO row:names.listByInstanceId(instance.getId())){
            JsonObject desired=object(row.getDesiredJson());
            boolean primary=row.getEndpointUuid()==null;
            boolean referenced=endpoint!=null?Objects.equals(row.getEndpointUuid(),endpoint.getUuid())
                || primary && (endpoint.getListenIp()==null || "0.0.0.0".equals(endpoint.getListenIp())
                    || Objects.equals(desired.get("ip"),JSON.toJsonTree(endpoint.getListenIp()))):
                ip==null || ip.isBlank() || Objects.equals(field(desired,"ip"),JSON.toJsonTree(ip))
                    && Objects.equals(field(desired,"port"),JSON.toJsonTree(port==null?445:port));
            if(referenced && (deleting || primary || !Objects.equals(field(desired,"ip"),JSON.toJsonTree(ip))
                    || desired.get("port").getAsInt()!=(port==null?445:port)))
                throw new CloudRuntimeException("SMB_NAME_REFERENCES_ENDPOINT");
        }
    }
    void guardRestore(StorageServiceInstanceVO instance,JsonObject desired,Set<StorageServiceInstance.Protocol> scope){
        if(!scope.contains(StorageServiceInstance.Protocol.SMB))return;
        JsonArray listeners=desired.getAsJsonArray("listeners");
        for(StorageSmbServiceNameVO row:names.listByInstanceId(instance.getId())){
            JsonObject expected=object(row.getDesiredJson());boolean match=false;
            for(JsonElement e:listeners){
                JsonObject listener=e.getAsJsonObject();
                if(!"SMB".equals(field(listener,"protocol").getAsString()) || !listener.has("enabled") || !listener.get("enabled").getAsBoolean())continue;
                if(row.getEndpointUuid()!=null){
                    match=row.getEndpointUuid().equals(field(listener,"uuid").getAsString())
                        && Objects.equals(field(listener,"listenIp"),field(expected,"ip"))
                        && listener.has("port") && listener.get("port").getAsInt()==expected.get("port").getAsInt();
                }else{
                    JsonElement ip=field(listener,"listenIp");
                    match=(ip.isJsonNull() || "0.0.0.0".equals(ip.getAsString()))
                        && (!listener.has("port") || listener.get("port").isJsonNull() || listener.get("port").getAsInt()==expected.get("port").getAsInt())
                        && Objects.equals(primary.apply(instance),expected.get("ip").getAsString());
                }
                if(match)break;
            }
            require(match,"SMB_NAME_RESTORE_BINDING_CHANGED");
        }
    }

    private JsonObject rpc(StorageServiceInstanceVO instance,String operation,JsonObject request){
        StorageServiceGuestCommandResult result=guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(),operation,request.toString(),20,Collections.emptySet()));
        JsonObject value=object(result.getResultJson());
        if(!result.isSuccess()){
            String code=value.has("code")?value.get("code").getAsString():"SMB_NAME_OBSERVATION_UNAVAILABLE";
            require(false,code.matches("SMB_NAME_[A-Z0-9_]+")?code:"SMB_NAME_OBSERVATION_UNAVAILABLE");
        }
        return value;
    }
    JsonObject preflight(StorageServiceInstanceVO instance,JsonObject intent,Long owner){
        JsonObject report=new JsonObject(),query=new JsonObject(),impact=new JsonObject(),profile=new JsonObject();
        report.addProperty("revision",instance.getConfigurationRevision());
        report.addProperty("observedat",System.currentTimeMillis()/1000.0);report.addProperty("expiresat",System.currentTimeMillis()/1000.0+30);
        report.add("checks",new JsonArray());report.addProperty("status","READY");
        impact.addProperty("class","NO_SERVICE_RESTART");impact.addProperty("method","SMB_AD_NAMES_ONLY");
        impact.addProperty("drainrequired",false);impact.addProperty("ackrequired",true);impact.addProperty("connections",0);impact.addProperty("affectedcount",1);
        report.add("impact",impact);profile.addProperty("name","SMB_AD_NAMES");profile.addProperty("observationseconds",120);
        profile.addProperty("softdeadlineseconds",60);profile.addProperty("pollintervalseconds",2);report.add("profile",profile);
        try{
            require(instance.getActiveLifecycleOperationUuid()==null,"SMB_NAME_LIFECYCLE_WRITER_ACTIVE");
            require(instance.getActiveConfigurationOperationId()==null || Objects.equals(instance.getActiveConfigurationOperationId(),owner),"SMB_NAME_CONFIGURATION_WRITER_ACTIVE");
            JsonObject request=request(instance,intent);
            JsonObject observed=rpc(instance,"configuration smb-names preview",request);
            JsonObject enriched=observed.getAsJsonObject("entry").getAsJsonObject("entry").deepCopy();
            for(StorageSmbServiceNameVO registered:names.listByInstanceId(instance.getId()))
                require(Objects.equals(object(registered.getDesiredJson()).get("computerGuid"),enriched.get("computerGuid")),"SMB_NAME_COMPUTER_IDENTITY_CHANGED");
            JsonObject input=request.getAsJsonObject("entry");
            if(input.has("uuid"))enriched.add("uuid",input.get("uuid"));
            if(input.has("ownership"))enriched.add("ownership",input.get("ownership"));
            request.add("entry",enriched);
            query.add("smbadnames",request);query.addProperty("protocol","SMB");
            query.addProperty("instanceUuid",instance.getUuid());query.addProperty("revision",instance.getConfigurationRevision());
            report.addProperty("fingerprint",observed.get("fingerprint").getAsString());report.add("namespreview",observed);
        }catch(RuntimeException error){
            String code=error.getMessage();
            if(code==null || !code.matches("SMB_NAME_[A-Z0-9_]+"))code="SMB_NAME_OBSERVATION_UNAVAILABLE";
            report.addProperty("status",code.contains("UNAVAILABLE") || code.contains("UNCONFIRMED")?"UNKNOWN":"BLOCKED");
            report.addProperty("fingerprint","");
            JsonObject check=new JsonObject();check.addProperty("code",code);check.addProperty("blocking",true);check.addProperty("severity","ERROR");report.getAsJsonArray("checks").add(check);
        }
        report.add("intent",query);return report;
    }
    StorageSmbServiceNameResponse candidate(StorageServiceInstanceVO instance,StorageServiceConfigurationPlan plan,BaseStorageSmbServiceNameCmd cmd){
        require(cmd.getExpectedRevision()!=null,"SMB_NAME_REVISION_REQUIRED");
        JsonObject request=plan.directoryReport.getAsJsonObject("intent").getAsJsonObject("smbadnames").deepCopy();
        StorageSmbServiceNameDao view=plan.dao(StorageSmbServiceNameDao.class);
        StorageSmbServiceNameVO row=cmd.getId()==null?null:view.findById(cmd.getId());
        if(cmd instanceof CreateStorageSmbServiceNameCmd){
            require(row==null,"SMB_NAME_DUPLICATE");row=new StorageSmbServiceNameVO();row.setInstanceId(instance.getId());
            JsonObject seed=request.getAsJsonObject("entry");
            row.setFqdn(seed.get("fqdn").getAsString());row.setCanonicalHash(DigestUtils.sha256Hex(row.getFqdn()));
            row.setNameKind(seed.get("kind").getAsString());row.setBindingType(seed.has("endpointUuid")?"EXPLICIT":"PRIMARY");
            row.setDesiredJson(withoutOwnership(seed).toString());row=view.persist(row);
        }else require(row!=null && row.getInstanceId()==instance.getId(),"SMB_NAME_OUTSIDE_INSTANCE");
        JsonObject desired=request.getAsJsonObject("entry");desired.addProperty("uuid",row.getUuid());
        request.addProperty("operationid",plan.operationUuid);
        row.setFqdn(desired.get("fqdn").getAsString());row.setCanonicalHash(DigestUtils.sha256Hex(row.getFqdn()));
        row.setNameKind(desired.get("kind").getAsString());row.setBindingType(desired.has("endpointUuid")?"EXPLICIT":"PRIMARY");
        row.setEndpointUuid(desired.has("endpointUuid")?desired.get("endpointUuid").getAsString():null);
        row.setDesiredJson(withoutOwnership(desired).toString());row.setRevision(plan.targetRevision);row.setState(StorageServiceInstance.ResourceState.Ready);row.setUpdated(new Date());
        if(cmd instanceof DeleteStorageSmbServiceNameCmd)view.remove(row.getId());else view.update(row.getId(),row);
        plan.commands.put(OPERATION,request);
        return response(instance,row,object(row.getObservedJson()));
    }
    static JsonObject withoutOwnership(JsonObject value){JsonObject copy=value.deepCopy();copy.remove("ownership");copy.remove("deleted");return copy;}
    static StorageSmbServiceNameResponse response(StorageServiceInstanceVO instance,StorageSmbServiceNameVO row,JsonObject observation){
        StorageSmbServiceNameResponse result=new StorageSmbServiceNameResponse();result.setId(row.getUuid());result.setInstanceid(instance.getUuid());
        result.setFqdn(row.getFqdn());result.setKind(row.getNameKind());result.setEndpointid(row.getEndpointUuid());result.setState(row.getState().name());
        JsonObject desired=object(row.getDesiredJson());result.setDesired(desired);result.setOwnership(object(row.getOwnershipJson()));result.setObservation(observation);
        result.setRevision(row.getRevision());if(desired.has("ip"))result.setIp(desired.get("ip").getAsString());if(desired.has("port"))result.setPort(desired.get("port").getAsInt());
        return result;
    }
    StorageSmbServiceNameContextResponse context(StorageServiceInstanceVO instance){
        JsonObject context=new JsonObject();
        StorageIdentityDomainVO domain=domains.findByInstanceId(instance.getId());
        if(domain==null || domain.getJoinState()!=StorageServiceInstance.DomainJoinState.JOINED){
            context.addProperty("status","NOT_JOINED");context.addProperty("code","SMB_NAME_AD_JOIN_REQUIRED");
        }else if(instance.getVmId()==null){
            context.addProperty("status","UNKNOWN");context.addProperty("code","SMB_NAME_SYSTEM_VM_REQUIRED");
        }else{
            JsonObject query=new JsonObject();query.addProperty("instanceUuid",instance.getUuid());query.add("entries",new JsonArray());
            try{
                JsonObject capability=rpc(instance,"configuration capability show",new JsonObject());
                require(capability.has("smbAdServiceNamesVersion") && capability.get("smbAdServiceNamesVersion").getAsInt()==1,"SMB_NAME_CAPABILITY_REQUIRED");
                JsonObject observed=rpc(instance,"configuration smb-names observe",query);
                require(observed.has("context") && observed.get("context").isJsonObject(),"SMB_NAME_CONTEXT_RESPONSE_INVALID");
                context=observed.getAsJsonObject("context");
            }
            catch(RuntimeException error){context.addProperty("status","UNKNOWN");context.addProperty("code",error.getMessage());}
        }
        JsonObject saved=domain==null?new JsonObject():object(domain.getConfigJson());
        if(saved.has("namesEnrollment"))context.add("enrollment",saved.get("namesEnrollment"));
        StorageSmbServiceNameContextResponse response=new StorageSmbServiceNameContextResponse();response.setContext(context);return response;
    }
    ListResponse<StorageSmbServiceNameResponse> list(StorageServiceInstanceVO instance,ListStorageSmbServiceNamesCmd cmd){
        List<StorageSmbServiceNameVO> rows=names.listByInstanceId(instance.getId());
        Map<String,JsonObject> observed=new HashMap<>();
        if(cmd.isRefresh() && instance.getVmId()!=null){
            JsonObject query=new JsonObject();query.addProperty("instanceUuid",instance.getUuid());JsonArray entries=new JsonArray();
            rows.forEach(row->entries.add(entry(row)));query.add("entries",entries);
            try{
                JsonObject value=rpc(instance,"configuration smb-names observe",query);
                for(JsonElement item:value.getAsJsonArray("entries"))observed.put(item.getAsJsonObject().get("uuid").getAsString(),item.getAsJsonObject());
            }catch(RuntimeException error){
                for(StorageSmbServiceNameVO row:rows){JsonObject unknown=new JsonObject();unknown.addProperty("status","UNKNOWN");unknown.addProperty("code",error.getMessage());observed.put(row.getUuid(),unknown);}
            }
        }
        List<StorageSmbServiceNameResponse> values=new ArrayList<>();
        long size=cmd.getPageSizeVal()==null?32:Math.min(32,cmd.getPageSizeVal());int start=(int)Math.min(cmd.getStartIndex()==null?0:cmd.getStartIndex(),rows.size());
        int end=(int)Math.min(start+size,rows.size());
        for(StorageSmbServiceNameVO row:rows.subList(start,end))values.add(response(instance,row,observed.getOrDefault(row.getUuid(),object(row.getObservedJson()))));
        ListResponse<StorageSmbServiceNameResponse> result=new ListResponse<StorageSmbServiceNameResponse>();result.setResponses(values,rows.size());return result;
    }

    private static JsonElement canonical(JsonElement value){
        if(value.isJsonObject()){JsonObject sorted=new JsonObject();new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key->{
            JsonElement item=value.getAsJsonObject().get(key);if(!item.isJsonNull())sorted.add(key,canonical(item));});return sorted;}
        if(value.isJsonArray()){JsonArray array=new JsonArray();value.getAsJsonArray().forEach(item->array.add(canonical(item)));return array;}
        return value.deepCopy();
    }
    static String wireHash(JsonObject request){return DigestUtils.sha256Hex(JSON.toJson(canonical(request)).getBytes(StandardCharsets.UTF_8));}
    private static JsonObject acceptedCommand(StorageServiceConfigurationOperationVO op){
        JsonObject saved=object(op.getCandidateSnapshot());require(saved.has("smbServiceNameCommand"),"SMB_NAME_RECEIPT_MISSING");
        return saved.getAsJsonObject("smbServiceNameCommand");
    }
    private static void identity(StorageServiceInstanceVO instance,StorageServiceConfigurationOperationVO op,JsonObject receipt,JsonObject command){
        require(receipt.get("version").getAsInt()==1 && "VERIFIED".equals(receipt.get("state").getAsString())
            && instance.getUuid().equals(receipt.get("instanceUuid").getAsString())
            && op.getUuid().equals(receipt.get("operationid").getAsString())
            && wireHash(command).equals(receipt.get("requestHash").getAsString()),"SMB_NAME_RECEIPT_MISMATCH");
    }
    /** Only this unchanged, single-purpose plan is exempt from unrelated SMB/POSIX receipts. */
    static boolean namesOnly(StorageServiceConfigurationPlan plan, StorageServiceConfigurationOperationVO op) {
        if (!TYPE.equals(op.getResourceType())) return false;
        JsonObject saved = object(op.getCandidateSnapshot());
        require("SMB".equals(op.getProtocol()) && saved.has("smbServiceNameCommand")
            && plan.networkChanges.isEmpty() && !saved.has("restoreProof")
            && (!saved.has("onlineSmbOptions") || !saved.get("onlineSmbOptions").getAsBoolean())
            && (!saved.has("onlineSmbClientAccess") || !saved.get("onlineSmbClientAccess").getAsBoolean()),
            "SMB_NAME_PLAN_SCOPE_MISMATCH");
        // Recovery restores the durable command separately and has no new guest commands.
        require(plan.commands.isEmpty() || plan.commands.size() == 1
            && Objects.equals(plan.commands.get(OPERATION), saved.get("smbServiceNameCommand")),
            "SMB_NAME_PLAN_SCOPE_MISMATCH");
        plan.retainOnlyChanges(StorageSmbServiceNameDao.class, op.getBeforeSnapshot());
        return true;
    }
    static void applyVerified(StorageServiceConfigurationPlan plan,StorageServiceInstanceVO instance,StorageServiceConfigurationOperationVO op,JsonObject observation){
        if(!TYPE.equals(op.getResourceType()))return;
        JsonObject receipt=observation.getAsJsonObject("smbServiceNameReceipt"),command=acceptedCommand(op);
        require(receipt!=null,"SMB_NAME_RECEIPT_MISSING");identity(instance,op,receipt,command);
        JsonObject result=receipt.getAsJsonObject("entry"),expected=command.getAsJsonObject("entry");
        for(String key:Arrays.asList("uuid","fqdn","kind","ip","port","endpointUuid","computerGuid","computerSid","realm","dc","zone"))
            require(Objects.equals(field(expected,key),field(result,key)),"SMB_NAME_RECEIPT_MISMATCH");
        require(result.has("ownership"),"SMB_NAME_RECEIPT_MISSING");
        JsonObject ownership=result.getAsJsonObject("ownership");
        require(new HashSet<>(Arrays.asList("dns","spn","ip","nodeGuid","computerGuid")).equals(ownership.keySet())
            && Objects.equals(ownership.get("computerGuid"),result.get("computerGuid"))
            && Objects.equals(ownership.get("ip"),result.get("ip"))
            && Arrays.asList("CREATED","REUSED").contains(ownership.get("dns").getAsString())
            && Arrays.asList("CREATED","REUSED").contains(ownership.get("spn").getAsString())
            && ownership.get("nodeGuid").getAsString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
            "SMB_NAME_RECEIPT_MISMATCH");
        JsonObject accepted=object(op.getCandidateSnapshot());
        require(accepted.has("smbServiceNameBefore"),"SMB_NAME_RECEIPT_MISSING");
        JsonObject prior=accepted.getAsJsonObject("smbServiceNameBefore").getAsJsonObject("entry");
        for(String key:Arrays.asList("dns","spn"))require(Objects.equals(prior.getAsJsonObject("ownership").get(key),ownership.get(key)),"SMB_NAME_RECEIPT_MISMATCH");
        if(!"DELETE".equals(command.get("action").getAsString()) && prior.getAsJsonArray("addresses").asList().stream().anyMatch(ip->Objects.equals(ip,result.get("ip"))))
            require(Objects.equals(prior.get("nodeGuid"),ownership.get("nodeGuid")),"SMB_NAME_RECEIPT_MISMATCH");
        if("DELETE".equals(command.get("action").getAsString())){require(result.has("deleted") && result.get("deleted").getAsBoolean(),"SMB_NAME_DELETE_UNCONFIRMED");return;}
        StorageSmbServiceNameDao dao=plan.dao(StorageSmbServiceNameDao.class);
        StorageSmbServiceNameVO row=dao.findByUuid(result.get("uuid").getAsString());
        require(row!=null && row.getInstanceId()==instance.getId(),"SMB_NAME_RECEIPT_MISMATCH");
        row.setDesiredJson(withoutOwnership(result).toString());row.setOwnershipJson(result.get("ownership").toString());row.setObservedJson(receipt.toString());row.setUpdated(new Date());dao.update(row.getId(),row);
        JsonObject saved=object(op.getCandidateSnapshot());saved.add("tables",JsonParser.parseString(plan.snapshot()));
        saved.add("response",JSON.toJsonTree(response(instance,row,receipt)));op.setCandidateSnapshot(saved.toString());
    }
    /** Must execute inside the coordinator's locked SQL rollback finalization transaction. */
    static void commitRollback(StorageSmbServiceNameDao dao,StorageServiceInstanceVO instance,StorageServiceConfigurationOperationVO op){
        if(!TYPE.equals(op.getResourceType()))return;
        if("PENDING".equals(op.getState()) || "VALIDATING".equals(op.getPhase()))return;
        JsonObject outcome=object(op.getGuestObservationJson());
        JsonObject noChange=outcome.has("rollback")?outcome.getAsJsonObject("rollback"):new JsonObject();
        JsonObject first=outcome.has("originalfailure")?outcome.getAsJsonObject("originalfailure"):new JsonObject();
        if("NOT_NEEDED".equals(field(noChange,"state").isJsonNull()?"":field(noChange,"state").getAsString())
                && "VALIDATING".equals(field(first,"phase").isJsonNull()?"":field(first,"phase").getAsString()))return;
        require(dao!=null && Objects.equals(instance.getActiveConfigurationOperationId(),op.getId())
            && instance.getConfigurationRevision()==op.getBaseRevision(),"SMB_NAME_ROLLBACK_RESERVATION_CHANGED");
        JsonObject observation=object(op.getGuestObservationJson()),receipt=observation.getAsJsonObject("smbServiceNameRollback"),command=acceptedCommand(op);
        require(receipt!=null,"SMB_NAME_ROLLBACK_RECEIPT_MISSING");identity(instance,op,receipt,command);
        JsonArray baseline=object(op.getBeforeSnapshot()).getAsJsonArray("StorageSmbServiceNameVO");
        require(baseline!=null,"SMB_NAME_ROLLBACK_RECEIPT_MISSING");
        Map<String,StorageSmbServiceNameVO> originals=new HashMap<>();for(JsonElement item:baseline){StorageSmbServiceNameVO row=JSON.fromJson(item,StorageSmbServiceNameVO.class);originals.put(row.getUuid(),row);}
        Set<String> seen=new HashSet<>();
        for(JsonElement item:receipt.getAsJsonArray("renewals")){
            JsonObject renewal=item.getAsJsonObject();String id=renewal.get("uuid").getAsString();
            StorageSmbServiceNameVO before=originals.get(id),current=dao.findByUuid(id);
            require(seen.add(id) && before!=null && current!=null && current.getInstanceId()==instance.getId()
                && before.getInstanceId()==instance.getId() && before.getRevision()==current.getRevision()
                && Objects.equals(object(before.getDesiredJson()),object(current.getDesiredJson()))
                && Objects.equals(object(before.getOwnershipJson()),object(current.getOwnershipJson())),"SMB_NAME_ROLLBACK_METADATA_CHANGED");
            JsonObject desired=object(before.getDesiredJson()),old=object(before.getOwnershipJson()),after=renewal.getAsJsonObject("ownership");
            require("VERIFIED_OWN_ROLLBACK".equals(renewal.get("reason").getAsString())
                && Objects.equals(old,renewal.get("expectedOwnership")) && "CREATED".equals(old.get("dns").getAsString())
                && Objects.equals(old.get("computerGuid"),renewal.get("computerGuid"))
                && Objects.equals(desired.get("computerGuid"),renewal.get("computerGuid"))
                && Objects.equals(desired.get("computerSid"),renewal.get("computerSid"))
                && Objects.equals(desired.get("fqdn"),renewal.get("fqdn")) && Objects.equals(desired.get("ip"),renewal.get("ip"))
                && !Objects.equals(old.get("nodeGuid"),after.get("nodeGuid"))
                && after.get("nodeGuid").getAsString().matches("[0-9a-f-]{36}"),"SMB_NAME_ROLLBACK_RECEIPT_MISMATCH");
            JsonObject onlyGuid=old.deepCopy();onlyGuid.add("nodeGuid",after.get("nodeGuid"));
            require(onlyGuid.equals(after),"SMB_NAME_ROLLBACK_RECEIPT_MISMATCH");
            StorageSmbServiceNameVO update=dao.createForUpdate(current.getId());update.setOwnershipJson(after.toString());
            JsonObject observed=object(current.getObservedJson());observed.addProperty("rollbackOperation",op.getUuid());observed.add("rollbackReceipt",receipt);
            update.setObservedJson(observed.toString());update.setUpdated(new Date());
            require(dao.update(current.getId(),update),"SMB_NAME_ROLLBACK_METADATA_CHANGED");
        }
    }
}
