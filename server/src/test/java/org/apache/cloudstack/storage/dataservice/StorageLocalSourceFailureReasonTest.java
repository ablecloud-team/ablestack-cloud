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

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.StorageServiceHostAnswer;
import com.cloud.agent.api.StorageServiceHostCommand;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StorageLocalSourceFailureReasonTest {
    private static final String GENERIC = "LOCAL SOURCE owned checkpoint requires reconciliation";
    private static final String SECRET = "SYNTHETIC_PRIVATE_KEY_AND_CAPSULE";
    private static final String VALID = "{\"success\":false,\"errorCode\":\"LOCAL_SOURCE_CHECKPOINT_REJECTED\",\"reason\":\"ValueError\"}";

    private static class Manager extends StorageServiceManagerImpl {
        JsonObject invoke(StorageServiceInstanceVO instance, String action, JsonObject request) {
            return localSourceGuest(instance, action, request);
        }
    }

    private static void inject(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }

    private static class Fixture {
        final AgentManager agent = mock(AgentManager.class);
        final Manager manager = new Manager();
        final StorageServiceInstanceVO instance = mock(StorageServiceInstanceVO.class);
        final JsonObject request = new JsonObject();

        Fixture(boolean success, String body) throws Exception {
            VMInstanceDao dao = mock(VMInstanceDao.class);VMInstanceVO vm = mock(VMInstanceVO.class);
            when(instance.getVmId()).thenReturn(54L);when(dao.findById(54L)).thenReturn(vm);
            when(vm.getHostId()).thenReturn(2L);when(vm.getInstanceName()).thenReturn("i-2-54-VM");
            when(agent.easySend(anyLong(), any(StorageServiceHostCommand.class))).thenAnswer(invocation ->
                    new StorageServiceHostAnswer(invocation.getArgument(1), success, SECRET, body));
            StorageServiceGuestCommandDispatcherImpl dispatcher = new StorageServiceGuestCommandDispatcherImpl();
            inject(dispatcher, StorageServiceGuestCommandDispatcherImpl.class, "agentManager", agent);
            inject(dispatcher, StorageServiceGuestCommandDispatcherImpl.class, "vmInstanceDao", dao);
            inject(manager, StorageServiceManagerImpl.class, "guestCommandDispatcher", dispatcher);
            request.addProperty("capsule", SECRET);request.addProperty("credentialPrivateKey", SECRET);
        }

        String failure(String action) {
            try { manager.invoke(instance, action, request);Assert.fail("False answer must remain failure");return null; }
            catch (CloudRuntimeException error) {
                Assert.assertFalse(error.getMessage().contains(SECRET));return error.getMessage();
            }
        }
    }

    private static void generic(String body) throws Exception {
        Assert.assertEquals(GENERIC, new Fixture(false, body).failure("import-local-source"));
    }

    @Test public void realDispatcherFalseAnswerPreservesOnlyPublicClassAndTransport() throws Exception {
        Fixture f = new Fixture(false, VALID);
        Assert.assertEquals(GENERIC + " [nativeReason=ValueError]", f.failure("import-local-source"));
        ArgumentCaptor<StorageServiceHostCommand> command = ArgumentCaptor.forClass(StorageServiceHostCommand.class);
        verify(f.agent).easySend(org.mockito.ArgumentMatchers.eq(2L), command.capture());
        Assert.assertEquals("identity capsule import-local-source", command.getValue().getOperation());
        Assert.assertEquals(120, command.getValue().getTimeoutSeconds());
        Assert.assertEquals(Set.of("capsule", "credentialPrivateKey"), command.getValue().getMaskedFields());
        Assert.assertEquals(f.request.toString(), command.getValue().getPayload());
    }

    @Test public void fourExistingActionsAcceptTheSameClosedNativeFailure() throws Exception {
        for (String action : new String[]{"export-local-source", "import-local-source", "local-source-status", "replay-local-source-auth"})
            Assert.assertEquals(GENERIC + " [nativeReason=ValueError]", new Fixture(false, VALID).failure(action));
    }

    @Test public void successfulNativeResultIsUnchanged() throws Exception {
        String body = "{\"success\":true,\"scope\":{\"revision\":11},\"sourceSmbResumed\":true}";
        Fixture f = new Fixture(true, body);
        Assert.assertEquals(new JsonParser().parse(body), f.manager.invoke(f.instance, "import-local-source", f.request));
    }

    @Test public void literalFalseAndKnownErrorCodeAreRequired() throws Exception {
        generic(VALID.replace("false", "true"));generic(VALID.replace("false", "\"false\""));
        generic(VALID.replace("false", "0"));generic(VALID.replace("false", "FALSE"));generic(VALID.replace("LOCAL_SOURCE_CHECKPOINT_REJECTED", "OTHER_ERROR"));
    }

    @Test public void duplicateFieldsIncludingEscapedNamesAreRejected() throws Exception {
        generic(VALID.replace("{", "{\"reason\":\"TypeError\","));
        generic(VALID.replace("{", "{\"succ\\u0065ss\":false,"));
    }

    @Test public void missingExtraNestedAndSecretFieldsAreRejected() throws Exception {
        generic("{\"errorCode\":\"LOCAL_SOURCE_CHECKPOINT_REJECTED\",\"reason\":\"ValueError\"}");
        generic(VALID.replace("}", ",\"credentialPrivateKey\":\"" + SECRET + "\"}"));
        generic(VALID.replace("\"ValueError\"", "{\"message\":\"" + SECRET + "\"}"));
    }

    @Test public void malformedTrailingArraysAndUnquotedJsonAreRejected() throws Exception {
        generic(VALID + VALID);generic("[" + VALID + "]");generic(VALID.substring(0, VALID.length() - 1));
        generic(VALID.replace("\"success\"", "success"));generic(VALID + " trailing");
    }

    @Test public void onlyAllowlistedClassNamesCanAppearInException() throws Exception {
        for (String reason : new String[]{SECRET, "ValueError: " + SECRET, "ValueError\\n" + SECRET, "ForeignCustomError"})
            generic(VALID.replace("ValueError", reason));
        Assert.assertEquals(GENERIC + " [nativeReason=PermissionError]", new Fixture(false, VALID.replace("ValueError", "PermissionError")).failure("import-local-source"));
    }

    @Test public void nullEmptyAndOversizedResultRemainGeneric() throws Exception {
        generic(null);generic("");generic(" ".repeat(513));generic(" ".repeat(513) + VALID);
    }

    @Test public void unrelatedActionCannotUsePublicDiagnostic() throws Exception {
        Assert.assertEquals(GENERIC, new Fixture(false, VALID).failure("capabilities"));
        Assert.assertEquals(GENERIC, new Fixture(false, VALID).failure(null));
    }

    @Test public void maskedHostFailureDoesNotExposePrivateDetails() throws Exception {
        generic("{\"success\":false,\"message\":\"" + SECRET + "\"}");
        generic("{\"success\":false,\"errorCode\":\"HOST_EXCEPTION\",\"reason\":\"ValueError\"}");
    }
}
