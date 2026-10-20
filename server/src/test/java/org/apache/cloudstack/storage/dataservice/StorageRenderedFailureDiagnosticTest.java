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

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** JSON/Mockito only: never invokes Native, a filesystem adapter, QGA, shell or a subprocess. */
public class StorageRenderedFailureDiagnosticTest {
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
    private StorageRenderedGenerationCoordinator.Runtime runtime(boolean success,String body,String hostDetail) {
        return runtime(success,body,hostDetail,"stage");
    }
    private StorageRenderedGenerationCoordinator.Runtime runtime(boolean success,String body,String hostDetail,String action) {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(42L);
        StorageServiceGuestCommandDispatcher dispatcher=command->{
            Assert.assertEquals("operation generation render-"+action,command.getOperation());
            Assert.assertTrue(command.getMaskedFields().contains("checkpointPrivateKey"));
            return new StorageServiceGuestCommandResult(success,hostDetail,body);
        };
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",dispatcher);
        StorageServiceOperationVO operation=new StorageServiceOperationVO();operation.setPreviousSnapshotJson("{}");
        return manager.renderedRuntime(instance,new StorageServiceManagerImpl.RenderedBatch(operation,new JsonObject(),new JsonObject(),null));
    }
    private String rejected(String body,String detail) {
        CloudRuntimeException error=Assert.assertThrows(CloudRuntimeException.class,()->runtime(false,body,detail).render("stage",new JsonObject()));
        Assert.assertFalse(error.getMessage(),error.getMessage().contains(PRIVATE));Assert.assertNull(error.getCause());return error.getMessage();
    }
    @Test public void nativeClosedValidatorPairsReachTheManagerWithoutPrivateBodyOrHostDetails() {
        for(String[] pair:PAIRS)Assert.assertTrue(rejected(nativeFailure(pair[0],pair[1]),PRIVATE).contains("protocol=NFS; stage="+pair[1]+"; runtimeValidationCode="+pair[0]));
    }
    @Test public void malformedDuplicateUnknownAndMismatchedRepliesStayGeneric() {
        for(String body:rejectedFailures())Assert.assertEquals("Rendered generation stage failed; inspect its scoped recovery receipt",rejected(body,PRIVATE));
    }
    @Test public void hostDetailsCannotInventAnAbsentNativeDiagnostic() {
        Assert.assertEquals("Rendered generation stage failed; inspect its scoped recovery receipt",rejected(null,"protocol=NFS; stage=RUNTIME_SELECTION; runtimeValidationCode=NFS_VALIDATOR_RUNTIME_UNAVAILABLE"));
    }
    @Test public void privateExtraFieldsOutsideTheClosedDiagnosticAreNeverPublished() {
        com.google.gson.JsonObject value=com.google.gson.JsonParser.parseString(nativeFailure(PAIRS[0][0],PAIRS[0][1])).getAsJsonObject();
        JsonObject nested=new JsonObject();nested.addProperty("secret",PRIVATE);value.add("privateBody",nested);
        Assert.assertTrue(rejected(value.toString(),PRIVATE).endsWith("runtimeValidationCode="+PAIRS[0][0]+"]"));
    }
    @Test public void successfulRenderedReplyRetainsItsExistingPayloadPath() {
        JsonObject reply=runtime(true,"{\"success\":true,\"publicValue\":1}",PRIVATE).render("stage",new JsonObject());Assert.assertTrue(reply.get("success").getAsBoolean());Assert.assertEquals(1,reply.get("publicValue").getAsInt());
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
    @Test public void fixedRuntimeFailuresReachManagerWithoutPrivateReason() {
        for (String[] pair : RUNTIME_PAIRS) {
            String protocol = ("RUNTIME_PREPARE_FAILED".equals(pair[0]) || "NATIVE_CHILD_INPUT_UNAVAILABLE".equals(pair[0])) ? "GENERATION" : "NFS";
            String message = rejected(runtimeFailure(protocol, pair[0], pair[1]), PRIVATE);
            Assert.assertTrue(message, message.contains("runtimeSchemaVersion=1; protocol=" + protocol + "; phase=" + pair[1] + "; code=" + pair[0]));
            Assert.assertFalse(message.contains(PRIVATE));
        }
        for (String protocol : new String[]{"SMB", "ISCSI", "NVMEOF", "GENERATION"}) {
            Assert.assertTrue(rejected(runtimeFailure(protocol, "RUNTIME_APPLY_FAILED", "APPLY"), PRIVATE).contains("protocol=" + protocol));
        }
    }
    @Test public void missingNestedInputReachesRollbackApiWithOnlyClosedMetadata() {
        String body = runtimeFailure("GENERATION", "NATIVE_CHILD_INPUT_UNAVAILABLE", "TRANSPORT");
        CloudRuntimeException error = Assert.assertThrows(CloudRuntimeException.class,
            () -> runtime(false, body, PRIVATE, "rollback").render("rollback", new JsonObject()));
        Assert.assertEquals("Rendered generation rollback failed; inspect its scoped recovery receipt "
            + "[runtimeSchemaVersion=1; protocol=GENERATION; phase=TRANSPORT; code=NATIVE_CHILD_INPUT_UNAVAILABLE]", error.getMessage());
        Assert.assertFalse(error.getMessage().contains(PRIVATE));Assert.assertNull(error.getCause());
    }
    @Test public void closedStageFailuresAreAcceptedOnlyForTheGenerationStageAction() {
        for(String code:java.util.List.of("STAGE_INPUT_INVALID","STAGE_CREDENTIAL_BINDING_FAILED","STAGE_RENDER_FAILED",
                "STAGE_SOURCE_SCOPE_MISMATCH","STAGE_SOURCE_VERIFY_FAILED","STAGE_SOURCE_IDENTITY_FAILED","STAGE_CANDIDATE_VALIDATION_FAILED")) {
            String body=runtimeFailure("GENERATION",code,"STAGE");
            Assert.assertTrue(rejected(body,PRIVATE).contains("protocol=GENERATION; phase=STAGE; code="+code));
            Assert.assertEquals("Rendered generation stage failed; inspect its scoped recovery receipt",rejected(runtimeFailure("SMB",code,"STAGE"),PRIVATE));
            CloudRuntimeException other=Assert.assertThrows(CloudRuntimeException.class,()->runtime(false,body,PRIVATE,"activate").render("activate",new JsonObject()));
            Assert.assertEquals("Rendered generation activate failed; inspect its scoped recovery receipt",other.getMessage());
        }
    }

    @Test public void safeStageDiagnosticIsPersistedOnlyInsideItsOriginalWriterScope() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");Mockito.when(instance.getVmId()).thenReturn(42L);
        StorageServiceOperationVO operation=new StorageServiceOperationVO();ReflectionTestUtils.setField(operation,"id",9L);operation.setInstanceId(7);operation.setRevision(3);
        operation.setPreviousSnapshotJson("{}");operation.setResultJson("{\"_requestFingerprint\":\"original-intent\"}");
        var dao=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);Mockito.when(dao.update(Mockito.eq(9L),Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(manager,"storageOperationDao",dao);
        @SuppressWarnings("unchecked") ThreadLocal<StorageServiceOperationVO> writer=(ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"storageWriterOperation");writer.set(operation);
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",(StorageServiceGuestCommandDispatcher)command->new StorageServiceGuestCommandResult(false,PRIVATE,runtimeFailure("GENERATION","STAGE_SOURCE_VERIFY_FAILED","STAGE")));
        var runtime=manager.renderedRuntime(instance,new StorageServiceManagerImpl.RenderedBatch(operation,new JsonObject(),new JsonObject(),null));
        Assert.assertThrows(CloudRuntimeException.class,()->runtime.render("stage",new JsonObject()));
        JsonObject result=com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject();
        Assert.assertEquals("original-intent",result.get("_requestFingerprint").getAsString());
        JsonObject saved=result.getAsJsonObject("_renderedFailureDiagnostic");Assert.assertEquals("stage",saved.get("action").getAsString());
        Assert.assertEquals(operation.getUuid(),saved.getAsJsonObject("scope").get("operationUuid").getAsString());
        Assert.assertEquals(3,saved.getAsJsonObject("scope").get("revision").getAsLong());
        Assert.assertTrue(saved.get("diagnostic").getAsString().contains("STAGE_SOURCE_VERIFY_FAILED"));Assert.assertFalse(operation.getResultJson().contains(PRIVATE));
        writer.remove();Mockito.verify(dao).update(9L,operation);
    }

    @Test public void malformedRuntimeFailuresRemainGenericWithoutPrivateFallback() {
        for (String value : rejectedRuntimeFailures()) {
            Assert.assertEquals("Rendered generation stage failed; inspect its scoped recovery receipt", rejected(value, PRIVATE));
        }
    }
}
