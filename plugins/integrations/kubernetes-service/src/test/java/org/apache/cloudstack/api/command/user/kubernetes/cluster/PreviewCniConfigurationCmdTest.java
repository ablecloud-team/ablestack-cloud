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

package org.apache.cloudstack.api.command.user.kubernetes.cluster;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.response.CniConfigurationPreviewResponse;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import org.apache.cloudstack.userdata.UserDataManager;

public class PreviewCniConfigurationCmdTest {
    private PreviewCniConfigurationCmd cmd;private UserDataDao dao;private AccountManager accounts;private Account caller;private UserDataVO cni;
    @Before public void setup() {
        cmd=new PreviewCniConfigurationCmd();dao=mock(UserDataDao.class);accounts=mock(AccountManager.class);caller=mock(Account.class);cni=mock(UserDataVO.class);
        CallContext.register(mock(User.class),caller);ReflectionTestUtils.setField(cmd,"userDataDao",dao);ReflectionTestUtils.setField(cmd,"accountManager",accounts);
        when(dao.findById(42L)).thenReturn(cni);when(cni.isForCks()).thenReturn(true);when(cni.getUuid()).thenReturn("cni-uuid");when(cni.getParams()).thenReturn("VALUE");when(cni.getUserData()).thenReturn(Base64.getEncoder().encodeToString("{{ VALUE }}".getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(cmd,"id",42L);
        UserDataManager manager=mock(UserDataManager.class);when(manager.validateUserData(anyString(),any())).thenAnswer(call -> call.getArgument(0));ReflectionTestUtils.setField(cmd,"userDataManager",manager);
    }
    @After public void cleanup() { CallContext.unregister(); }
    @Test public void registeredSourceRequiresAclAndReturnsIdentityWithoutWriting() {
        cmd.execute();CniConfigurationPreviewResponse result=(CniConfigurationPreviewResponse)cmd.getResponseObject();
        verify(accounts).checkAccess(caller,AccessType.UseEntry,false,cni);assertEquals("cni-uuid",result.id);assertEquals("VALUE",result.missing.get(0));verify(dao,never()).persist(any());
    }
    @Test public void accessDenialAndWrongTypeCannotReturnASecretBody() {
        doThrow(new PermissionDeniedException("denied")).when(accounts).checkAccess(caller,AccessType.UseEntry,false,cni);
        assertThrows(PermissionDeniedException.class,cmd::execute);assertNull(cmd.getResponseObject());
        reset(accounts);when(cni.isForCks()).thenReturn(false);assertThrows(InvalidParameterValueException.class,cmd::execute);
    }
    @Test public void ambiguousSourceAndRegisteredParameterOverridesAreRejected() {
        ReflectionTestUtils.setField(cmd,"cniConfig","eA==");assertThrows(InvalidParameterValueException.class,cmd::execute);
        ReflectionTestUtils.setField(cmd,"cniConfig",null);ReflectionTestUtils.setField(cmd,"params","OTHER");assertThrows(InvalidParameterValueException.class,cmd::execute);
    }
    @Test public void localDraftUsesExistingSizeValidationAndNoDatabaseLookup() {
        ReflectionTestUtils.setField(cmd,"id",null);ReflectionTestUtils.setField(cmd,"cniConfig","eA==");UserDataManager manager=mock(UserDataManager.class);
        when(manager.validateUserData(eq("eA=="),any())).thenReturn("eA==");ReflectionTestUtils.setField(cmd,"userDataManager",manager);cmd.setHttpMethod("POST");cmd.execute();
        verify(manager,times(2)).validateUserData(anyString(),any());verifyNoInteractions(dao);assertNull(((CniConfigurationPreviewResponse)cmd.getResponseObject()).id);
    }
    @Test public void discoveryMarksBothDirectionsSensitive() {
        APICommand annotation=PreviewCniConfigurationCmd.class.getAnnotation(APICommand.class);assertTrue(annotation.requestHasSensitiveInfo());assertTrue(annotation.responseHasSensitiveInfo());
    }
}
