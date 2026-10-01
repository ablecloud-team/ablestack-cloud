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
package com.cloud.vm.process;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.VmProcessAction;
import com.cloud.agent.api.VmProcessActionAnswer;
import com.cloud.agent.api.VmProcessActionCommand;
import com.cloud.agent.api.VmProcessProfile;
import com.cloud.agent.api.VmProcessSnapshot;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.GsonBuilder;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.response.VmProcessActionResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.vm.process.VmProcessProfileService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;

/** VM-scoped immutable versions. Retirement and dispatch serialize on the profile row. */
public class VmProcessProfileServiceImpl extends com.cloud.utils.component.ManagerBase
        implements VmProcessProfileService, com.cloud.utils.component.PluggableService {
    @Inject private UserVmDao vmDao;
    @Inject private HostDao hostDao;
    @Inject private AccountManager accountManager;
    @Inject private AgentManager agentManager;
    private static final com.google.gson.Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    @Override public List<Class<?>> getCommands(){return List.of(
        org.apache.cloudstack.api.command.user.vm.ListVirtualMachineProcessProfilesCmd.class,
        org.apache.cloudstack.api.command.user.vm.ManageVirtualMachineProcessProfileCmd.class);}
    UserVmVO authorized(long id) { return authorized(id, true); }
    UserVmVO authorized(long id, boolean live) {
        UserVmVO vm=vmDao.findById(id);
        if(vm==null || vm.getRemoved()!=null || vm.getType()!=VirtualMachine.Type.User)throw new InvalidParameterValueException("User VM not found");
        accountManager.checkAccess(CallContext.current().getCallingAccount(),AccessType.OperateEntry,true,vm);
        if(!Boolean.TRUE.equals(VmProcessCapabilityServiceImpl.MANAGEMENT_ENABLED.value()))throw new InvalidParameterValueException("VM process management is disabled");
        if(vm.getHypervisorType()!=HypervisorType.KVM || (live && (vm.getState()!=VirtualMachine.State.Running || vm.getHostId()==null)))throw new InvalidParameterValueException("Running KVM VM required");
        return vm;
    }
    List<?> guest(long vmId) {
        UserVmVO vm=authorized(vmId);long host=vm.getHostId(),generation=vm.getUpdated();
        Map<String,Object> request=new LinkedHashMap<>();request.put("schemaVersion","1.1");request.put("kind","readRequest");request.put("requestId",UUID.randomUUID().toString());request.put("authority",Map.of("vmUuid",vm.getUuid(),"hostUuid",hostDao.findById(host).getUuid(),"placementGeneration",Long.toString(generation)));request.put("budgetMs",10000);request.put("operation","profile.list");request.put("operationId",null);
        try {
            Answer answer=agentManager.send(host,new VmProcessActionCommand(vm.getUuid(),JSON.toJson(request),true));
            UserVmVO current=authorized(vmId);
            if(current.getHostId()!=host || current.getUpdated()!=generation || !(answer instanceof VmProcessActionAnswer) || !answer.getResult())throw new IllegalArgumentException();
            return VmProcessProfile.catalog(((VmProcessActionAnswer)answer).getResultJson(),VmProcessAction.parse(JSON.toJson(request)));
        }catch(Exception e){throw new InvalidParameterValueException("Registered profiles unavailable; update ABLESTACK Tools and Mold Agent, then refresh");}
    }
    static List<Map<String,Object>> mergeProfiles(List<Map<String,Object>> history,List<?> live) {
        Map<String,Map<String,Object>> values=new LinkedHashMap<>();
        for(Map<String,Object> stored:history) {
            Map<String,Object> p=new LinkedHashMap<>(stored);p.remove("identity");p.put("available",false);
            values.put(p.get("id")+"@"+VmProcessProfile.version(p.get("version")),p);
        }
        for(Object item:live) {
            Map<String,Object> p=new LinkedHashMap<>(VmProcessSnapshot.map(item));
            if(!p.containsKey("version")) {
                if(history.stream().noneMatch(h -> Objects.equals(h.get("id"),p.get("id")))) {
                    p.put("registrationState","INVALID");p.put("available",false);values.put(p.get("id")+"@invalid",p);
                }
                continue;
            }
            String key=p.get("id")+"@"+VmProcessProfile.version(p.get("version"));Map<String,Object> prior=values.get(key);
            p.put("available",true);
            p.put("registrationState",prior==null?"UNREGISTERED":Objects.equals(prior.get("definitionHash"),p.get("definitionHash"))?prior.get("registrationState"):"CHANGED");
            if(prior!=null)p.put("registryState",prior.get("registrationState"));
            values.put(key,p);
        }
        return new ArrayList<>(values.values());
    }
    @Override public VmProcessActionResponse list(long vmId) {
        UserVmVO vm=authorized(vmId,false);List<?> live=List.of();String status="VM_STOPPED";
        if(vm.getState()==VirtualMachine.State.Running) {
            try{live=guest(vmId);status="AVAILABLE";}catch(InvalidParameterValueException e){status="UNAVAILABLE";}
        }
        List<Map<String,Object>> history=new ArrayList<>();boolean limited=false;
        try(Connection tx=TransactionLegacy.getStandaloneConnectionWithException()) {
            // Active approvals remain visible; only retired history is bounded.
            try(PreparedStatement s=tx.prepareStatement("SELECT profile_id,version,definition_hash,metadata_json,state FROM vm_process_profile WHERE vm_id=? ORDER BY (state='RETIRED'),updated DESC LIMIT 257")) {
                s.setLong(1,vmId);
                try(ResultSet r=s.executeQuery()){while(r.next()) {
                    if(history.size()==256){limited=true;break;}
                    Map<String,Object> p=new LinkedHashMap<>(JSON.fromJson(r.getString(4),Map.class));p.put("id",r.getString(1));p.put("version",r.getInt(2));p.put("definitionHash",r.getString(3));p.put("registrationState",r.getString(5));history.add(p);
                }}
            }
            // A current guest version must never look unregistered because its retired history is older.
            for(Object item:live) {
                Map<String,Object> p=VmProcessSnapshot.map(item);if(!p.containsKey("version") || history.stream().anyMatch(h -> Objects.equals(h.get("id"),p.get("id")) && VmProcessProfile.version(h.get("version"))==VmProcessProfile.version(p.get("version"))))continue;
                try(PreparedStatement s=tx.prepareStatement("SELECT state,definition_hash FROM vm_process_profile WHERE vm_id=? AND profile_id=? AND version=?")) {
                    s.setLong(1,vmId);s.setString(2,(String)p.get("id"));s.setInt(3,VmProcessProfile.version(p.get("version")));
                    try(ResultSet r=s.executeQuery()){if(r.next()){Map<String,Object> stored=new LinkedHashMap<>(p);stored.put("registrationState",r.getString(1));stored.put("definitionHash",r.getString(2));history.add(stored);}}
                }
            }
        }catch(SQLException e){throw new InvalidParameterValueException("Profile registry unavailable");}
        VmProcessActionResponse result=new VmProcessActionResponse();result.setProcessState(Map.of("profiles",mergeProfiles(history,live),"guestStatus",status,"historyLimited",limited));return result;
    }
    Map<String,Object> selected(long vm,String id,int version) {
        VmProcessActionServiceImpl.uuid(id);if(version<1)throw new InvalidParameterValueException("Profile version required");
        for(Object entry:guest(vm)) {
            Map<String,Object> p=VmProcessSnapshot.map(entry);
            if(id.equals(p.get("id")) && p.containsKey("version") && version==VmProcessProfile.version(p.get("version")))return p;
        }
        throw new InvalidParameterValueException("PROFILE_CHANGED; profile is absent, invalid or belongs to another VM");
    }
    @Override public VmProcessActionResponse manage(long vmId,String id,int version,String operation) {
        if(!Set.of("REGISTER","APPROVE","RETIRE").contains(operation))throw new InvalidParameterValueException("Fixed profile lifecycle operation required");
        authorized(vmId,!"RETIRE".equals(operation));VmProcessActionServiceImpl.uuid(id);if(version<1)throw new InvalidParameterValueException("Profile version required");
        Map<String,Object> p="RETIRE".equals(operation)?null:selected(vmId,id,version);
        long actor=CallContext.current().getCallingUserId();
        try(Connection tx=TransactionLegacy.getStandaloneConnectionWithException()) {
            tx.setAutoCommit(false);
            try {
                // Use the same VM -> profile lock order as execution and serialize capacity checks.
                try(PreparedStatement fence=tx.prepareStatement("SELECT id FROM vm_instance WHERE id=? FOR UPDATE")) {
                    fence.setLong(1,vmId);fence.setQueryTimeout(5);try(ResultSet r=fence.executeQuery()){if(!r.next())throw new SQLException("VM is absent");}
                }
                if("REGISTER".equals(operation)) {
                    try(PreparedStatement capacity=tx.prepareStatement("SELECT COUNT(*) FROM vm_process_profile WHERE vm_id=? AND state IN ('REGISTERED','APPROVED')")) {
                        capacity.setLong(1,vmId);try(ResultSet r=capacity.executeQuery()){r.next();if(r.getInt(1)>=128)throw new InvalidParameterValueException("Retire unused profile versions before registering more");}
                    }
                    // Persist sanitized metadata only: argv values and secret contents are absent.
                    try(PreparedStatement s=tx.prepareStatement("INSERT INTO vm_process_profile(vm_id,profile_id,version,definition_hash,metadata_json,state,registered_by) VALUES(?,?,?,?,?,'REGISTERED',?)")) {
                        s.setLong(1,vmId);s.setString(2,id);s.setInt(3,version);s.setString(4,(String)p.get("definitionHash"));
                        Map<String,Object> metadata=new LinkedHashMap<>(p);metadata.remove("identity");s.setString(5,JSON.toJson(metadata));s.setLong(6,actor);s.executeUpdate();
                    }
                } else {
                    String column="APPROVE".equals(operation)?"approved_by":"retired_by";
                    String next="APPROVE".equals(operation)?"APPROVED":"RETIRED";
                    try(PreparedStatement s=tx.prepareStatement("UPDATE vm_process_profile SET state=?,"+column+"=?,updated=CURRENT_TIMESTAMP(3) WHERE vm_id=? AND profile_id=? AND version=? AND state"+("APPROVE".equals(operation)?"='REGISTERED' AND definition_hash=?":" IN ('REGISTERED','APPROVED')"))) {
                        s.setString(1,next);s.setLong(2,actor);s.setLong(3,vmId);s.setString(4,id);s.setInt(5,version);if(p!=null)s.setString(6,(String)p.get("definitionHash"));
                        if(s.executeUpdate()!=1)throw new InvalidParameterValueException("PROFILE_CHANGED or invalid lifecycle transition; retired versions cannot be reapproved");
                    }
                }
                if("APPROVE".equals(operation)) {
                    try(PreparedStatement retire=tx.prepareStatement("UPDATE vm_process_profile SET state='RETIRED',retired_by=?,updated=CURRENT_TIMESTAMP(3) WHERE vm_id=? AND profile_id=? AND version<>? AND state='APPROVED'")) {
                        retire.setLong(1,actor);retire.setLong(2,vmId);retire.setString(3,id);retire.setInt(4,version);retire.executeUpdate();
                    }
                }
                tx.commit();
            }catch(Exception e){tx.rollback();throw e;}
        }catch(SQLException e){throw new InvalidParameterValueException("Profile version already registered or registry unavailable; create a new version for changed definitions");}
        com.cloud.event.ActionEventUtils.onCompletedActionEvent(actor,vmDao.findById(vmId).getAccountId(),"INFO","VM.PROCESS.PROFILE."+operation,"Process profile "+id+" version="+version+" "+operation,vmId,org.apache.cloudstack.api.ApiCommandResourceType.VirtualMachine.toString(),CallContext.current().getStartEventId());
        VmProcessActionResponse r=new VmProcessActionResponse();r.setProcessState(Map.of("id",id,"version",version,"registrationState","REGISTER".equals(operation)?"REGISTERED":"APPROVE".equals(operation)?"APPROVED":"RETIRED"));return r;
    }
    @Override public Map<String,Object> approved(long vmId,String id,int version) {
        authorized(vmId);VmProcessActionServiceImpl.uuid(id);if(version<1)throw new InvalidParameterValueException("Profile version required");
        try(Connection tx=TransactionLegacy.getStandaloneConnectionWithException();PreparedStatement s=tx.prepareStatement("SELECT definition_hash FROM vm_process_profile WHERE vm_id=? AND profile_id=? AND version=? AND state='APPROVED'")) {
            s.setLong(1,vmId);s.setString(2,id);s.setInt(3,version);
            try(ResultSet r=s.executeQuery()){if(!r.next())throw new InvalidParameterValueException("PROFILE_NOT_APPROVED; current VM profile version must be registered and approved");return Map.of("id",id,"version",version,"definitionHash",r.getString(1));}
        }catch(SQLException e){throw new InvalidParameterValueException("Profile registry unavailable");}
    }
    @Override public void fence(Connection tx,long vm,Map<String,Object> reference) throws SQLException {
        try(PreparedStatement s=tx.prepareStatement("SELECT definition_hash,state FROM vm_process_profile WHERE vm_id=? AND profile_id=? AND version=? FOR UPDATE")) {
            s.setLong(1,vm);s.setString(2,(String)reference.get("id"));s.setInt(3,VmProcessProfile.version(reference.get("version")));s.setQueryTimeout(5);
            try(ResultSet r=s.executeQuery()){if(!r.next() || !"APPROVED".equals(r.getString(2)) || !Objects.equals(reference.get("definitionHash"),r.getString(1)))throw new SQLException("Profile is not approved");}
        }
    }
}
