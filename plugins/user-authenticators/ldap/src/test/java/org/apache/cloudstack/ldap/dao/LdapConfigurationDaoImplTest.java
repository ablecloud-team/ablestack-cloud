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
package org.apache.cloudstack.ldap.dao;

import java.util.List;

import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.ldap.LdapConfigurationVO;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class LdapConfigurationDaoImplTest {
    private final LdapConfigurationDaoImpl dao = spy(new LdapConfigurationDaoImpl());

    @Test
    public void appliesExactScopeAndLiteralKeywordBeforePagination() {
        Filter page = new Filter(LdapConfigurationVO.class, "id", true, 1L, 1L);
        Pair<List<LdapConfigurationVO>, Integer> rows = new Pair<>(List.of(), 2);
        doReturn(rows).when(dao).searchAndCount(any(), any());

        assertSame(rows, dao.searchConfigurations(null, "example.test", 389, 41L, true, " ex%_\\ ", page));

        ArgumentCaptor<SearchCriteria<LdapConfigurationVO>> conditions = ArgumentCaptor.captor();
        verify(dao).searchAndCount(conditions.capture(), org.mockito.ArgumentMatchers.eq(page));
        var criteria = conditions.getValue();
        assertTrue(criteria.getWhereClause().contains(" LIKE "));
        assertEquals(4, criteria.getValues().size());
        assertTrue(criteria.getValues().stream().anyMatch(value -> "%ex\\%\\_\\\\%".equals(value.second())));
        assertEquals(Long.valueOf(1), page.getOffset());
        assertEquals(Long.valueOf(1), page.getLimit());
    }

    @Test
    public void idIgnoresEveryOtherFilterAndPagination() {
        Filter page = new Filter(LdapConfigurationVO.class, "id", true, 99L, 1L);
        doReturn(new Pair<>(List.of(), 0)).when(dao).searchAndCount(any(), any());
        dao.searchConfigurations(9L, "wrong.example", 1636, 41L, true, "wrong", page);

        ArgumentCaptor<SearchCriteria<LdapConfigurationVO>> conditions = ArgumentCaptor.captor();
        verify(dao).searchAndCount(conditions.capture(), org.mockito.ArgumentMatchers.isNull());
        var criteria = conditions.getValue();
        assertEquals(1, criteria.getValues().size());
        assertEquals(9L, criteria.getValues().get(0).second());
        assertFalse(criteria.getWhereClause().contains(" LIKE "));
    }
}
