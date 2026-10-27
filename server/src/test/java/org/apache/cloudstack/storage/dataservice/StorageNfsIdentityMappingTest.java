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
package org.apache.cloudstack.storage.dataservice;
import java.util.Arrays;
import java.util.Collections;
import java.lang.reflect.Field;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsIdentityMappingCmd;
import org.junit.Test;
import static org.junit.Assert.*;
public class StorageNfsIdentityMappingTest {
    @Test public void legacyAndExplicitDefaultsAgreeAndOtherEndpointFieldsSurvive() {
        StorageServiceProtocolVO legacy = new StorageServiceProtocolVO(9, StorageServiceInstance.Protocol.NFS, true, "10.0.0.1", 2049);
        legacy.setConfigJson("{\"listenerGroups\":[{\"port\":2050}],\"protocolMode\":\"V4_ONLY\"}");
        StorageServiceProtocolVO explicit = new StorageServiceProtocolVO(9, StorageServiceInstance.Protocol.NFS, false, "10.0.0.2", 2050);
        explicit.setConfigJson(new StorageNfsIdentityMapping("NAME_DOMAIN",0).overlay(null).toString());
        assertEquals(0,StorageNfsIdentityMapping.policy(Arrays.asList(legacy,explicit)).revision);
        JsonObject candidate = new StorageNfsIdentityMapping("NUMERIC",13).overlay(legacy.getConfigJson());
        assertEquals("V4_ONLY",candidate.get("protocolMode").getAsString());
        assertEquals(2050,candidate.getAsJsonArray("listenerGroups").get(0).getAsJsonObject().get("port").getAsInt());
        assertEquals("NUMERIC",candidate.get(StorageNfsIdentityMapping.MODE).getAsString());
        assertFalse(legacy.getConfigJson().contains("NUMERIC"));
        JsonObject newEndpoint = new StorageNfsIdentityMapping("NUMERIC",13).overlay("{\"protocolMode\":\"V4_ONLY\"}");
        assertFalse(newEndpoint.has("listenerGroups"));
    }
    @Test(expected=InvalidParameterValueException.class) public void inconsistentDisabledRowIsNotSilentlyIgnored() {
        StorageServiceProtocolVO a=new StorageServiceProtocolVO(9,StorageServiceInstance.Protocol.NFS,true,null,2049);
        StorageServiceProtocolVO b=new StorageServiceProtocolVO(9,StorageServiceInstance.Protocol.NFS,false,null,2050);
        b.setConfigJson(new StorageNfsIdentityMapping("NUMERIC",1).overlay(null).toString());
        StorageNfsIdentityMapping.policy(Arrays.asList(a,b));
    }
    @Test public void actualCommandAndUiPreflightUseTheSameTargetWithoutPosix() throws Exception {
        UpdateStorageNfsIdentityMappingCmd cmd=new UpdateStorageNfsIdentityMappingCmd();
        Field field=cmd.getClass().getDeclaredField("idMappingMode");field.setAccessible(true);field.set(cmd,"NUMERIC");
        JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals("NUMERIC",actual.get("idmappingmode").getAsString());
        assertFalse(actual.has("posixchanges"));
        JsonObject ui=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageNfsIdentityMapping\",\"protocol\":\"NFS\",\"idmappingmode\":\"NUMERIC\"}");
        assertEquals(ui.get("idmappingmode"),actual.get("idmappingmode"));
    }
    @Test(expected=InvalidParameterValueException.class) public void invalidTargetIsRejectedAtPreflight() {
        StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageNfsIdentityMapping\",\"idmappingmode\":\"BAD\"}");
    }
    @Test(expected=InvalidParameterValueException.class) public void policyKeyCannotBeSmuggledIntoOtherCommands() {
        StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageNfsExport\",\"idmappingmode\":\"NUMERIC\"}");
    }
}
