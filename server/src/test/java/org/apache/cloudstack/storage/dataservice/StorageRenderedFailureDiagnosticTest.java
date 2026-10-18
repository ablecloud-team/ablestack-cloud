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
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(42L);
        StorageServiceGuestCommandDispatcher dispatcher=command->{
            Assert.assertEquals("operation generation render-stage",command.getOperation());
            Assert.assertTrue(command.getMaskedFields().contains("checkpointPrivateKey"));
            return new StorageServiceGuestCommandResult(success,hostDetail,body);
        };
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",dispatcher);return manager.renderedRuntime(instance,null);
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
}
