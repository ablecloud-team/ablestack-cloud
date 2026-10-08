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

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.google.gson.JsonObject;

public class StorageRetainedRootRpcTest {
    private static class Manager extends StorageServiceManagerImpl {
        JsonObject nativeState=new JsonObject(),status=new JsonObject(),response=new JsonObject(),request;
        @Override protected void requireProtectedIdentityTransport(StorageServiceInstanceVO instance,String operation){commands.add("PROTECTED_STDIN_VERIFIED");}List<String> commands=new ArrayList<>();
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){return nativeState.deepCopy();}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject payload,int timeout){commands.add(command);if(command.endsWith("render-status"))return status.deepCopy();request=payload.deepCopy();return response.deepCopy();}
    }
    private JsonObject scope(){JsonObject s=new JsonObject();s.addProperty("instanceUuid","11111111-1111-1111-1111-111111111111");s.addProperty("operationUuid","22222222-2222-2222-2222-222222222222");s.addProperty("templateUpgradeUuid","33333333-3333-3333-3333-333333333333");s.addProperty("revision",11);return s;}
    private Manager manager(){Manager m=new Manager();
        JsonObject gen=new JsonObject();
        gen.add("instanceUuid",scope().get("instanceUuid"));
        gen.addProperty("revision",4);
        gen.addProperty("configurationSha256","a".repeat(64));
        m.nativeState.addProperty("success",true);
        m.nativeState.addProperty("generationStatus","IN_SYNC");
        m.nativeState.add("generation",gen);
        m.nativeState.add("configurationDesiredState",new JsonObject());
        m.status.addProperty("success",true);
        m.status.addProperty("retainedRootRestoreSupported",true);
        JsonObject current=new JsonObject();
        current.addProperty("manifestSha256","b".repeat(64));
        m.status.add("current",current);
        m.response.addProperty("success",true);
        m.response.addProperty("retainedBaselineCaptured",true);
        m.response.add("scope",scope());
        m.response.add("retainedGeneration",gen.deepCopy());
        m.response.addProperty("retainedRenderedSha256","b".repeat(64));
        m.response.addProperty("canonicalDesiredStateChanged",false);
        m.response.addProperty("dataPermissionsChanged",false);
        JsonObject ref=new JsonObject();
        ref.addProperty("authorizationUuid","44444444-4444-4444-4444-444444444444");
        ref.addProperty("sha256","c".repeat(64));
        m.response.add("baselineRef",ref);
        return m;
        }
    @Test public void realProducerCapturesOldNativeGenerationBeforeAnyLatestIdentityInput(){
        Manager m=manager();JsonObject captured=m.captureRetainedRootBaseline(Mockito.mock(StorageServiceInstanceVO.class),scope());Assert.assertEquals(m.nativeState.get("generation"),captured.get("generation"));Assert.assertEquals(m.response.get("baselineRef"),captured.get("baselineRef"));Assert.assertEquals(Set.of("instanceUuid","operationUuid","templateUpgradeUuid","revision","expectedRetainedGeneration","expectedRetainedRenderedSha256"),m.request.keySet());Assert.assertFalse(m.request.has("capsule"));Assert.assertEquals(List.of("operation generation render-status","operation generation render-root-capture-retained"),m.commands);
    }
    @Test public void unsupportedOrCoercedCapabilityAndPendingWriterRejectBeforeCapture(){
        for(String failure:List.of("missing","string","pending","failed")){Manager m=manager();if(failure.equals("missing"))m.status.remove("retainedRootRestoreSupported");if(failure.equals("string"))m.status.addProperty("retainedRootRestoreSupported","true");if(failure.equals("pending"))m.nativeState.addProperty("pendingOperationUuid","foreign");if(failure.equals("failed"))m.nativeState.addProperty("success",false);Assert.assertThrows(RuntimeException.class,()->m.captureRetainedRootBaseline(Mockito.mock(StorageServiceInstanceVO.class),scope()));Assert.assertFalse(m.commands.contains("operation generation render-root-capture-retained"));}
    }
    @Test public void nativeForeignPointerOrMutatingReceiptCannotBecomeTheSavedBaseline(){
        Manager m=manager();m.response.addProperty("retainedRenderedSha256","d".repeat(64));Manager foreign=m;Assert.assertThrows(RuntimeException.class,()->foreign.captureRetainedRootBaseline(Mockito.mock(StorageServiceInstanceVO.class),scope()));m=manager();m.response.addProperty("dataPermissionsChanged",true);Manager changed=m;Assert.assertThrows(RuntimeException.class,()->changed.captureRetainedRootBaseline(Mockito.mock(StorageServiceInstanceVO.class),scope()));
    }
    @Test public void realAuthorizeProducerUsesProtectedCipherAndNativeRefWithoutPlainLatestState() throws Exception {
        java.nio.file.Path path=java.nio.file.Files.createTempDirectory("retained-root-rpc-");java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));String previous=System.getProperty("cloudstack.storage.identity.path");System.setProperty("cloudstack.storage.identity.path",path.toString());
        try(org.mockito.MockedStatic<com.cloud.utils.crypt.DBEncryptionUtil> crypto=Mockito.mockStatic(com.cloud.utils.crypt.DBEncryptionUtil.class)) {
            crypto.when(()->com.cloud.utils.crypt.DBEncryptionUtil.decrypt("encrypted-test-key")).thenReturn("RAM-ONLY-PRIVATE-KEY");Manager m=manager();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(7L);
            JsonObject capsule=new JsonObject();capsule.addProperty("ciphertext","protected-test-envelope");byte[] bytes=capsule.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),key="encrypted-test-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);String op=scope().get("operationUuid").getAsString(),keyId="55555555-5555-5555-5555-555555555555";StorageConfigArtifactStore store=new StorageConfigArtifactStore(path);store.write(op,bytes);store.write(keyId,key);
            JsonObject identity=new JsonObject();identity.addProperty("operationUuid",op);identity.addProperty("keyId",keyId);identity.addProperty("capsuleSha256",StorageConfigArchive.sha256(bytes));identity.addProperty("keySha256",StorageConfigArchive.sha256(key));identity.add("sourceRootScope",scope());identity.addProperty("sourceConfigurationSha256","d".repeat(64));JsonObject baseline=new JsonObject();baseline.add("generation",m.nativeState.get("generation").deepCopy());baseline.add("rendered",m.status.get("current").deepCopy());baseline.add("baselineRef",m.response.get("baselineRef").deepCopy());
            JsonObject approved=m.response.deepCopy();approved.addProperty("retainedRootAuthorized",true);approved.addProperty("latestConfigurationSha256","d".repeat(64));JsonObject ref=approved.getAsJsonObject("baselineRef").deepCopy();approved.add("retainedRootAuthorization",ref);approved.add("authorizationUuid",ref.get("authorizationUuid"));approved.add("sha256",ref.get("sha256"));
            StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);Assert.assertEquals("operation generation render-root-authorize-retained",command.getOperation());Assert.assertEquals(Set.of("capsule","credentialPrivateKey"),command.getMaskedFields());JsonObject frame=com.google.gson.JsonParser.parseString(command.getPayload()).getAsJsonObject();Assert.assertEquals(scope().get("revision"),frame.get("revision"));Assert.assertEquals(capsule,frame.get("capsule"));Assert.assertEquals(baseline.get("baselineRef"),frame.get("baselineRef"));Assert.assertFalse(frame.has("configurationDesiredState"));Assert.assertFalse(frame.has("latest7"));return new StorageServiceGuestCommandResult(true,"authorized",approved.toString());});org.springframework.test.util.ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            Assert.assertEquals(ref,m.authorizeRetainedRoot(instance,scope(),baseline,identity,new com.google.gson.JsonArray()));Assert.assertEquals(List.of("PROTECTED_STDIN_VERIFIED"),m.commands);Mockito.verify(guest).dispatch(Mockito.any());
        }finally {if(previous==null)System.clearProperty("cloudstack.storage.identity.path");else System.setProperty("cloudstack.storage.identity.path",previous);}
    }
}
