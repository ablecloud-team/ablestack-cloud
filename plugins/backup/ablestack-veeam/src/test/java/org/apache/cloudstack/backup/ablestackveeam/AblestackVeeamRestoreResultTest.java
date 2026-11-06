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
package org.apache.cloudstack.backup.ablestackveeam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.json.JSONObject;
import org.junit.Test;

public class AblestackVeeamRestoreResultTest {
    @Test
    public void onlyConfirmedSuccessCompletesTheArtifactRestore() {
        ThirdPartyBackupRestore.Result result = read("Success", true, true, null);
        assertTrue(result.completed);
        assertTrue(result.externalTerminal);
        assertNull(result.failure);
    }

    @Test
    public void warningCannotBecomeSuccessfulEvenWhenReceiptClaimsCompletion() {
        ThirdPartyBackupRestore.Result result = read("Warning", true, true, "Unable to set permissions on root.qcow2");
        assertFalse(result.completed);
        assertTrue(result.externalTerminal);
        assertEquals("Unable to set permissions on root.qcow2", result.failure);
    }

    @Test
    public void explicitFailureOverridesTheSuccessfulTaskResult() {
        ThirdPartyBackupRestore.Result result = read("Success", true, true, "FLR cleanup failed");
        assertFalse(result.completed);
        assertTrue(result.externalTerminal);
        assertEquals("FLR cleanup failed", result.failure);
    }

    @Test
    public void failedAndCanceledResultsAlwaysHaveAFailureReason() {
        for (String state : new String[]{"Failed", "Canceled", "Cancelled"}) {
            ThirdPartyBackupRestore.Result result = read(state, true, true, null);
            assertFalse(result.completed);
            assertTrue(result.externalTerminal);
            assertTrue(result.failure.contains(state.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    public void activeOrUnknownResultsCannotAuthorizeVolumeApplicationOrCleanup() {
        for (String state : new String[]{"None", "Unknown", "Running"}) {
            ThirdPartyBackupRestore.Result result = read(state, true, true, "Unconfirmed result");
            assertFalse(result.completed);
            assertFalse(result.externalTerminal);
            assertNull(result.failure);
        }
        assertFalse(read("Success", true, false, null).completed);
    }

    @Test
    public void oldReceiptsStillRequireConfirmedTermination() {
        JSONObject receipt = new JSONObject().put("jobId", "restore-test").put("completed", true).put("terminal", true);
        assertTrue(AblestackVeeamSshClient.parseVolumeRestoreResult(receipt).completed);
        receipt.put("terminal", false);
        assertFalse(AblestackVeeamSshClient.parseVolumeRestoreResult(receipt).completed);
    }

    @Test
    public void callbackSessionIdsRemainAvailableForLoopPrevention() {
        JSONObject receipt = new JSONObject().put("jobId", "restore-test").put("result", "Success")
                .put("completed", true).put("terminal", true)
                .put("callbackJobIds", new org.json.JSONArray().put("12345678-1234-1234-1234-123456789012"));
        assertEquals("12345678-1234-1234-1234-123456789012",
                AblestackVeeamSshClient.parseVolumeRestoreResult(receipt).callbackJobIds.get(0));
    }

    private ThirdPartyBackupRestore.Result read(String state, boolean completed, boolean terminal, String failure) {
        JSONObject receipt = new JSONObject().put("jobId", "restore-test").put("result", state).put("completed", completed)
                .put("terminal", terminal).put("failure", failure == null ? JSONObject.NULL : failure);
        return AblestackVeeamSshClient.parseVolumeRestoreResult(receipt);
    }
}
