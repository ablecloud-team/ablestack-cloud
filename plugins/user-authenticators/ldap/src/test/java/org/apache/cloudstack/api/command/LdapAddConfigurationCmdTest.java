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
package org.apache.cloudstack.api.command;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.ldap.LdapManager;
import org.apache.cloudstack.ldap.LdapRegistrationException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LdapAddConfigurationCmdTest {
    @Test
    public void exposesDistinctRegistrationCodesWithoutChangingHttpStatus() {
        assertReason(LdapRegistrationException.DUPLICATE, "Duplicate configuration");
        assertReason(LdapRegistrationException.BIND_FAILED, "Unable to bind to the given LDAP server");
    }

    private void assertReason(int reason, String message) {
        LdapManager manager = mock(LdapManager.class);
        LdapAddConfigurationCmd command = new LdapAddConfigurationCmd(manager);
        command.setHostname("ldap.example.test");
        command.setPort(389);
        when(manager.addConfiguration("ldap.example.test", 389, null)).thenThrow(new LdapRegistrationException(reason, message));

        ServerApiException failure = assertThrows(ServerApiException.class, command::execute);
        assertEquals(ApiErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertEquals(reason, failure.getCSErrorCode());
        assertEquals("com.cloud.exception.InvalidParameterValueException: " + message, failure.getMessage());
    }
}
