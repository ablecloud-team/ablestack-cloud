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
package org.apache.cloudstack.storage.sharedfs;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.TransactionLegacy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SharedFSDeletionAuditAccessTest {
    private SharedFSServiceImpl service;
    private Account caller;
    private AccountManager accounts;
    private SharedFSDao resources;
    private SharedFSVO projectResource;
    private TransactionLegacy transaction;
    private PreparedStatement statement;
    private MockedStatic<CallContext> contexts;
    private MockedStatic<TransactionLegacy> transactions;
    private static final String UUID = "11111111-1111-4111-8111-111111111111";

    @Before public void setup() throws Exception {
        service=new SharedFSServiceImpl();caller=mock(Account.class);when(caller.getId()).thenReturn(2L);
        accounts=mock(AccountManager.class);resources=mock(SharedFSDao.class);
        projectResource=mock(SharedFSVO.class);when(projectResource.getAccountId()).thenReturn(22L);
        when(resources.findByUuidIncludingRemoved(UUID)).thenReturn(projectResource);
        ReflectionTestUtils.setField(service,"accountMgr",accounts);ReflectionTestUtils.setField(service,"sharedFSDao",resources);
        transaction=mock(TransactionLegacy.class);statement=mock(PreparedStatement.class);ResultSet rows=mock(ResultSet.class);
        when(transaction.prepareAutoCloseStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        contexts=mockStatic(CallContext.class);CallContext context=mock(CallContext.class);when(context.getCallingAccount()).thenReturn(caller);contexts.when(CallContext::current).thenReturn(context);
        transactions=mockStatic(TransactionLegacy.class);transactions.when(TransactionLegacy::currentTxn).thenReturn(transaction);
    }
    @After public void close() {transactions.close();contexts.close();}

    @Test public void authorizedProjectMemberReadsRemovedServiceAuditUnderProjectAccountScope() throws Exception {
        service.listSharedFSDeletionAudits(UUID);
        verify(accounts).checkAccess(caller,null,false,projectResource);
        verify(statement).setLong(1,22L);verify(statement).setString(2,UUID);
    }
    @Test public void deniedProjectMemberCannotQueryAuditRows() {
        doThrow(new PermissionDeniedException("not a project member")).when(accounts).checkAccess(caller,null,false,projectResource);
        Assert.assertThrows(PermissionDeniedException.class,()->service.listSharedFSDeletionAudits(UUID));
        verifyNoInteractions(transaction,statement);
    }
    @Test public void unscopedOrdinaryListingCannotBroadenToOtherAccounts() throws Exception {
        service.listSharedFSDeletionAudits(null);
        verify(statement).setLong(1,2L);verifyNoInteractions(resources);
    }
    @Test public void rootRetainsGlobalAuditScopeAfterResourceRemoval() throws Exception {
        when(accounts.isRootAdmin(2L)).thenReturn(true);
        service.listSharedFSDeletionAudits(UUID);
        verify(statement).setString(1,UUID);verify(statement,never()).setLong(anyInt(),anyLong());verifyNoInteractions(resources);
    }
}
