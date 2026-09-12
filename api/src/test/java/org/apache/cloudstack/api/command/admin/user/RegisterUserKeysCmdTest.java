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
package org.apache.cloudstack.api.command.admin.user;

import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.response.ApiKeyPairResponse;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.commons.lang3.reflect.FieldUtils;
import com.cloud.user.AccountService;

public class RegisterUserKeysCmdTest {
    @Test
    public void returnsKeyPairSynchronouslyWithSensitiveResponseMetadata() throws Exception {
        RegisterUserKeysCmd command = new RegisterUserKeysCmd();
        AccountService accounts = Mockito.mock(AccountService.class);
        ResponseGenerator responses = Mockito.mock(ResponseGenerator.class);
        FieldUtils.writeField(command, "_accountService", accounts, true);
        FieldUtils.writeField(command, "_responseGenerator", responses, true);
        ApiKeyPair pair = Mockito.mock(ApiKeyPair.class);
        ApiKeyPairResponse response = new ApiKeyPairResponse();
        Mockito.when(accounts.createApiKeyAndSecretKey(command)).thenReturn(pair);
        Mockito.when(responses.createKeyPairResponse(pair)).thenReturn(response);
        command.execute();
        Assert.assertSame(response, command.getResponseObject());
        Assert.assertEquals("userkeys", response.getObjectName());
        Assert.assertFalse(BaseAsyncCmd.class.isAssignableFrom(RegisterUserKeysCmd.class));
        Assert.assertTrue(RegisterUserKeysCmd.class.getAnnotation(APICommand.class).responseHasSensitiveInfo());
    }
}
