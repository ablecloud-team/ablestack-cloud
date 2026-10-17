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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.NetworkModel;
import com.cloud.user.Account;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterCniConfigurationCmd;
import org.apache.cloudstack.userdata.UserDataManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

public class CniRegistrationTest {
    private ManagementServerImpl manager;
    private UserDataDao dao;
    private UserDataManager normalizer;
    private Account owner;
    private RegisterCniConfigurationCmd cmd;

    @Before
    public void setup() {
        manager = spy(new ManagementServerImpl());
        dao = mock(UserDataDao.class);
        normalizer = mock(UserDataManager.class);
        owner = mock(Account.class);
        cmd = mock(RegisterCniConfigurationCmd.class);
        ReflectionTestUtils.setField(manager, "userDataDao", dao);
        ReflectionTestUtils.setField(manager, "userDataManager", normalizer);
        doReturn(owner).when(manager).getOwner(cmd);
        when(owner.getAccountId()).thenReturn(11L);
        when(owner.getDomainId()).thenReturn(22L);
        when(cmd.getName()).thenReturn("  CNI  ");
        when(cmd.getCniConfig()).thenReturn("eA");
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
    public void canonicalContentAndOwnerArePersistedAsCniWithoutDuplicateBodyPolicy() {
        when(cmd.getParams()).thenReturn(" USER , env.name, AS_NUMBER ");
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, true);
            UserData result = manager.registerCniConfiguration(cmd);
            assertEquals("CNI", result.getName());
            assertEquals("eA==", result.getUserData());
            assertEquals("USER,env.name,AS_NUMBER", result.getParams());
            assertEquals(11L, result.getAccountId());
            assertEquals(22L, result.getDomainId());
            assertTrue(result.isForCks());
            verify(dao).findByName(11L, 22L, "CNI");
            verify(dao, never()).findByUserData(anyLong(), anyLong(), anyString());
            verify(dao).persist(any(UserDataVO.class));
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void invalidBodyCannotPersistAndUsesCniErrors() {
        for (String value : new String[] {null, "", "IAkK", "44CAwqA=", "77u/", "woU="}) {
            when(normalizer.validateUserData("eA", BaseCmd.HTTPMethod.POST)).thenReturn(value);
            InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                    () -> manager.registerCniConfiguration(cmd));
            assertTrue(error.getMessage().contains("CNI configuration"));
        }
        verifyNoInteractions(dao);
    }

    @Test
    public void sharedNormalizerEnforcesEncodingAndConfiguredHttpSizeBeforePersistence() {
        when(normalizer.validateUserData("eA", BaseCmd.HTTPMethod.POST))
                .thenThrow(new InvalidParameterValueException("User data has exceeded configurable max length: 32768"));
        InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                () -> manager.registerCniConfiguration(cmd));
        assertTrue(error.getMessage().contains("CNI"));
        assertTrue(error.getMessage().contains("32768"));
        verifyNoInteractions(dao);
    }

    @Test
    public void invalidDuplicateAndEveryReservedMetadataVariableCannotPersist() {
        for (String value : new String[] {" ", "x,", ",x", "x,x", "x, x ", "1x", "a b", "x=y"}) {
            when(cmd.getParams()).thenReturn(value);
            assertThrows(InvalidParameterValueException.class, () -> manager.registerCniConfiguration(cmd));
        }
        for (String value : NetworkModel.metadataFileNames) {
            when(cmd.getParams()).thenReturn("  " + value + " ");
            assertThrows(InvalidParameterValueException.class, () -> manager.registerCniConfiguration(cmd));
        }
        verifyNoInteractions(dao);
    }

    @Test
    public void binaryCanonicalContentRemainsSupported() {
        when(normalizer.validateUserData("eA", BaseCmd.HTTPMethod.POST)).thenReturn("/wAB");
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            lock(locks, true);
            assertEquals("/wAB", manager.registerCniConfiguration(cmd).getUserData());
        }
    }

    @Test
    public void normalizedDuplicateNameUsesSameNamespaceAsNormalUserData() {
        when(dao.findByName(11L, 22L, "CNI")).thenReturn(mock(UserDataVO.class));
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, true);
            assertThrows(InvalidParameterValueException.class, () -> manager.registerCniConfiguration(cmd));
            verify(dao, never()).persist(any(UserDataVO.class));
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void lockTimeoutCannotWriteOrUnlockAndAlwaysReleasesReference() {
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, false);
            assertThrows(CloudRuntimeException.class, () -> manager.registerCniConfiguration(cmd));
            verifyNoInteractions(dao);
            verify(lock, never()).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void databaseFailureReleasesOwnerLock() {
        doThrow(new CloudRuntimeException("DB failure")).when(dao).persist(any(UserDataVO.class));
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = lock(locks, true);
            assertThrows(CloudRuntimeException.class, () -> manager.registerCniConfiguration(cmd));
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }
}
