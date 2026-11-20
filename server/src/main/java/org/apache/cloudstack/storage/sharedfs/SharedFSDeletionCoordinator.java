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
package org.apache.cloudstack.storage.sharedfs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.function.Consumer;
/** Deletion is a monotonic saga, never a configuration rollback. */
public final class SharedFSDeletionCoordinator {
    public interface Port {
        void validate(JsonObject plan);
        void stopAndConfirm(JsonObject plan);
        void softDestroy(JsonObject plan);
        JsonObject observeVolume(JsonObject volume);
        void detach(JsonObject plan,JsonObject volume);
        boolean vmRemoved(JsonObject plan);
        void removeRootOnlyVm(JsonObject plan);
        void deleteSelected(JsonObject volume);
        void cleanupMetadata(JsonObject plan);
    }
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code){super(code);this.code=code;}
        public Failure(String code,Throwable cause){super(code,cause);this.code=code;}
    }
    private static final org.apache.logging.log4j.Logger LOG=org.apache.logging.log4j.LogManager.getLogger(SharedFSDeletionCoordinator.class);
    private final Port port;
    private final Consumer<SharedFSDeletionOperationVO> journal;
    public SharedFSDeletionCoordinator(Port port,Consumer<SharedFSDeletionOperationVO> journal){this.port=port;this.journal=journal;}
    static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null;}
    static boolean yes(JsonObject o,String k){return o.has(k)&&o.get(k).getAsBoolean();}
    static void require(boolean ok,String code){if(!ok)throw new Failure(code);}
    public static void identity(JsonObject expected,JsonObject observed){
        require(observed!=null,"DATA_MISSING");
        for(String k:Arrays.asList("id","uuid","accountid","zoneid","type","size","poolid"))
            require(Objects.equals(text(expected,k),text(observed,k)),"VOLUME_IDENTITY_CHANGED");
    }
    private void save(SharedFSDeletionOperationVO op,JsonObject result,String phase){
        op.setPhase(phase);op.setResultJson(result.toString());op.setUpdated(new Date());journal.accept(op);
    }
    public JsonObject execute(SharedFSDeletionOperationVO op,boolean immediate){
        if(java.util.Arrays.asList("COMPLETED","REJECTED","CANCELLED").contains(op.getState()))return response(op);
        JsonObject plan=JsonParser.parseString(op.getPlanJson()).getAsJsonObject();
        JsonObject result=op.getResultJson()==null?new JsonObject():JsonParser.parseString(op.getResultJson()).getAsJsonObject();
        // Persist promotion before any expunge effect. A replay of the original soft
        // request or a worker restart must never downgrade a partially executed expunge.
        if (immediate) result.addProperty("expungeRequested", true);
        immediate = immediate || yes(result, "expungeRequested");
        if(!result.has("volumes"))result.add("volumes",new JsonObject());
        JsonObject rows=result.getAsJsonObject("volumes");
        op.setState("RUNNING");save(op,result,"PLAN_VALIDATED");
        String activeVolume=null;
        try{
            port.validate(plan);
            if(!yes(result,"vmRemoved")){
                save(op,result,"STOPPING_VM");port.stopAndConfirm(plan);
                result.addProperty("vmStopped",true);save(op,result,"VM_STOP_CONFIRMED");
            }
            if(!immediate){
                port.softDestroy(plan);op.setState("SOFT_DESTROYED");save(op,result,"SOFT_DESTROYED");return response(op);
            }
            for(JsonElement e:plan.getAsJsonArray("volumes")){
                JsonObject v=e.getAsJsonObject();if(!"DATADISK".equals(text(v,"type")))continue;
                activeVolume=text(v,"uuid");
                if(yes(result,"vmRemoved")){require(rows.has(activeVolume),"DETACH_RECEIPT_MISSING");continue;}
                JsonObject actual=port.observeVolume(v);identity(v,actual);
                String attached=text(actual,"attachedvm");
                require(attached==null||Objects.equals(attached,text(plan,"vmuuid")),"VOLUME_ATTACHED_ELSEWHERE");
                if(attached!=null){
                    rows.addProperty(activeVolume,"DETACHING");save(op,result,"DETACHING_DATA");
                    port.detach(plan,v);
                    actual=port.observeVolume(v);identity(v,actual);
                    require(text(actual,"attachedvm")==null,"DATA_DETACH_UNCONFIRMED");
                }
                require(!yes(actual,"removed")&&"Ready".equals(text(actual,"state")),"DATA_DETACH_UNCONFIRMED");
                rows.addProperty(activeVolume,"DETACHED_PRESERVED");save(op,result,"DETACHING_DATA");
            }
            activeVolume=null;save(op,result,"DATA_DETACHED_VERIFIED");
            if(!yes(result,"vmRemoved")){
                if(!port.vmRemoved(plan)){save(op,result,"VM_ROOT_REMOVING");port.removeRootOnlyVm(plan);}
                require(port.vmRemoved(plan),"VM_ROOT_REMOVAL_UNCONFIRMED");
                result.addProperty("vmRemoved",true);save(op,result,"VM_ROOT_REMOVED_VERIFIED");
            }
            for(JsonElement e:plan.getAsJsonArray("volumes")){
                JsonObject v=e.getAsJsonObject();if(!yes(v,"delete"))continue;activeVolume=text(v,"uuid");
                JsonObject actual=port.observeVolume(v);
                if("DELETED".equals(text(rows,activeVolume)))continue;
                if(actual==null||yes(actual,"removed")||"Expunged".equals(text(actual,"state"))){
                    require("DELETING".equals(text(rows,activeVolume)),"SELECTED_DATA_MISSING_BEFORE_DELETE");
                }else{
                    identity(v,actual);require(text(actual,"attachedvm")==null,"VOLUME_ATTACHED_ELSEWHERE");
                    require(!yes(actual,"deleteprotection")&&!yes(actual,"shared")&&!yes(actual,"externalrefs"),"SELECTED_DATA_PROTECTED");
                    rows.addProperty(activeVolume,"DELETING");save(op,result,"DELETING_SELECTED_DATA");
                    port.deleteSelected(v);
                    actual=port.observeVolume(v);
                    require(actual==null||yes(actual,"removed")||"Expunged".equals(text(actual,"state")),"DATA_DELETE_UNCONFIRMED");
                }
                rows.addProperty(activeVolume,"DELETED");save(op,result,"DELETING_SELECTED_DATA");
            }
            activeVolume=null;save(op,result,"CLEANUP_METADATA");port.cleanupMetadata(plan);
            op.setState("COMPLETED");op.setCompleted(new Date());save(op,result,"COMPLETED");
        }catch(RuntimeException ex){
            String failedPhase=op.getPhase();
            boolean noEffects=!yes(result,"vmStopped")&&!yes(result,"vmRemoved")&&rows.size()==0;
            op.setState(noEffects&&"PLAN_VALIDATED".equals(failedPhase)?"REJECTED":"RECOVERY_REQUIRED");
            JsonObject failure=new JsonObject();failure.addProperty("code",ex instanceof Failure?((Failure)ex).code:"DELETION_STEP_FAILED");
            failure.addProperty("phase",failedPhase);if(activeVolume!=null)failure.addProperty("volumeuuid",activeVolume);
            failure.addProperty("type",ex.getClass().getSimpleName());
            if(ex.getCause()!=null)failure.addProperty("causeType",ex.getCause().getClass().getSimpleName());
            LOG.warn("SharedFS deletion {} failed in {} for volume {}",op.getUuid(),failedPhase,activeVolume,ex);
            // Do not put exception messages/credentials or mutable failure replacements in the journal.
            op.setOriginalFailureJson(failure.toString());result.add("latestfailure",failure);save(op,result,failedPhase);
        }
        return response(op);
    }
    public static JsonObject response(SharedFSDeletionOperationVO op){
        JsonObject r=new JsonObject();r.addProperty("operationid",op.getUuid());r.addProperty("requestid",op.getRequestId());
        r.addProperty("sharedfsuuid",op.getSharedFsUuid());r.addProperty("state",op.getState());r.addProperty("phase",op.getPhase());
        r.addProperty("success",Arrays.asList("COMPLETED","SOFT_DESTROYED").contains(op.getState()));
        r.addProperty("created",op.getCreated().toInstant().toString());r.addProperty("updated",op.getUpdated().toInstant().toString());
        r.addProperty("canresume",java.util.Arrays.asList("RECOVERY_REQUIRED","PLANNED").contains(op.getState()));
        if(op.getResultJson()!=null)r.add("progress",JsonParser.parseString(op.getResultJson()));
        if(op.getOriginalFailureJson()!=null)r.add("originalfailure",JsonParser.parseString(op.getOriginalFailureJson()));
        r.add("plan",JsonParser.parseString(op.getPlanJson()));return r;
    }
}
