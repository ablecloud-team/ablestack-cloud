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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Alternate authority only for an unexposed first declaration on a protected NEW private-template fixture. */
public final class StorageLegacyDeferredFixtureTargetRecovery {
    public static final String RESULT_KEY = "_legacyDeferredFixtureTargetRecovery";
    private static final List<String> TABLES = List.of("storage_service_protocol", "storage_file_share", "storage_block_target",
            "storage_identity_domain", "storage_access_rule", "storage_posix_directory_policy");
    private StorageLegacyDeferredFixtureTargetRecovery() { }

    public static boolean eligible(StorageServiceOperationVO operation) {
        try {
            JsonObject result = object(operation.getResultJson());
            return "createstoragenfsexportresponse".equals(operation.getAction())
                    && Set.of("RUNNING", "RECOVERY_REQUIRED").contains(operation.getState())
                    && operation.getSnapshotJson()!=null && result.has("_response")
                    && !result.has("_targetResourceBindings") && !result.has("_targetNativeBootId")
                    && !result.has("_targetConfigurationSha256");
        } catch (RuntimeException malformed) { return false; }
    }

    /** creation must be returned by protected StorageTemplateFixturePermit.requireCreated, never by a request. */
    public static JsonObject require(String instanceUuid, StorageServiceOperationVO operation, String currentSnapshot,
            JsonObject currentCreatedBindings, JsonObject creation, JsonObject nativeStatus,
            JsonObject history, JsonObject identity, long dataVolumeId) {
        try {
            need(eligible(operation),"Legacy fixture authority requires all three original witnesses to be absent");
            need(object(operation.getSnapshotJson()).equals(object(currentSnapshot)),"Durable TARGET declaration changed");
            need(StorageOperationReconciliation.committedTarget(instanceUuid,operation,nativeStatus),"Native TARGET is not this exact committed operation");
            JsonObject previous=object(operation.getPreviousSnapshotJson());
            need(previous.has("nativeGeneration"),"Original native checkpoint is absent");
            Map<String,JsonArray> source=tables(previous,operation.getInstanceId()),target=tables(object(currentSnapshot),operation.getInstanceId());
            for(String table:TABLES) {
                if("storage_service_protocol".equals(table)) {
                    need(source.get(table).size()<=1 && target.get(table).size()==1,"NFS protocol declaration is ambiguous");
                    for(JsonArray rows:List.of(source.get(table),target.get(table)))
                        for(JsonElement row:rows) need("NFS".equals(text(row.getAsJsonObject(),"protocol"))
                                && flag(row.getAsJsonObject(),"enabled"),"Another or disabled protocol is configured");
                } else if(!"storage_file_share".equals(table))
                    need(source.get(table).size()==0 && target.get(table).size()==0,"Existing protocol, identity or user data is outside this authority");
            }
            need(source.get("storage_file_share").size()==0 && target.get("storage_file_share").size()==1,"Only the first new file share is eligible");
            JsonObject share=target.get("storage_file_share").get(0).getAsJsonObject();
            JsonObject result=object(operation.getResultJson()),response=result.getAsJsonObject("_response");
            need("NFS".equals(text(share,"protocol")) && (!response.has("protocol") || "NFS".equals(text(response,"protocol")))
                    && !response.has("type") && !response.has("objectName") && !response.has("responseName")
                    && "Allocated".equals(text(share,"state")) && "Allocated".equals(text(response,"state"))
                    && text(share,"uuid").equals(text(response,"id")) && number(share,"volume_id")==dataVolumeId,"Declaration is exposed or response scope differs");
            JsonObject config=object(text(share,"config_json"));need("FORMAT_EMPTY".equals(text(config,"importMode")),"Original formatting intent is absent");
            JsonObject durableOperation=config.getAsJsonObject("lastInspection").getAsJsonObject("operation");
            JsonObject durableReceipt=durableOperation.getAsJsonObject("formatterSuccessReceipt");
            JsonObject bindings=creation.getAsJsonObject("bindings"),request=creation.getAsJsonObject("request");
            need(bindings.equals(currentCreatedBindings),"Immutable creation VM/ROOT/DATA tuple changed");
            JsonObject root=bindings.getAsJsonObject("rootDisk"),data=bindings.getAsJsonObject("dataDisk");
            for(JsonObject disk:List.of(root,data)) {
                need("Ready".equals(text(disk,"state")) && flag(disk,"attachedToFixture") && flag(disk,"notRemoved")
                        && Set.of("SPARSE","FAT").contains(text(disk,"provisioningType")) && number(disk,"sizeBytes")>0,"Created disk is not attached Ready SPARSE/FAT");
            }
            need("ROOT".equals(text(root,"type")) && "DATADISK".equals(text(data,"type"))
                    && text(root,"volumeUuid").equals(text(bindings,"rootVolumeUuid"))
                    && text(data,"volumeUuid").equals(text(bindings,"dataVolumeUuid"))
                    && "NEW".equals(text(request,"backingVolumeMode")) && flag(data,"newDataWithoutBacking")
                    && data.has("templateId") && data.get("templateId").isJsonNull()
                    && text(root,"provisioningType").equals(text(request,"rootProvisioningType"))
                    && text(data,"provisioningType").equals(text(request,"dataProvisioningType")),"Creation is not an exact single NEW DATA fixture");
            need((!response.has("instanceid") || instanceUuid.equals(text(response,"instanceid")))
                    && (!response.has("volumeid") || text(data,"volumeUuid").equals(text(response,"volumeid")))
                    && (!response.has("volumeuuid") || text(data,"volumeUuid").equals(text(response,"volumeuuid"))),"Response instance or DATA identity differs");
            StorageVolumePreparationProof.requireCompleted(history,identity);
            JsonObject liveOperation=history.getAsJsonObject("operation"),liveReceipt=liveOperation.getAsJsonObject("formatterSuccessReceipt");
            String boot=text(nativeStatus,"bootId");
            need("COMPLETE".equals(text(history,"status")) && "COMPLETE".equals(text(liveOperation,"phase"))
                    && "COMPLETE".equals(text(durableOperation,"phase")) && durableReceipt.equals(liveReceipt)
                    && boot.equals(text(history,"bootId")) && boot.equals(text(durableReceipt,"formatterBootId"))
                    && "SKIP_DISCARD".equals(text(durableReceipt,"formatDiscardPolicy"))
                    && text(data,"volumeUuid").equals(text(durableReceipt,"volumeUuid"))
                    && number(data,"sizeBytes")==number(durableReceipt,"sizeBytes"),"Durable precommit formatter receipt differs from current exact filesystem");
            JsonObject owner=durableReceipt.getAsJsonObject("originalFormatOwner");
            need(owner.keySet().equals(Set.of("instanceUuid","managerOperationUuid","revision"))
                    && instanceUuid.equals(text(owner,"instanceUuid")) && operation.getUuid().equals(text(owner,"managerOperationUuid"))
                    && number(owner,"revision")==operation.getRevision(),"Original formatter owner is foreign");
            double formatted=epoch(durableReceipt,"verifiedEpoch"),committed=epoch(nativeStatus.getAsJsonObject("generation"),"verifiedAt");
            need(formatted<committed,"Formatter receipt was not durable before the native commit");
            for(String field:List.of("volumeUuid","filesystemUuid","filesystem","formatterPid","formatterStartTicks",
                    "formatterBootId","formatterOptions","formatDiscardPolicy","originalFormatOwner","formatterExitCode"))
                need(durableOperation.has(field) && durableOperation.get(field).equals(durableReceipt.get(field)),"Durable formatter operation and receipt disagree");
            JsonObject authority=new JsonObject();authority.addProperty("schemaVersion",1);
            authority.addProperty("kind","LEGACY_DEFERRED_FIXTURE_TARGET_RECOVERY");
            authority.addProperty("instanceUuid",instanceUuid);authority.addProperty("operationUuid",operation.getUuid());
            authority.addProperty("revision",operation.getRevision());authority.addProperty("bootId",boot);
            authority.addProperty("configurationSha256",text(nativeStatus,"configurationSha256"));
            authority.addProperty("creationReceiptSha256",sha(creation.toString()));
            authority.addProperty("targetSnapshotSha256",sha(operation.getSnapshotJson()));
            authority.addProperty("responseSha256",sha(response.toString()));
            authority.addProperty("formatterReceiptSha256",sha(durableReceipt.toString()));
            authority.addProperty("dataVolumeUuid",text(data,"volumeUuid"));
            authority.addProperty("historicalTargetWitnessesBackfilled",false);return authority;
        } catch (RuntimeException malformed) {
            throw new CloudRuntimeException("Legacy deferred fixture TARGET requires protected creation, durable precommit formatter and exact fresh native/physical proof",malformed);
        }
    }
    private static Map<String,JsonArray> tables(JsonObject snapshot,long instanceId) {
        need(number(snapshot,"schemaVersion")==2 && number(snapshot,"instanceId")==instanceId,"Snapshot scope differs");
        Map<String,JsonArray> result=new HashMap<>();
        for(JsonElement item:snapshot.getAsJsonArray("tables")) {
            JsonObject entry=item.getAsJsonObject();String table=text(entry,"table");
            need(TABLES.contains(table) && result.put(table,entry.getAsJsonArray("rows"))==null
                    && entry.has("columns") && entry.get("columns").isJsonArray(),"Snapshot table is invalid");
            for(JsonElement itemRow:entry.getAsJsonArray("rows")) {
                JsonObject row=itemRow.getAsJsonObject();
                need("storage_access_rule".equals(table) || number(row,"instance_id")==instanceId,"Snapshot row belongs to another instance");
                need(!row.has("removed") || row.get("removed").isJsonNull(),"Snapshot row is removed");
            }
        }
        need(result.keySet().equals(Set.copyOf(TABLES)),"Snapshot is incomplete");return result;
    }
    private static void need(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
    private static JsonObject object(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static String text(JsonObject value,String field) {
        JsonElement item=value.get(field);need(item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString(),"Missing string");return item.getAsString();
    }
    private static long number(JsonObject value,String field) {
        JsonElement item=value.get(field);need(item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber()
                && item.getAsString().matches("[0-9]+"),"Missing whole number");return item.getAsBigDecimal().longValueExact();
    }
    private static boolean flag(JsonObject value,String field) {
        JsonElement item=value.get(field);need(item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean(),"Missing boolean");return item.getAsBoolean();
    }
    private static double epoch(JsonObject value,String field) {
        JsonElement item=value.get(field);need(item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber(),"Missing epoch");
        double number=item.getAsDouble();need(Double.isFinite(number)&&number>0,"Invalid epoch");return number;
    }
    private static String sha(String value) { return StorageConfigArchive.sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
