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
package com.cloud.user;

import org.apache.cloudstack.api.command.admin.account.CreateAccountCmd;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class AccountCreationEnableTest {
    private void assertOptionalEnable(Boolean requested, boolean expected) {
        AccountManagerImpl manager = Mockito.mock(AccountManagerImpl.class, Mockito.CALLS_REAL_METHODS);
        CreateAccountCmd command = Mockito.mock(CreateAccountCmd.class);
        UserAccount result = Mockito.mock(UserAccount.class);
        Mockito.when(command.getEnable()).thenReturn(requested);
        Mockito.doReturn(result).when(manager).createUserAccount(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        Assert.assertSame(result, manager.createUserAccount(command));
        Mockito.verify(manager).createUserAccount(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(User.Source.UNKNOWN), Mockito.eq(expected));
    }

    @Test
    public void omittedEnableUsesExistingDisabledDefaultWithoutNullUnboxing() {
        assertOptionalEnable(null, false);
    }

    @Test
    public void explicitFalseRemainsFalse() {
        assertOptionalEnable(false, false);
    }

    @Test
    public void explicitTruePreservesExternalIdentityEnablement() {
        assertOptionalEnable(true, true);
    }
}
