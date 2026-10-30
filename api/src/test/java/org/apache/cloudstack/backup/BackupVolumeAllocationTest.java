// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

public class BackupVolumeAllocationTest {
    private BackupVolumeAllocation policy(String backend, String format, String policy) {
        BackupVolumeAllocation result = new BackupVolumeAllocation();
        result.volumeUuid = "volume"; result.bytes = 4096; result.backend = backend; result.format = format;
        result.intent = VolumeAllocationPolicy.request(policy, format, null);
        return result;
    }
    @Test public void allFiveExactPoliciesSurviveSerialization() {
        for (String policy : List.of("metadata", "falloc", "full")) {
            var parsed = new Gson().fromJson(new Gson().toJson(policy("GFS2", "qcow2", policy)), BackupVolumeAllocation.class);
            parsed.validate(); assertEquals(policy, parsed.intent.get(VolumeAllocationPolicy.POLICY));
        }
        for (String policy : List.of("off", "full")) policy("RBD", "raw", policy).validate();
    }
    @Test public void fileRawKeepsItsExactAllocationPolicy() {
        for (String policy : List.of("off", "falloc", "full")) {
            policy("GFS2", "raw", policy).validate();
        }
    }
    @Test public void rbdFallocAndQcow2AreRejected() {
        assertThrows(CloudRuntimeException.class, () -> policy("RBD", "raw", "falloc").validate());
        assertThrows(CloudRuntimeException.class, () -> policy("RBD", "qcow2", "full").validate());
    }
    @Test public void fatCompatibilityColumnDoesNotEraseTheExactPolicy() {
        assertEquals("fat", VolumeAllocationPolicy.legacyColumn("falloc", "qcow2", null));
        assertEquals("fat", VolumeAllocationPolicy.legacyColumn("full", "qcow2", null));
        assertNotEquals(policy("GFS2", "qcow2", "falloc").intent, policy("GFS2", "qcow2", "full").intent);
    }
    @Test public void incompleteOrInconsistentContractsAreRejected() {
        var policy = policy("GFS2", "qcow2", "metadata"); policy.format = null;
        assertThrows(CloudRuntimeException.class, policy::validate);
        policy.format = "raw";
        assertThrows(CloudRuntimeException.class, policy::validate);
    }
    @Test public void oldBackupVolumeMetadataKeepsLegacyBehavior() {
        var info = new Gson().fromJson("{\"uuid\":\"volume\",\"type\":\"ROOT\",\"size\":4096}", Backup.VolumeInfo.class);
        assertNull(info.getAllocation());
        assertFalse(new Gson().toJson(info).contains("allocation"));
    }
}
