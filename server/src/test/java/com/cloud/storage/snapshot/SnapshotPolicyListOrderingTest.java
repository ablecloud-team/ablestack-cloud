// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.Filter;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotPolicyListOrderingTest {
    @Test
    public void defaultRetainsIdDescendingAndPaging() {
        Filter filter = SnapshotPolicyListOrdering.filter(null, null, 20L, 10L);
        assertEquals(" ORDER BY snapshot_policy.id DESC", filter.getOrderBy());
        assertEquals(Long.valueOf(20), filter.getOffset());
        assertEquals(Long.valueOf(10), filter.getLimit());
    }

    @Test
    public void allScalarFieldsHaveDeterministicSqlBeforePaging() {
        Map<String, String> fields = Map.of("id", "snapshot_policy.id", "intervaltype", "snapshot_policy.interval",
                "maxsnaps", "snapshot_policy.max_snaps", "timezone", "snapshot_policy.timezone", "volumename", "volumeJoin.name");
        fields.forEach((key, field) -> {
            for (String order : new String[]{"asc", "desc"}) {
                String suffix = " " + order.toUpperCase(java.util.Locale.ROOT);
                assertEquals(" ORDER BY " + field + suffix + ("id".equals(key) ? "" : ", snapshot_policy.id" + suffix),
                        SnapshotPolicyListOrdering.filter(key.toUpperCase(java.util.Locale.ROOT), order, 40L, 20L).getOrderBy());
            }
        });
    }

    @Test
    public void scheduleUsesNumericHourMinuteThenIntervalAndCalendarDay() {
        String sql = SnapshotPolicyListOrdering.filter("schedule", "asc", null, null).getOrderBy();
        assertTrue(sql.contains("CASE WHEN snapshot_policy.interval = 0 THEN CAST("));
        assertTrue(sql.contains(" * 60 + "));
        assertTrue(sql.contains("END ASC, snapshot_policy.interval ASC, CASE WHEN snapshot_policy.interval IN (2, 3)"));
        assertTrue(sql.endsWith("END ASC, snapshot_policy.id ASC"));
        assertEquals(sql.replace(" ASC", " DESC"), SnapshotPolicyListOrdering.filter("schedule", "desc", 0L, 20L).getOrderBy());
    }

    @Test
    public void invalidKeysAndDirectionsCannotReachSql() {
        for (String key : new String[]{"", "name", "max_snaps", "id desc; DROP TABLE snapshot_policy", "volumeJoin.name", "schedule --", " id"}) {
            assertThrows(InvalidParameterValueException.class, () -> SnapshotPolicyListOrdering.filter(key, "asc", 0L, 20L));
        }
        for (String direction : new String[]{"", "ascending", "asc;--", "desc, id", " desc"}) {
            assertThrows(InvalidParameterValueException.class, () -> SnapshotPolicyListOrdering.filter("id", direction, 0L, 20L));
        }
    }
}
