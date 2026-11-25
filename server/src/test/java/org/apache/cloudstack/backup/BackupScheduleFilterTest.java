// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.cloudstack.api.command.user.backup.ListBackupScheduleCmd;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDaoImpl;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

public class BackupScheduleFilterTest {
    private BackupManagerImpl manager;
    private BackupScheduleDao schedules;
    private ListBackupScheduleCmd cmd;

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        manager = new BackupManagerImpl();
        schedules = Mockito.mock(BackupScheduleDao.class);
        AccountManager accounts = Mockito.mock(AccountManager.class);
        cmd = new ListBackupScheduleCmd();
        ReflectionTestUtils.setField(manager, "backupScheduleDao", schedules);
        ReflectionTestUtils.setField(manager, "accountManager", accounts);
        AccountVO caller = new AccountVO("owner", 11L, null, Account.Type.NORMAL, "caller");
        caller.setId(42L);
        CallContext.register(new UserVO(42L, "user", "unused", "test", "user", "test@example.invalid", "UTC", "user", User.Source.UNKNOWN), caller);
        new BackupScheduleDaoImpl();
        when(schedules.createSearchBuilder()).thenAnswer(invocation -> new SearchBuilder<>(BackupScheduleVO.class));
        when(schedules.createSearchCriteria()).thenAnswer(invocation -> new SearchBuilder<>(BackupScheduleVO.class).create());
        when(schedules.searchAndCount(any(), any(Filter.class))).thenReturn(new Pair<>(List.of(), 0));
        ReflectionTestUtils.setField(cmd, "id", 17L);
        doAnswer(invocation -> {
            SearchBuilder<BackupScheduleVO> builder = invocation.getArgument(0);
            builder.and("owner", builder.entity().getAccountId(), SearchCriteria.Op.EQ);
            return null;
        }).when(accounts).buildACLSearchBuilder(any(), any(), anyBoolean(), any(), any());
        doAnswer(invocation -> {
            SearchCriteria<BackupScheduleVO> criteria = invocation.getArgument(0);
            criteria.setParameters("owner", 42L);
            return null;
        }).when(accounts).buildACLSearchCriteria(any(), any(), anyBoolean(), any(), any());
    }

    @After
    public void teardown() {
        CallContext.unregister();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SearchCriteria<BackupScheduleVO> query(Boolean quiesce) {
        ReflectionTestUtils.setField(cmd, "quiesceVM", quiesce);
        assertTrue(manager.listBackupSchedules(cmd).isEmpty());
        ArgumentCaptor<SearchCriteria> criteria = ArgumentCaptor.forClass(SearchCriteria.class);
        Mockito.verify(schedules).searchAndCount(criteria.capture(), any(Filter.class));
        return criteria.getValue();
    }

    private List<Object> values(SearchCriteria<?> criteria) {
        return criteria.getValues().stream().map(Pair::second).collect(Collectors.toList());
    }

    @Test
    public void enabledFilterKeepsTheOwnerAndScheduleRestrictions() {
        SearchCriteria<?> criteria = query(true);
        String sql = criteria.getWhereClause();
        assertTrue(sql.contains("account_id"));
        assertTrue(sql.contains("quiescevm"));
        assertFalse(sql.contains("IS NULL"));
        assertEquals(List.of(42L, 17L, true), values(criteria));
    }

    @Test
    public void disabledFilterIncludesUnsetSchedulesWithoutWideningTheOwnerScope() {
        SearchCriteria<?> criteria = query(false);
        String sql = criteria.getWhereClause();
        assertTrue(sql.contains("IS NULL"));
        assertTrue(sql.contains(" OR "));
        int depth = 0;
        for (int index = 0; index < sql.length(); index++) {
            if (sql.charAt(index) == '(') depth++;
            if (sql.charAt(index) == ')') depth--;
            if (sql.startsWith(" OR ", index)) assertTrue("NULL matches must remain inside the owner restriction", depth > 0);
        }
        assertEquals(List.of(42L, 17L, false), values(criteria));
    }

    @Test
    public void omittedFilterReturnsBothChoicesWithoutChangingTheExistingQuery() {
        SearchCriteria<?> criteria = query(null);
        assertFalse(criteria.getWhereClause().contains("quiescevm"));
        assertEquals(List.of(42L, 17L), values(criteria));
    }

    @Test
    public void intervalTypeCombinesWithTheQuiesceAndOwnerFilters() {
        for (String interval : List.of("HOURLY", "daily", " WEEKLY ", "monthly")) {
            Mockito.clearInvocations(schedules);
            ReflectionTestUtils.setField(cmd, "intervalType", interval);
            SearchCriteria<?> criteria = query(false);
            assertTrue(criteria.getWhereClause().contains("schedule_type"));
            assertEquals(List.of(42L, 17L, (short) DateUtil.IntervalType.getIntervalType(interval.trim()).ordinal(), false), values(criteria));
        }
    }

    @Test
    public void invalidIntervalTypeIsRejectedBeforeSearching() {
        ReflectionTestUtils.setField(cmd, "intervalType", "YEARLY");
        assertThrows(InvalidParameterValueException.class, () -> manager.listBackupSchedules(cmd));
        Mockito.verify(schedules, Mockito.never()).searchAndCount(any(), any(Filter.class));
    }
}
