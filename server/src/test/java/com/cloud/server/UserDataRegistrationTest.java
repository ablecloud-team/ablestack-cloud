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
package com.cloud.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmd;
import org.apache.cloudstack.userdata.UserDataManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

public class UserDataRegistrationTest {
    private ManagementServerImpl manager;
    private UserDataDao dao;
    private UserDataManager normalizer;
    private Account owner;
    private RegisterUserDataCmd cmd;

    @Before
    public void setup() {
        manager = spy(new ManagementServerImpl());
        dao = mock(UserDataDao.class);
        normalizer = mock(UserDataManager.class);
        owner = mock(Account.class);
        cmd = mock(RegisterUserDataCmd.class);
        ReflectionTestUtils.setField(manager, "userDataDao", dao);
        ReflectionTestUtils.setField(manager, "userDataManager", normalizer);
        doReturn(owner).when(manager).getOwner(cmd);
        when(owner.getAccountId()).thenReturn(11L);
        when(owner.getDomainId()).thenReturn(22L);
        when(cmd.getName()).thenReturn("  test  ");
        when(cmd.getUserData()).thenReturn("eA");
        when(cmd.getHttpMethod()).thenReturn(BaseCmd.HTTPMethod.POST);
        when(normalizer.validateUserData("eA", BaseCmd.HTTPMethod.POST)).thenReturn("eA==");
    }

    private GlobalLock lock(MockedStatic<GlobalLock> locks, boolean acquired) {
        GlobalLock lock = mock(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock("userdata-register-22-11")).thenReturn(lock);
        when(lock.lock(10)).thenReturn(acquired);
        return lock;
    }

    @Test
    public void canonicalContentAndTrimmedNameAreCheckedAndStoredForOwner() {
        when(cmd.getParams()).thenReturn("  USER , env.name ");
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, true);
            UserData result = manager.registerUserData(cmd);
            assertEquals("test", result.getName());
            assertEquals("eA==", result.getUserData());
            assertEquals("USER,env.name", result.getParams());
            assertEquals(11, result.getAccountId());
            assertEquals(22, result.getDomainId());
            verify(dao).findByName(11, 22, "test");
            verify(dao).findByUserData(11, 22, "eA==");
            verify(dao).persist(any(UserDataVO.class));
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void canonicalDuplicateIsRejectedWithoutPersistingAndReleasesLock() {
        when(dao.findByUserData(11, 22, "eA==")).thenReturn(mock(UserDataVO.class));
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, true);
            assertThrows(InvalidParameterValueException.class, () -> manager.registerUserData(cmd));
            verify(dao, never()).persist(any(UserDataVO.class));
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void trimmedDuplicateNameIsRejected() {
        when(dao.findByName(11, 22, "test")).thenReturn(mock(UserDataVO.class));
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            lock(locks, true);
            assertThrows(InvalidParameterValueException.class, () -> manager.registerUserData(cmd));
            verify(dao, never()).persist(any(UserDataVO.class));
        }
    }

    @Test
    public void lockTimeoutCannotPersistOrUnlockAnUnownedLock() {
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, false);
            assertThrows(CloudRuntimeException.class, () -> manager.registerUserData(cmd));
            verifyNoInteractions(dao);
            verify(lock, never()).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void persistFailureAlwaysReleasesLock() {
        doThrow(new CloudRuntimeException("DB failure")).when(dao).persist(any(UserDataVO.class));
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, true);
            assertThrows(CloudRuntimeException.class, () -> manager.registerUserData(cmd));
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void blankAndLongNamesAreRejected() {
        for (String value : new String[] {null, "", " \t\n", "\u00a0\u3000", "\uFEFF", "x".repeat(256)}) {
            assertThrows(InvalidParameterValueException.class, () -> UserDataRegistrationValidation.name(value));
        }
        assertEquals("name", UserDataRegistrationValidation.name("\u3000name\u00a0"));
    }

    @Test
    public void blankContentIsRejectedButBinaryAndNewlinesArePreserved() {
        for (String value : new String[] {null, "", "IAkK", "44CAwqA=", "77u/"}) {
            assertThrows(InvalidParameterValueException.class, () -> UserDataRegistrationValidation.requireContent(value));
        }
        UserDataRegistrationValidation.requireContent("/wAB");
        UserDataRegistrationValidation.requireContent("eAo=");
    }

    @Test
    public void parametersAreTrimmedCaseSensitiveAndNotGuestExecution() {
        assertNull(UserDataRegistrationValidation.params(null));
        assertNull(UserDataRegistrationValidation.params(""));
        assertEquals("USER,user,env.name,test-key", UserDataRegistrationValidation.params(" USER , user,env.name,test-key "));
        for (String value : new String[] {" ", "x,", ",x", "x,x", "x, x ", "a b", "x=y", "1x", "instance-id", " instance-id "}) {
            assertThrows(InvalidParameterValueException.class, () -> UserDataRegistrationValidation.params(value));
        }
    }
}
