// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.usage.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.usage.UsageIPAddressVO;
import com.cloud.usage.UsageVO;
import com.cloud.usage.dao.UsageDao;
import com.cloud.usage.dao.UsageIPAddressDao;
import com.cloud.user.AccountVO;

@RunWith(MockitoJUnitRunner.class)
public class IPAddressUsageParserTest {
    @InjectMocks
    private IPAddressUsageParser parser;
    @Mock
    private UsageIPAddressDao ipDao;
    @Mock
    private UsageDao usageDao;
    @Mock
    private AccountVO account;

    private final Date start = new Date(3600000L);
    private final Date end = new Date(14400000L - 1);

    @Before
    public void setUp() {
        when(account.getId()).thenReturn(2L);
        when(account.getAccountId()).thenReturn(2L);
        when(account.getDomainId()).thenReturn(1L);
    }

    private UsageIPAddressVO interval(long ip, long from, long to, boolean hidden) {
        return new UsageIPAddressVO(ip, 2L, 1L, 1L, "10.46.31.18", false, false,
                new Date(from), to < 0 ? null : new Date(to), hidden);
    }

    private List<UsageVO> parse(int count, UsageIPAddressVO... intervals) {
        when(ipDao.getUsageRecords(2L, 1L, start, end)).thenReturn(Arrays.asList(intervals));
        assertTrue(parser.doParsing(account, start, end));
        ArgumentCaptor<UsageVO> records = ArgumentCaptor.forClass(UsageVO.class);
        verify(usageDao, times(count)).persist(records.capture());
        return records.getAllValues();
    }

    private double hours(List<UsageVO> rows, long ip, boolean hidden) {
        return rows.stream().filter(row -> row.getUsageId() == ip && row.isHidden() == hidden)
                .mapToDouble(UsageVO::getRawUsage).sum();
    }

    @Test
    public void includedHiddenIncludedReuseKeepsSeparateExportTotals() {
        List<UsageVO> rows = parse(2,
                interval(429L, 3600000L, 7200000L - 1, false),
                interval(429L, 7200000L, 10800000L - 1, true),
                interval(429L, 10800000L, -1, false));
        assertEquals(2D, hours(rows, 429L, false), 0.000001D);
        assertEquals(1D, hours(rows, 429L, true), 0.000001D);
    }

    @Test
    public void reuseEndingHiddenDoesNotHideEarlierIncludedTime() {
        List<UsageVO> rows = parse(2,
                interval(429L, 3600000L, 7200000L - 1, false),
                interval(429L, 7200000L, -1, true));
        assertEquals(1D, hours(rows, 429L, false), 0.000001D);
        assertEquals(2D, hours(rows, 429L, true), 0.000001D);
    }

    @Test
    public void daoOrderingDoesNotChooseTheExportFlag() {
        List<UsageVO> rows = parse(2,
                interval(429L, 10800000L, -1, false),
                interval(429L, 3600000L, 7200000L - 1, false),
                interval(429L, 7200000L, 10800000L - 1, true));
        assertEquals(2D, hours(rows, 429L, false), 0.000001D);
        assertEquals(1D, hours(rows, 429L, true), 0.000001D);
    }

    @Test
    public void unchangedPolicyStillAggregatesByIpAndClipsToPeriod() {
        List<UsageVO> rows = parse(2,
                interval(429L, 0L, 7200000L - 1, false),
                interval(429L, 7200000L, -1, false),
                interval(464L, 0L, -1, true));
        assertEquals(3D, hours(rows, 429L, false), 0.000001D);
        assertEquals(3D, hours(rows, 464L, true), 0.000001D);
    }
}
