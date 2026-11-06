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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
public class VmProcessProfileServiceImplTest {
    private Map<String,Object> registered(int version,String state) {return new LinkedHashMap<>(Map.of("id","profile","version",version,"definitionHash","hash"+version,"registrationState",state));}
    @Test public void missingGuestDefinitionKeepsApprovalVisibleWithoutIdentity() {
        Map<String,Object> old=registered(1,"APPROVED");old.put("identity",Map.of("pid",999));
        Map<String,Object> value=VmProcessProfileServiceImpl.mergeProfiles(List.of(old),List.of()).get(0);
        assertEquals("APPROVED",value.get("registrationState"));assertEquals(false,value.get("available"));assertFalse(value.containsKey("identity"));
    }
    @Test public void versionHistoryCannotBorrowCurrentIdentity() {
        Map<String,Object> live=registered(2,"ignored");live.remove("registrationState");live.put("identity",Map.of("pid",100));
        List<Map<String,Object>> values=VmProcessProfileServiceImpl.mergeProfiles(List.of(registered(1,"RETIRED"),registered(2,"APPROVED")),List.of(live));
        assertEquals(2,values.size());assertFalse(values.get(0).containsKey("identity"));assertEquals(false,values.get(0).get("available"));assertEquals("APPROVED",values.get(1).get("registrationState"));assertEquals(live.get("identity"),values.get(1).get("identity"));
    }
    @Test public void changedDefinitionCannotInheritApprovalButCanBeRetired() {
        Map<String,Object> live=registered(1,"ignored");live.remove("registrationState");live.put("definitionHash","tampered");
        Map<String,Object> value=VmProcessProfileServiceImpl.mergeProfiles(List.of(registered(1,"APPROVED")),List.of(live)).get(0);
        assertEquals("CHANGED",value.get("registrationState"));assertEquals("APPROVED",value.get("registryState"));
    }
    @Test public void invalidGuestFileDoesNotHideRegisteredVersion() {
        List<Map<String,Object>> values=VmProcessProfileServiceImpl.mergeProfiles(List.of(registered(1,"REGISTERED")),List.of(Map.of("id","profile","available",false)));
        assertEquals(1,values.size());assertEquals("REGISTERED",values.get(0).get("registrationState"));assertEquals(false,values.get(0).get("available"));
    }
}
