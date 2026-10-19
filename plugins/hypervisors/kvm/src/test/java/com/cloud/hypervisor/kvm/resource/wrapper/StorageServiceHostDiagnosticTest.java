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

package com.cloud.hypervisor.kvm.resource.wrapper;
import java.util.Collections;
import com.cloud.agent.api.StorageServiceHostCommand;
import org.junit.Assert;
import org.junit.Test;
public class StorageServiceHostDiagnosticTest {
    private final LibvirtStorageServiceHostCommandWrapper wrapper=new LibvirtStorageServiceHostCommandWrapper();
    @Test public void structuredGuestErrorIsUsedWhenStderrIsAbsent() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","nfs export apply","{}",30);
        String result=wrapper.commandFailureDetails(command,1,"{\"success\":false,\"errorCode\":\"APPLY_FAILED\",\"message\":\"fixture probe failed\"}",null);
        Assert.assertTrue(result.contains("APPLY_FAILED"));Assert.assertTrue(result.contains("fixture probe failed"));Assert.assertFalse(result.endsWith(": null"));
    }
    @Test public void secretBearingFailuresDoNotReachAuditDiagnostics() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","smb share apply","{}",30,Collections.singleton("password"));
        Assert.assertFalse(wrapper.commandFailureDetails(command,1,"fixture-only-secret","fixture-only-secret").contains("fixture-only-secret"));
        Assert.assertFalse(wrapper.commandExceptionDetails(command,"fixture-only-secret").contains("fixture-only-secret"));
    }
    private static final String[][] PAIRS={
        {"NFS_VALIDATOR_RUNTIME_UNAVAILABLE","RUNTIME_SELECTION"},{"NFS_VALIDATOR_RUNTIME_UNTRUSTED","RUNTIME_SELECTION"},
        {"NFS_VALIDATOR_NAMESPACE_FAILED","NAMESPACE_SETUP"},{"NFS_VALIDATOR_CHROOT_FAILED","CHROOT_SETUP"},
        {"NFS_VALIDATOR_PRIVATE_SERVICES_FAILED","PRIVATE_SERVICES"},{"NFS_VALIDATOR_PARSER_REJECTED","PARSER_STARTUP"},
        {"NFS_VALIDATOR_PARSER_TIMEOUT","PARSER_STARTUP"},{"NFS_VALIDATOR_WORKER_FAILED","WORKER_TRANSPORT"},
        {"NFS_VALIDATOR_WORKER_TIMEOUT","WORKER_TRANSPORT"}};
    private static final String PRIVATE="SYNTH_PRIVATE_REASON_STDERR_PATH_COMMAND";
    private String nativeFailure(String code,String stage) {
        com.google.gson.JsonObject value=new com.google.gson.JsonObject(),diagnostic=new com.google.gson.JsonObject();
        value.addProperty("success",false);value.addProperty("errorCode","RENDERED_GENERATION_REJECTED");value.addProperty("reason",PRIVATE);
        diagnostic.addProperty("protocol","NFS");diagnostic.addProperty("stage",stage);diagnostic.addProperty("runtimeValidationCode",code);value.add("diagnostic",diagnostic);return value.toString();
    }
    private java.util.List<String> rejectedFailures() {
        String valid=nativeFailure(PAIRS[0][0],PAIRS[0][1]);java.util.List<String> bad=new java.util.ArrayList<>();
        for(String value:new String[]{null,"","null","[]","{",valid+"{}",valid.replace("\"success\":false","\"success\":false,\"success\":false"),
            valid.replace("\"protocol\":\"NFS\"","\"protocol\":\"NFS\",\"protocol\":\"NFS\""),
            nativeFailure(PAIRS[0][0],"PRIVATE_SERVICES"),nativeFailure(PRIVATE,PAIRS[0][1]),valid.replace("\"protocol\":\"NFS\"","\"protocol\":\"SMB\""),
            valid.replace("\"success\":false","\"success\":true"),valid.replace("\"success\":false","\"success\":\"false\""),
            valid.replace("RENDERED_GENERATION_REJECTED",PRIVATE),valid.replace("\"diagnostic\":{","\"diagnostic\":{\"private\":\""+PRIVATE+"\","),
            valid.replace("\"protocol\":\"NFS\"","\"protocol\":null"),valid.replace("\"protocol\":\"NFS\",",""),
            valid.replace("\"stage\":\"RUNTIME_SELECTION\"","\"stage\":false"),valid.replace("\"runtimeValidationCode\":\""+PAIRS[0][0]+"\"","\"runtimeValidationCode\":1"),
            valid.replace(PRIVATE,PRIVATE+"x".repeat(65536))})bad.add(value);
        return bad;
    }
    @Test public void sensitiveRenderedFailuresExposeOnlyAllNineClosedValidatorPairs() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","operation generation render-stage",PRIVATE,30,Collections.singleton("checkpointPrivateKey"));
        for(String[] pair:PAIRS) {String detail=wrapper.commandFailureDetails(command,1,nativeFailure(pair[0],pair[1]),PRIVATE);
            Assert.assertTrue(detail.contains("protocol=NFS; stage="+pair[1]+"; runtimeValidationCode="+pair[0]));Assert.assertFalse(detail.contains(PRIVATE));Assert.assertTrue(detail.startsWith("Sensitive Storage Service command failed"));}
    }
    @Test public void malformedOrUntrustedRenderedDiagnosticsNeverFallBackToReasonOrStderr() {
        for(String operation:new String[]{"operation generation render-import","operation generation render-stage"}) {
            StorageServiceHostCommand command=new StorageServiceHostCommand("fixture",operation,PRIVATE,30);
            for(String body:rejectedFailures()) {String detail=wrapper.commandFailureDetails(command,1,body,PRIVATE);Assert.assertFalse(detail,detail.contains(PRIVATE));Assert.assertFalse(detail,detail.contains("runtimeValidationCode="));}
        }
    }
    @Test public void closedRenderedDiagnosticCannotBeBorrowedByAnotherSensitiveOperation() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","smb share apply",PRIVATE,30,Collections.singleton("password"));
        Assert.assertEquals("Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted",wrapper.commandFailureDetails(command,1,nativeFailure(PAIRS[0][0],PAIRS[0][1]),PRIVATE));
    }
    @Test public void renderedExceptionsWithoutStructuredReplyRemainMasked() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture","operation generation render-import",PRIVATE,30);
        Assert.assertFalse(wrapper.commandExceptionDetails(command,PRIVATE).contains(PRIVATE));
    }
    private static final String[][] RUNTIME_PAIRS = {
        {"NATIVE_CHILD_TIMEOUT", "TRANSPORT"}, {"NATIVE_CHILD_OUTPUT_INVALID", "TRANSPORT"},
        {"NATIVE_CHILD_FAILED", "TRANSPORT"}, {"NATIVE_CHILD_INPUT_UNAVAILABLE", "TRANSPORT"}, {"RUNTIME_PREPARE_FAILED", "PREPARE"},
        {"RUNTIME_APPLY_FAILED", "APPLY"}, {"RUNTIME_VERIFY_FAILED", "VERIFY"},
        {"RUNTIME_ROLLBACK_FAILED", "ROLLBACK"}, {"ENDPOINT_LISTENER_ADDRESS_MISMATCH", "VERIFY"},
        {"NFS_ALIAS_UNMOUNT_FAILED", "CLEANUP"}, {"NFS_ALIAS_UNMOUNT_UNVERIFIED", "CLEANUP"}};
    private String runtimeFailure(String protocol, String code, String phase) {
        com.google.gson.JsonObject value = new com.google.gson.JsonObject(), diagnostic = new com.google.gson.JsonObject();
        value.addProperty("success", false);value.addProperty("errorCode", "RENDERED_GENERATION_REJECTED");value.addProperty("reason", PRIVATE);
        diagnostic.addProperty("schemaVersion", 1);diagnostic.addProperty("protocol", protocol);
        diagnostic.addProperty("phase", phase);diagnostic.addProperty("code", code);value.add("runtimeDiagnostic", diagnostic);return value.toString();
    }
    private java.util.List<String> rejectedRuntimeFailures() {
        String valid = runtimeFailure("NFS", "ENDPOINT_LISTENER_ADDRESS_MISMATCH", "VERIFY");
        java.util.List<String> bad = new java.util.ArrayList<>();
        java.util.Collections.addAll(bad, valid + "{}", valid.replace("\"success\":false", "\"success\":true"),
            valid.replace("\"success\":false", "\"success\":false,\"success\":false"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"protocol\":\"NFS\"", "\"protocol\":\"SMB\""),
            valid.replace("\"protocol\":\"NFS\"", "\"protocol\":\"PRIVATE\""),
            valid.replace("\"phase\":\"VERIFY\"", "\"phase\":\"ROLLBACK\""),
            valid.replace("ENDPOINT_LISTENER_ADDRESS_MISMATCH", PRIVATE),
            valid.replace("\"code\":", "\"private\":\"" + PRIVATE + "\",\"code\":"),
            valid.replace("\"schemaVersion\":1,", ""),
            valid.replace(PRIVATE, PRIVATE + "x".repeat(65536)));
        bad.add(runtimeFailure("NFS", "RUNTIME_PREPARE_FAILED", "PREPARE"));
        String input = runtimeFailure("GENERATION", "NATIVE_CHILD_INPUT_UNAVAILABLE", "TRANSPORT");
        bad.add(runtimeFailure("SMB", "NATIVE_CHILD_INPUT_UNAVAILABLE", "TRANSPORT"));
        bad.add(runtimeFailure("GENERATION", "NATIVE_CHILD_INPUT_UNAVAILABLE", "APPLY"));
        bad.add(input.replace("NATIVE_CHILD_INPUT_UNAVAILABLE", PRIVATE));
        bad.add(input.replace("\"code\":", "\"private\":\"" + PRIVATE + "\",\"code\":"));
        return bad;
    }
    @Test public void fixedRuntimeFailuresReachAgentWithoutPrivateReason() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("fixture", "operation generation render-activate", PRIVATE, 30, Collections.singleton("transientCredentials"));
        for (String[] pair : RUNTIME_PAIRS) {
            String protocol = ("RUNTIME_PREPARE_FAILED".equals(pair[0]) || "NATIVE_CHILD_INPUT_UNAVAILABLE".equals(pair[0])) ? "GENERATION" : "NFS";
            String message = wrapper.commandFailureDetails(command, 1, runtimeFailure(protocol, pair[0], pair[1]), PRIVATE);
            Assert.assertTrue(message, message.contains("runtimeSchemaVersion=1; protocol=" + protocol + "; phase=" + pair[1] + "; code=" + pair[0]));
            Assert.assertFalse(message.contains(PRIVATE));
        }
        for (String protocol : new String[]{"SMB", "ISCSI", "NVMEOF", "GENERATION"}) {
            Assert.assertTrue(wrapper.commandFailureDetails(command, 1, runtimeFailure(protocol, "RUNTIME_APPLY_FAILED", "APPLY"), PRIVATE).contains("protocol=" + protocol));
        }
    }
    @Test public void missingNestedInputReachesRollbackHostDetailWithoutPrivateText() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("fixture", "operation generation render-rollback",
            PRIVATE, 30, Collections.singleton("checkpointPrivateKey"));
        String message = wrapper.commandFailureDetails(command, 1,
            runtimeFailure("GENERATION", "NATIVE_CHILD_INPUT_UNAVAILABLE", "TRANSPORT"), PRIVATE);
        Assert.assertTrue(message, message.contains("runtimeSchemaVersion=1; protocol=GENERATION; phase=TRANSPORT; code=NATIVE_CHILD_INPUT_UNAVAILABLE"));
        Assert.assertFalse(message.contains(PRIVATE));Assert.assertTrue(message.startsWith("Sensitive Storage Service command failed"));
        Assert.assertFalse(wrapper.commandFailureDetails(command, 1, null, message).contains("runtimeSchemaVersion="));
    }
    @Test public void malformedRuntimeFailuresRemainMaskedWithoutPrivateFallback() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("fixture", "operation generation render-rollback", PRIVATE, 30);
        for (String value : rejectedRuntimeFailures()) {
            String message = wrapper.commandFailureDetails(command, 1, value, PRIVATE);
            Assert.assertFalse(message, message.contains("runtimeSchemaVersion="));Assert.assertFalse(message, message.contains(PRIVATE));
        }
    }
    @Test public void runtimeDiagnosticCannotBeBorrowedByUnrelatedSensitiveOperation() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("fixture", "smb share apply", PRIVATE, 30, Collections.singleton("password"));
        Assert.assertEquals("Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted",
            wrapper.commandFailureDetails(command, 1, runtimeFailure("NFS", "NFS_ALIAS_UNMOUNT_FAILED", "CLEANUP"), PRIVATE));
    }
}
