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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import org.junit.Test;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
public class VmProcessProfileTest {
 private static final String VM="11111111-1111-4111-8111-111111111111",ID="22222222-2222-4222-8222-222222222222";
 private String json(Object value){return new GsonBuilder().serializeNulls().create().toJson(value);}
 private Map<String,Object> request(){Map<String,Object> r=new LinkedHashMap<>();r.put("schemaVersion","1.1");r.put("requestId",ID);r.put("operationId",ID);r.put("authority",Map.of("vmUuid",VM));r.put("identity",Map.of("vmUuid",VM,"bootId","linux:"+VM,"pid",100,"startTicks","18446744073709551615"));r.put("action","process.restart");r.put("service",null);r.put("profile",Map.of("id",ID,"version",1,"definitionHash","a".repeat(64)));return r;}
 private Map<String,Object> parsedRequest()throws Exception{return VmProcessAction.parse(json(request()));}
 private Map<String,Object> success(Map<String,Object> request){Map<String,Object> r=VmProcessAction.unknown(request);r.put("state","SUCCEEDED");r.put("effect","VERIFIED");r.put("postcondition","PROFILE_RESTART_VERIFIED");r.put("completedAt",java.time.Instant.now().toString());r.put("guestExitCode",0);r.put("error",null);r.put("progress",Map.of("oldProcess","EXITED","newProcess","RUNNING","newIdentity",Map.of("vmUuid",VM,"bootId","linux:"+VM,"pid",101,"startTicks","18446744073709551614")));return r;}
 @Test public void oldProtocolCannotClaimProfileSuccess()throws Exception{Map<String,Object> q=parsedRequest(),r=success(q);r.put("schemaVersion","1.0");assertThrows(IOException.class,()->VmProcessAction.decode(json(r),q));}
 @Test public void exactNewIdentityAndPostconditionRequired()throws Exception{Map<String,Object> q=parsedRequest(),r=success(q);assertEquals("SUCCEEDED",VmProcessAction.decode(json(r),q).get("state"));r.put("progress",Map.of("oldProcess","EXITED","newProcess","RUNNING","newIdentity",q.get("identity")));assertThrows(IOException.class,()->VmProcessAction.decode(json(r),q));}
 @Test public void changingProfileVersionCannotCertifyOldApproval()throws Exception{Map<String,Object> q=parsedRequest(),r=success(q);r.put("profile",Map.of("id",ID,"version",2,"definitionHash","a".repeat(64)));assertThrows(IOException.class,()->VmProcessAction.decode(json(r),q));}
 @Test public void stoppedAndFailedStartIsExplicitPartial()throws Exception{Map<String,Object> q=parsedRequest(),r=VmProcessAction.unknown(q);r.put("state","PARTIAL");r.put("effect","PARTIAL");r.put("postcondition","OLD_EXITED_NEW_NOT_STARTED");r.put("completedAt",java.time.Instant.now().toString());r.put("error",Map.of("code","START_FAILED","message","internal secret must not escape","retryMode","NONE"));Map<String,Object> progress=new LinkedHashMap<>();progress.put("oldProcess","EXITED");progress.put("newProcess","NOT_RUNNING");progress.put("newIdentity",null);r.put("progress",progress);Map<String,Object> decoded=VmProcessAction.decode(json(r),q);assertEquals("PARTIAL",decoded.get("state"));assertFalse(json(decoded).contains("internal secret"));progress.put("oldProcess","RUNNING");assertThrows(IOException.class,()->VmProcessAction.decode(json(r),q));}
 @Test public void unknownCannotReleaseAsCleanFailure()throws Exception{Map<String,Object> q=parsedRequest(),r=VmProcessAction.unknown(q);r.put("state","FAILED");r.put("completedAt",java.time.Instant.now().toString());assertThrows(IOException.class,()->VmProcessAction.decode(json(r),q));}
 @Test public void extraCommandOrSecretFieldsRejected()throws Exception{Map<String,Object> q=parsedRequest(),r=success(q);r.put("command","arbitrary");assertThrows(IOException.class,()->VmProcessAction.decode(json(r),q));}
 @Test public void catalogRefusesWrongVmAndSecretContent()throws Exception{Map<String,Object> q=parsedRequest();Map<String,Object> p=new LinkedHashMap<>();p.putAll(Map.of("id",ID,"version",1,"definitionHash","a".repeat(64),"displayName","fixture","executable","/usr/bin/app","argumentCount",1,"cwd","/var/lib","account","root","supervisor","systemd","verification","identity-and-running"));p.put("environmentRef",null);p.put("identity",q.get("identity"));Map<String,Object> r=Map.of("schemaVersion","1.1","kind","profiles","requestId",q.get("requestId"),"authority",q.get("authority"),"profiles",List.of(p));assertEquals(1,VmProcessProfile.catalog(json(r),q).size());p.put("environmentValues",Map.of("SECRET","value"));assertThrows(IOException.class,()->VmProcessProfile.catalog(json(r),q));p.remove("environmentValues");p.put("identity",Map.of("vmUuid",ID,"bootId","linux:"+VM,"pid",101,"startTicks","1"));assertThrows(IOException.class,()->VmProcessProfile.catalog(json(r),q));}
}
