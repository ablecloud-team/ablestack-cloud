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
package com.cloud.agent.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Protocol 1.1 is opt-in. An approved profile never contains arbitrary executable input. */
public final class VmProcessProfile {
    private VmProcessProfile() { }
    public static void uuid(Object value) {
        if (!(value instanceof String) || !UUID.fromString((String)value).toString().equals(value)) throw new IllegalArgumentException("UUID");
    }
    public static int version(Object value) {
        int number=new BigDecimal(value.toString()).intValueExact();
        if(number<1)throw new IllegalArgumentException("version");return number;
    }
    public static Map<String,Object> reference(Object value) {
        Map<String,Object> p=VmProcessSnapshot.map(value);
        if(!p.keySet().equals(Set.of("id","version","definitionHash")))throw new IllegalArgumentException("profile fields");
        uuid(p.get("id"));version(p.get("version"));
        if(!(p.get("definitionHash") instanceof String) || !((String)p.get("definitionHash")).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("hash");
        return p;
    }
    public static void identity(Object value,String vm) {
        Map<String,Object> i=VmProcessSnapshot.map(value);
        if(!i.keySet().equals(Set.of("vmUuid","bootId","pid","startTicks")) || !vm.equals(i.get("vmUuid")))throw new IllegalArgumentException("identity");
        long pid=new BigDecimal(i.get("pid").toString()).longValueExact();
        if(pid<1 || pid>4294967295L || !(i.get("startTicks") instanceof String) || !((String)i.get("startTicks")).matches("[0-9]{1,20}") || !(i.get("bootId") instanceof String) || !((String)i.get("bootId")).matches("(?:linux:[0-9a-f-]{36}|windows:[0-9]{1,20})"))throw new IllegalArgumentException("identity");
    }
    public static List<?> catalog(String json,Map<String,Object> request) throws IOException {
        try {
            Map<String,Object> r=VmProcessAction.parse(json);
            if(!r.keySet().equals(Set.of("schemaVersion","kind","requestId","authority","profiles")) || !"1.1".equals(r.get("schemaVersion")) || !"profiles".equals(r.get("kind")) || !Objects.equals(request.get("requestId"),r.get("requestId")) || !Objects.equals(request.get("authority"),r.get("authority")))throw new IllegalArgumentException("catalog authority");
            List<?> profiles=(List<?>)r.get("profiles");if(profiles.size()>32)throw new IllegalArgumentException("capacity");
            Set<String> ids=new java.util.HashSet<>();String vm=(String)VmProcessSnapshot.map(request.get("authority")).get("vmUuid");
            for(Object object:profiles) {
                Map<String,Object> p=VmProcessSnapshot.map(object);uuid(p.get("id"));if(!ids.add((String)p.get("id")))throw new IllegalArgumentException("duplicate");
                if(p.keySet().equals(Set.of("id","available")) && Boolean.FALSE.equals(p.get("available")))continue;
                if(!p.keySet().equals(Set.of("id","version","definitionHash","displayName","executable","argumentCount","cwd","account","environmentRef","supervisor","verification","identity")))throw new IllegalArgumentException("metadata");
                reference(Map.of("id",p.get("id"),"version",p.get("version"),"definitionHash",p.get("definitionHash")));
                identity(p.get("identity"),vm);
                for(String key:List.of("displayName","executable","cwd","account")) {
                    Object text=p.get(key);if(!(text instanceof String) || ((String)text).isEmpty() || ((String)text).length()>1024 || ((String)text).chars().anyMatch(x->x<32))throw new IllegalArgumentException("metadata text");
                }
                long count=new BigDecimal(p.get("argumentCount").toString()).longValueExact();if(count<0 || count>32)throw new IllegalArgumentException("args");
                if(!Set.of("systemd","taskScheduler").contains(p.get("supervisor")) || !"identity-and-running".equals(p.get("verification")))throw new IllegalArgumentException("supervisor");
                if(p.get("environmentRef")!=null && (!(p.get("environmentRef") instanceof String) || ((String)p.get("environmentRef")).length()>1024 || ((String)p.get("environmentRef")).chars().anyMatch(x->x<32)))throw new IllegalArgumentException("environment reference");
            }
            return profiles;
        } catch(RuntimeException e){throw new IOException("Profile catalog could not be verified",e);}
    }
    public static Map<String,Object> decode(Map<String,Object> r,Map<String,Object> request) throws IOException {
        try {
            if("failure".equals(r.get("kind")) && r.keySet().equals(Set.of("schemaVersion","kind","requestId","authority","error")) && Objects.equals(r.get("requestId"),request.get("requestId")) && Objects.equals(r.get("authority"),request.get("authority"))) {
                String code=(String)VmProcessSnapshot.map(r.get("error")).get("code");
                if(Set.of("BUSY","CHECK_FAILED","TOOLS_REQUIRED","PERMISSION_DENIED","STALE_AUTHORITY","HOST_TOOL_MISSING","REQUEST_CONFLICT").contains(code))return VmProcessAction.rejected(request,code);
            }
            if(!r.keySet().equals(Set.of("schemaVersion","kind","requestId","authority","operationId","action","identity","service","profile","progress","state","effect","submittedAt","completedAt","guestExecPid","guestExitCode","postcondition","error")))throw new IllegalArgumentException("fields");
            for(String key:List.of("schemaVersion","requestId","authority","operationId","action","identity","service","profile"))if(!Objects.equals(r.get(key),request.get(key)))throw new IllegalArgumentException("request identity");
            if(!"1.1".equals(r.get("schemaVersion")) || !"actionResult".equals(r.get("kind")) || !"process.restart".equals(r.get("action")) || r.get("service")!=null)throw new IllegalArgumentException("fixed action");
            reference(r.get("profile"));Instant.parse((String)r.get("submittedAt"));
            Map<String,Object> p=VmProcessSnapshot.map(r.get("progress"));
            if(!p.keySet().equals(Set.of("oldProcess","newProcess","newIdentity")) || !Set.of("NOT_CHECKED","RUNNING","EXITED").contains(p.get("oldProcess")) || !Set.of("NOT_ATTEMPTED","UNKNOWN","RUNNING","NOT_RUNNING").contains(p.get("newProcess")))throw new IllegalArgumentException("progress");
            String vm=(String)VmProcessSnapshot.map(request.get("authority")).get("vmUuid");
            if(p.get("newIdentity")!=null)identity(p.get("newIdentity"),vm);
            String state=(String)r.get("state");
            if("SUCCEEDED".equals(state)) {
                if(r.get("guestExitCode")!=null && new BigDecimal(r.get("guestExitCode").toString()).signum()!=0 || !"VERIFIED".equals(r.get("effect")) || !"PROFILE_RESTART_VERIFIED".equals(r.get("postcondition")) || !"EXITED".equals(p.get("oldProcess")) || !"RUNNING".equals(p.get("newProcess")) || p.get("newIdentity")==null || Objects.equals(p.get("newIdentity"),r.get("identity")) || !Objects.equals(VmProcessSnapshot.map(p.get("newIdentity")).get("bootId"),VmProcessSnapshot.map(r.get("identity")).get("bootId")) || r.get("error")!=null)throw new IllegalArgumentException("unverified restart");
                Instant.parse((String)r.get("completedAt"));
            } else if("PARTIAL".equals(state)) {
                if(!"PARTIAL".equals(r.get("effect")) || !"OLD_EXITED_NEW_NOT_STARTED".equals(r.get("postcondition")) || !"EXITED".equals(p.get("oldProcess")) || !"NOT_RUNNING".equals(p.get("newProcess")) || p.get("newIdentity")!=null)throw new IllegalArgumentException("partial evidence");
                Instant.parse((String)r.get("completedAt"));
            } else if("FAILED".equals(state)) {
                if(!Set.of("NOT_CHECKED","RUNNING").contains(p.get("oldProcess")) || !"NOT_ATTEMPTED".equals(p.get("newProcess")) || !"NOT_STARTED".equals(r.get("effect")) || !"NOT_CHECKED".equals(r.get("postcondition")) || p.get("newIdentity")!=null)throw new IllegalArgumentException("failed evidence");
                Instant.parse((String)r.get("completedAt"));
            } else if("UNKNOWN".equals(state)) {
                if(!"MAY_HAVE_RUN".equals(r.get("effect")) || !"NOT_CHECKED".equals(r.get("postcondition")) || r.get("completedAt")!=null)throw new IllegalArgumentException("unknown evidence");
            } else throw new IllegalArgumentException("state");
            if(!"SUCCEEDED".equals(state)) {
                Map<String,Object> e=VmProcessSnapshot.map(r.get("error"));
                if(!e.keySet().equals(Set.of("code","message","retryMode")) || !Set.of("RESULT_UNKNOWN","START_FAILED","PROFILE_CHANGED","STALE_IDENTITY","PROTECTED_TARGET","UNSUPPORTED_ACTION","BUSY","CHECK_FAILED","PERMISSION_DENIED","STALE_AUTHORITY","TOOLS_REQUIRED","HOST_TOOL_MISSING").contains(e.get("code")) || !("UNKNOWN".equals(state)?"READ_ONLY":"NONE").equals(e.get("retryMode")))throw new IllegalArgumentException("error");
                r.put("error",Map.of("code",e.get("code"),"message","Registered profile restart could not be verified","retryMode",e.get("retryMode")));
            }
            for(String key:List.of("guestExecPid","guestExitCode"))if(r.get(key)!=null){long n=new BigDecimal(r.get(key).toString()).longValueExact();if(n<0 || n>4294967295L)throw new IllegalArgumentException("exit evidence");}
            return r;
        } catch(RuntimeException e){throw new IOException("Profile action evidence mismatch",e);}
    }
}
