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
package org.apache.cloudstack.api.command.user.userdata;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.cloud.server.ManagementService;
import com.cloud.user.AccountService;
import com.cloud.user.UserData;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.response.UserDataResponse;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class RegisterCniConfigurationCmdTest {
    @Test
    public void discoveryMatchesRequiredInputAndRealCniObjectResponse() throws Exception {
        APICommand command = RegisterCniConfigurationCmd.class.getAnnotation(APICommand.class);
        assertEquals(UserDataResponse.class, command.responseObject());
        Parameter input = RegisterCniConfigurationCmd.class.getDeclaredField("cniConfig").getAnnotation(Parameter.class);
        assertTrue(input.required());
        assertEquals(1048576, input.length());
        RegisterCniConfigurationCmd cmd = new RegisterCniConfigurationCmd();
        ManagementService manager = mock(ManagementService.class);
        ResponseGenerator generator = mock(ResponseGenerator.class);
        UserData data = mock(UserData.class);
        UserDataResponse response = mock(UserDataResponse.class);
        ReflectionTestUtils.setField(cmd, "_mgr", manager);
        ReflectionTestUtils.setField(cmd, "_responseGenerator", generator);
        when(manager.registerCniConfiguration(cmd)).thenReturn(data);
        when(generator.createUserDataResponse(data)).thenReturn(response);
        cmd.execute();
        assertSame(response, cmd.getResponseObject());
        verify(response).setObjectName("cniconfig");
        verify(response).setResponseName(cmd.getCommandName());
    }

    @Test
    public void accountAndProjectOwnershipUseExistingFinalizer() {
        RegisterCniConfigurationCmd cmd = new RegisterCniConfigurationCmd();
        AccountService accounts = mock(AccountService.class);
        ReflectionTestUtils.setField(cmd, "_accountService", accounts);
        ReflectionTestUtils.setField(cmd, "accountName", "owner");
        ReflectionTestUtils.setField(cmd, "domainId", 22L);
        ReflectionTestUtils.setField(cmd, "projectId", 33L);
        when(accounts.finalizeAccountId("owner", 22L, 33L, true)).thenReturn(44L);
        assertEquals(44L, cmd.getEntityOwnerId());
        verify(accounts).finalizeAccountId("owner", 22L, 33L, true);
    }
}
