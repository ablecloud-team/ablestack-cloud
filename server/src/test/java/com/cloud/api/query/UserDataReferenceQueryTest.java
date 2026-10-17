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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.resource.ResourceManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/** Exercise both command routes through their real SQL count/pagination boundary. */
public class UserDataReferenceQueryTest {
    private final QueryManagerImpl query = new QueryManagerImpl();
    private final TemplateJoinDao dao = mock(TemplateJoinDao.class);
    private final SearchCriteria<TemplateJoinVO> criteria = mock(SearchCriteria.class);

    @Before
    public void setup() {
        Account caller = mock(Account.class);
        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(mock(User.class), caller);
        ReflectionTestUtils.setField(query, "accountMgr", mock(AccountManager.class));
        ReflectionTestUtils.setField(query, "_domainDao", mock(DomainDao.class));
        ResourceManager resources = mock(ResourceManager.class);
        when(resources.listAvailHypervisorInZone(null)).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(query, "_resourceMgr", resources);
        SearchBuilder<TemplateJoinVO> builder = mock(SearchBuilder.class);
        when(builder.entity()).thenReturn(mock(TemplateJoinVO.class));
        when(builder.create()).thenReturn(criteria);
        when(dao.createSearchBuilder()).thenReturn(builder);
        when(dao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
            .thenReturn(new Pair<>(Collections.emptyList(), 0));
        ReflectionTestUtils.setField(query, "_templateJoinDao", dao);
    }

    @After
    public void cleanup() { CallContext.unregister(); }

    private void templates(Long userDataId) {
        ListTemplatesCmd cmd = mock(ListTemplatesCmd.class, invocation -> invocation.getMethod().getReturnType() == Long.class ? null : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        when(cmd.getTemplateFilter()).thenReturn("all");
        when(cmd.getUserDataId()).thenReturn(userDataId);
        when(cmd.getShowUnique()).thenReturn(true);
        when(cmd.getPageSizeVal()).thenReturn(10L);
        when(cmd.getStartIndex()).thenReturn(10L);
        Pair<?, Integer> result = ReflectionTestUtils.invokeMethod(query, "searchForTemplatesInternal", cmd);
        assertEquals(Integer.valueOf(0), result.second());
    }

    @Test
    public void templatesFilterBeforeCount() {
        templates(42L);
        org.mockito.InOrder order = inOrder(criteria, dao);
        order.verify(criteria).addAnd("userDataId", SearchCriteria.Op.EQ, 42L);
        order.verify(dao).searchAndDistinctCount(eq(criteria), any(Filter.class), any(String[].class));
    }

    @Test
    public void omittedFilterPreservesExistingQueries() {
        templates(null);
        verify(criteria, never()).addAnd(eq("userDataId"), any(), any());
        verify(dao).searchAndDistinctCount(eq(criteria), any(Filter.class), any(String[].class));
    }

    @Test
    public void isosFilterBeforeCount() {
        ListIsosCmd cmd = mock(ListIsosCmd.class, invocation -> invocation.getMethod().getReturnType() == Long.class ? null : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        when(cmd.getIsoFilter()).thenReturn("all");
        when(cmd.getUserDataId()).thenReturn(43L);
        when(cmd.getShowUnique()).thenReturn(true);
        ReflectionTestUtils.invokeMethod(query, "searchForIsosInternal", cmd);
        org.mockito.InOrder order = inOrder(criteria, dao);
        order.verify(criteria).addAnd("userDataId", SearchCriteria.Op.EQ, 43L);
        order.verify(dao).searchAndDistinctCount(eq(criteria), any(Filter.class), any(String[].class));
    }
}
