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

import java.util.Map;
import com.cloud.api.dispatch.ParamProcessWorker;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

public class StoragePermissionPreviewParameterTest {
    private ParamProcessWorker worker;
    private StorageService handler;
    @Before public void setup() {
        Account account=mock(Account.class);when(account.getId()).thenReturn(41L);User user=mock(User.class);when(user.getId()).thenReturn(51L);CallContext.register(user,account);
        worker=new ParamProcessWorker();ReflectionTestUtils.setField(worker,"_accountMgr",mock(AccountManager.class));handler=mock(StorageService.class);
    }
    @After public void close() {CallContext.unregister();}
    private CreateStoragePosixDirectoryPolicyCmd command() {
        CreateStoragePosixDirectoryPolicyCmd command=new CreateStoragePosixDirectoryPolicyCmd();ReflectionTestUtils.setField(command,"storageService",handler);return command;
    }
    @Test public void signedApprovalForOneHundredAffectedSharesPassesActualApiParameterProcessing() throws Exception {
        JsonObject intent=new JsonObject();intent.addProperty("volumeUuid","22222222-2222-4222-8222-222222222222");JsonArray shares=new JsonArray();for(int i=0;i<100;i++){JsonObject share=new JsonObject();share.addProperty("uuid",java.util.UUID.randomUUID().toString());share.addProperty("protocol","NFS");share.addProperty("path","/export/authorized/"+i);shares.add(share);}intent.add("affectedShares",shares);
        JsonObject identity=new JsonObject();identity.addProperty("filesystemUuid","33333333-3333-4333-8333-333333333333");identity.addProperty("inode",9001);identity.addProperty("device",2049);
        String token=StoragePermissionPreviewToken.issue(intent,identity,51L,1000L,"unit-test-only-key");Assert.assertTrue(token.length()>255);Assert.assertTrue(token.length()<131072);
        CreateStoragePosixDirectoryPolicyCmd command=command();worker.processParameters(command,Map.of("previewtoken",token,"applyconfirmation","true"));
        Assert.assertEquals(token,command.getPreviewToken());Assert.assertEquals(intent,StoragePermissionPreviewToken.verify(command.getPreviewToken(),intent,identity,51L,2000L,"unit-test-only-key").get("intent"));verifyNoInteractions(handler);
        Assert.assertEquals(131072,BaseStoragePosixDirectoryPolicyCmd.class.getDeclaredField("previewToken").getAnnotation(org.apache.cloudstack.api.Parameter.class).length());
    }
    @Test public void oversizedApprovalIsRejectedBeforeAnyPolicyOrGuestHandlerInvocation() {
        CreateStoragePosixDirectoryPolicyCmd command=command();ServerApiException failure=Assert.assertThrows(ServerApiException.class,()->worker.processParameters(command,Map.of("previewtoken","a".repeat(131073))));
        Assert.assertTrue(failure.getMessage().contains("131072"));Assert.assertNull(command.getPreviewToken());verifyNoInteractions(handler);
    }
    @Test public void approvalRequestsAndResponsesAreSensitiveAndRequireAnEncryptedQueue() {
        for(Class<?> type:new Class<?>[]{org.apache.cloudstack.api.command.user.storage.dataservice.CreateStoragePosixDirectoryPolicyCmd.class,org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStoragePosixDirectoryPolicyCmd.class,org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStoragePosixDirectoryPolicyCmd.class}) {
            org.apache.cloudstack.api.APICommand metadata=type.getAnnotation(org.apache.cloudstack.api.APICommand.class);Assert.assertTrue(metadata.requestHasSensitiveInfo());Assert.assertTrue(metadata.responseHasSensitiveInfo());
        }
        Object previous=ReflectionTestUtils.getField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_useEncryption");
        try {
            ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_useEncryption",false);
            Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class,()->command().validateSpecificParameters(Map.of("previewtoken","test-approval")));
            command().validateSpecificParameters(Map.of("preview","true"));
            ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_useEncryption",true);command().validateSpecificParameters(Map.of("previewtoken","test-approval"));
        } finally {ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_useEncryption",previous);}
        verifyNoInteractions(handler);
    }

}
