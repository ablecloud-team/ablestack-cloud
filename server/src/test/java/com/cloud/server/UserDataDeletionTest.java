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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import com.cloud.api.ApiDBUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.component.ComponentContext;
import org.apache.cloudstack.userdata.CniConfigurationReferenceChecker;
import org.junit.After;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.user.userdata.DeleteCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmd;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(Parameterized.class)
public class UserDataDeletionTest {
    @Parameterized.Parameters(name = "forCks={0}")
    public static Collection<Object[]> types() { return Arrays.asList(new Object[][] {{false}, {true}}); }

    private final boolean forCks;
    private ManagementServerImpl manager;
    private UserDataDao dao;
    private AnnotationDao annotations;
    private VMTemplateDao templates;
    private UserVmDao vms;
    private AccountManager accounts;
    private AccountDao accountDao;
    private Account caller;
    private Account owner;
    private UserDataVO data;
    private DeleteUserDataCmd cmd;
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<ComponentContext> components;

    public UserDataDeletionTest(boolean forCks) { this.forCks = forCks; }

    @Before
    public void setup() {
        components = mockStatic(ComponentContext.class);
        components.when(() -> ComponentContext.getDelegateComponentOfType(CniConfigurationReferenceChecker.class)).thenReturn(mock(CniConfigurationReferenceChecker.class));
        locks = mockStatic(GlobalLock.class);
        GlobalLock lock = mock(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock("CniConfiguration.Reference.17")).thenReturn(lock);
        when(lock.lock(10)).thenReturn(true);
        manager = spy(new ManagementServerImpl());
        dao = mock(UserDataDao.class); annotations = mock(AnnotationDao.class);
        templates = mock(VMTemplateDao.class); vms = mock(UserVmDao.class);
        accounts = mock(AccountManager.class); accountDao = mock(AccountDao.class);
        caller = mock(Account.class); owner = mock(Account.class); data = mock(UserDataVO.class);
        cmd = forCks ? mock(DeleteCniConfigurationCmd.class) : mock(DeleteUserDataCmd.class);
        ReflectionTestUtils.setField(manager, "userDataDao", dao);
        ReflectionTestUtils.setField(manager, "annotationDao", annotations);
        ReflectionTestUtils.setField(manager, "templateDao", templates);
        ReflectionTestUtils.setField(manager, "_userVmDao", vms);
        ReflectionTestUtils.setField(manager, "_accountMgr", accounts);
        ReflectionTestUtils.setField(manager, "_accountDao", accountDao);
        doReturn(caller).when(manager).getCaller();
        when(cmd.getId()).thenReturn(17L); when(cmd.getAccountName()).thenReturn("owner");
        when(cmd.getDomainId()).thenReturn(22L); when(cmd.getProjectId()).thenReturn(33L);
        when(accounts.finalizeOwner(caller, "owner", 22L, 33L)).thenReturn(owner);
        when(owner.getAccountName()).thenReturn("owner"); when(owner.getDomainId()).thenReturn(22L);
        when(dao.findById(17L)).thenReturn(data);
        when(data.getId()).thenReturn(17L); when(data.getUuid()).thenReturn("resource-uuid");
        when(data.getName()).thenReturn("fixture"); when(data.isForCks()).thenReturn(forCks);
        when(dao.remove(17L)).thenReturn(true);
    }

    @After
    public void closeLocks() { locks.close(); components.close(); }

    @Test
    public void integrationReferenceBlocksOnlyCniAndPreservesComments() {
        CniConfigurationReferenceChecker checker = mock(CniConfigurationReferenceChecker.class);
        components.when(() -> ComponentContext.getDelegateComponentOfType(CniConfigurationReferenceChecker.class)).thenReturn(checker);
        when(checker.hasActiveCniReferences(17L)).thenReturn(true);
        if (forCks) {
            assertThrows(InvalidParameterValueException.class, this::delete);
            noDeletion(); verifyNoInteractions(templates, vms);
        } else {
            try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
                transaction(transactions); assertTrue(delete()); verifyNoInteractions(checker);
            }
        }
    }

    @Test
    public void failedReferenceLookupCannotDeleteCni() {
        CniConfigurationReferenceChecker checker = mock(CniConfigurationReferenceChecker.class);
        components.when(() -> ComponentContext.getDelegateComponentOfType(CniConfigurationReferenceChecker.class)).thenReturn(checker);
        when(checker.hasActiveCniReferences(17L)).thenThrow(new CloudRuntimeException("reference store failure"));
        if (forCks) { assertThrows(CloudRuntimeException.class, this::delete); noDeletion(); }
        else {
            try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
                transaction(transactions); assertTrue(delete()); verifyNoInteractions(checker);
            }
        }
    }

    @Test
    public void unavailableIntegrationFailsClosedOnlyForCni() {
        components.when(() -> ComponentContext.getDelegateComponentOfType(CniConfigurationReferenceChecker.class)).thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("CNI checker"));
        if (forCks) { assertThrows(CloudRuntimeException.class, this::delete); noDeletion(); }
        else {
            try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
                transaction(transactions); assertTrue(delete());
            }
        }
    }

    private boolean delete() {
        return forCks ? manager.deleteCniConfiguration((DeleteCniConfigurationCmd) cmd) : manager.deleteUserData(cmd);
    }

    private void noDeletion() {
        verify(dao, never()).remove(anyLong()); verifyNoInteractions(annotations);
    }

    private TransactionLegacy transaction(MockedStatic<TransactionLegacy> transactions) {
        TransactionLegacy txn = mock(TransactionLegacy.class);
        transactions.when(() -> TransactionLegacy.open(anyString(), eq(TransactionLegacy.CLOUD_DB), eq(false))).thenReturn(txn);
        return txn;
    }

    @Test
    public void wrongTypeIsRejectedBeforeReferencesOrMutations() {
        when(data.isForCks()).thenReturn(!forCks);
        InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class, this::delete);
        assertTrue(error.getMessage().contains(forCks ? "deleteUserData" : "deleteCniConfiguration"));
        noDeletion(); verifyNoInteractions(templates, vms);
        verify(accounts).finalizeOwner(caller, "owner", 22L, 33L);
    }

    @Test
    public void missingOrRemovedRecordCannotMutateAnnotations() {
        when(dao.findById(17L)).thenReturn(null);
        try (MockedStatic<ApiDBUtils> ignored = mockStatic(ApiDBUtils.class)) {
            InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class, this::delete);
            assertTrue(error.getMessage().contains("does not exist"));
        }
        noDeletion(); verifyNoInteractions(templates, vms);
    }

    @Test
    public void ownerResolutionFailureDoesNotReadOrDeleteResource() {
        when(accounts.finalizeOwner(caller, "owner", 22L, 33L)).thenThrow(new InvalidParameterValueException("owner forbidden"));
        assertThrows(InvalidParameterValueException.class, this::delete);
        verifyNoInteractions(dao, annotations, templates, vms);
    }

    @Test
    public void removedAccountFallbackRemainsRestrictedToRootAdmin() {
        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        when(accounts.finalizeOwner(caller, "owner", 22L, 33L)).thenThrow(new InvalidParameterValueException("removed account"));
        when(accountDao.findAccountIncludingRemoved("owner", 22L)).thenReturn(owner);
        when(data.isForCks()).thenReturn(!forCks);
        assertThrows(InvalidParameterValueException.class, this::delete);
        verify(accountDao).findAccountIncludingRemoved("owner", 22L); noDeletion();
    }

    @Test
    public void activeTemplateReferenceBlocksDeletionAndCommentsRemain() {
        when(templates.findTemplatesLinkedToUserdata(17L)).thenReturn(Collections.singletonList(mock(VMTemplateVO.class)));
        assertThrows(CloudRuntimeException.class, this::delete); noDeletion(); verifyNoInteractions(vms);
    }

    @Test
    public void activeVmReferenceBlocksDeletionAndCommentsRemain() {
        when(vms.findByUserDataId(17L)).thenReturn(Collections.singletonList(mock(UserVmVO.class)));
        assertThrows(CloudRuntimeException.class, this::delete); noDeletion();
    }

    @Test
    public void matchingTypeDeletesRecordAndCommentsInOneCommittedTransaction() {
        try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
            TransactionLegacy txn = transaction(transactions);
            assertTrue(delete());
            org.mockito.InOrder order = inOrder(txn, dao, annotations);
            order.verify(txn).start(); order.verify(dao).remove(17L);
            order.verify(annotations).removeByEntityType(AnnotationService.EntityType.USER_DATA.name(), "resource-uuid");
            order.verify(txn).commit(); order.verify(txn).close();
        }
    }

    @Test
    public void noCommentsIsAValidSuccessfulDeletion() {
        when(annotations.removeByEntityType(anyString(), anyString())).thenReturn(false);
        try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
            TransactionLegacy txn = transaction(transactions); assertTrue(delete()); verify(txn).commit();
        }
    }

    @Test
    public void failedRecordRemovalAbortsWithoutTouchingCommentsOrCommitting() {
        when(dao.remove(17L)).thenReturn(false);
        try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
            TransactionLegacy txn = transaction(transactions);
            assertThrows(CloudRuntimeException.class, this::delete);
            verifyNoInteractions(annotations); verify(txn, never()).commit(); verify(txn).close();
        }
    }

    @Test
    public void recordStorageExceptionDoesNotCommitOrRemoveComments() {
        when(dao.remove(17L)).thenThrow(new CloudRuntimeException("storage failure"));
        try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
            TransactionLegacy txn = transaction(transactions);
            assertThrows(CloudRuntimeException.class, this::delete);
            verifyNoInteractions(annotations); verify(txn, never()).commit(); verify(txn).close();
        }
    }

    @Test
    public void annotationExceptionAbortsTheSameTransactionAsRecordRemoval() {
        when(annotations.removeByEntityType(anyString(), anyString())).thenThrow(new CloudRuntimeException("annotation failure"));
        try (MockedStatic<TransactionLegacy> transactions = mockStatic(TransactionLegacy.class)) {
            TransactionLegacy txn = transaction(transactions);
            assertThrows(CloudRuntimeException.class, this::delete);
            verify(dao).remove(17L); verify(txn, never()).commit(); verify(txn).close();
        }
    }
}
