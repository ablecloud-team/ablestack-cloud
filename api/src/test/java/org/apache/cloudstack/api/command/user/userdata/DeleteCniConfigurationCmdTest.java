// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.command.user.userdata;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.UserDataResponse;
import com.cloud.server.ManagementService;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class DeleteCniConfigurationCmdTest {
    @Test
    public void cniDispatchDoesNotUseOrdinaryUserDataEndpoint() {
        ManagementService service = mock(ManagementService.class);
        DeleteCniConfigurationCmd cmd = new DeleteCniConfigurationCmd();
        ReflectionTestUtils.setField(cmd, "_mgr", service);
        when(service.deleteCniConfiguration(cmd)).thenReturn(true);
        cmd.execute(); assertTrue(cmd.getResponseObject() instanceof SuccessResponse);
        verify(service).deleteCniConfiguration(cmd); verify(service, never()).deleteUserData(cmd);
    }

    @Test
    public void falseResultCannotProduceSuccessfulResponse() {
        ManagementService service = mock(ManagementService.class);
        DeleteCniConfigurationCmd cmd = new DeleteCniConfigurationCmd();
        ReflectionTestUtils.setField(cmd, "_mgr", service);
        assertThrows(ServerApiException.class, cmd::execute);
    }

    @Test
    public void inheritedUuidAclAndOwnerScopeParametersRemainIntact() throws Exception {
        Field id = DeleteUserDataCmd.class.getDeclaredField("id");
        assertEquals(SecurityChecker.AccessType.OperateEntry, id.getAnnotation(ACL.class).accessType());
        Parameter parameter = id.getAnnotation(Parameter.class);
        assertTrue(parameter.required()); assertEquals(UserDataResponse.class, parameter.entityType()[0]);
        assertEquals(DeleteUserDataCmd.class, DeleteCniConfigurationCmd.class.getSuperclass());
        for (String scope : new String[] {"accountName", "domainId", "projectId"}) {
            assertNotNull(DeleteUserDataCmd.class.getDeclaredField(scope).getAnnotation(Parameter.class));
        }
    }
}
