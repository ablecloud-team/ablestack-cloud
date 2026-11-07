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

package com.cloud.api.query;

import java.util.Collections;

import org.apache.cloudstack.api.command.user.project.ListProjectsCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.dao.ProjectJoinDao;
import com.cloud.api.query.vo.ProjectJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ProjectDomainQueryTest {
    @InjectMocks
    private QueryManagerImpl manager;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private DomainDao domainDao;
    @Mock
    private ProjectJoinDao projectJoinDao;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private SearchBuilder<ProjectJoinVO> builder;
    @Mock
    private SearchCriteria<ProjectJoinVO> criteria;
    @Mock
    private ProjectJoinVO entity;
    @Mock
    private Account caller;
    @Mock
    private User user;
    @Mock
    private ListProjectsCmd command;
    @Mock
    private CallContext context;
    private MockedStatic<CallContext> current;

    @Before
    public void setup() {
        current = Mockito.mockStatic(CallContext.class);
        current.when(CallContext::current).thenReturn(context);
        when(context.getCallingAccount()).thenReturn(caller);
        when(context.getCallingUser()).thenReturn(user);
        when(caller.getId()).thenReturn(7L);
        when(command.listAll()).thenReturn(true);
        when(command.getDomainId()).thenReturn(null);
        when(command.getId()).thenReturn(null);
        when(projectJoinDao.createSearchBuilder()).thenReturn(builder);
        when(builder.entity()).thenReturn(entity);
        Mockito.lenient().doReturn(builder).when(builder).and();
        when(builder.create()).thenReturn(criteria);
        Mockito.lenient().when(projectJoinDao.searchAndCount(eq(criteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
    }

    @After
    public void closeContext() {
        current.close();
    }

    private void query() {
        ReflectionTestUtils.invokeMethod(manager, "listProjectsInternal", command);
    }

    @Test
    public void domainAdministratorIncludesDescendantsWithinItsPathBoundary() {
        DomainVO domain = Mockito.mock(DomainVO.class);
        when(caller.getDomainId()).thenReturn(105L);
        when(accountMgr.isAdmin(7L)).thenReturn(true);
        when(accountMgr.isDomainAdmin(7L)).thenReturn(true);
        when(domainDao.findById(105L)).thenReturn(domain);
        when(domain.getPath()).thenReturn("/1/105/");
        query();
        // The trailing slash protects adjacent domain IDs such as /1/1050/.
        verify(criteria).setParameters("domainPath", "/1/105/%");
        verify(criteria, never()).setParameters(eq("accountId"), any());
        verify(criteria, never()).setParameters(eq("userId"), any());
    }

    @Test
    public void rootAdministratorDoesNotAcquireAnAccidentalDomainFilter() {
        when(accountMgr.isAdmin(7L)).thenReturn(true);
        query();
        verify(criteria, never()).setParameters(eq("domainPath"), any());
        verify(domainDao, never()).findById(Mockito.anyLong());
    }

    @Test
    public void ordinaryUsersRemainFilteredByTheirMembership() {
        when(user.getId()).thenReturn(11L);
        query();
        verify(criteria).setParameters("accountId", 7L);
        verify(criteria).setParameters("userId", 11L);
        verify(criteria, never()).setParameters(eq("domainPath"), any());
    }

    @Test
    public void explicitOutsideDomainStillRequiresDomainAuthorization() {
        DomainVO outside = Mockito.mock(DomainVO.class);
        when(command.getDomainId()).thenReturn(999L);
        when(accountMgr.isAdmin(7L)).thenReturn(true);
        when(domainDao.findById(999L)).thenReturn(outside);
        Mockito.doThrow(new PermissionDeniedException("outside domain")).when(accountMgr).checkAccess(caller, outside);
        assertThrows(PermissionDeniedException.class, this::query);
        verify(projectJoinDao, never()).searchAndCount(any(), any());
    }
}

