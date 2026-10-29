// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.Filter;

/** Fixed SQL expressions only: ordering is applied before LIMIT within the existing ACL query. */
public final class SnapshotPolicyListOrdering {
    private static final String MINUTE = "CAST(SUBSTRING_INDEX(snapshot_policy.schedule, ':', 1) AS UNSIGNED)";
    private static final String HOUR = "CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(snapshot_policy.schedule, ':', 2), ':', -1) AS UNSIGNED)";
    private static final Map<String, List<String>> FIELDS = Map.of(
            "id", List.of("snapshot_policy.id"),
            "intervaltype", List.of("snapshot_policy.interval"),
            "maxsnaps", List.of("snapshot_policy.max_snaps"),
            "timezone", List.of("snapshot_policy.timezone"),
            "volumename", List.of("volumeJoin.name"),
            "schedule", List.of("CASE WHEN snapshot_policy.interval = 0 THEN " + MINUTE + " ELSE " + HOUR + " * 60 + " + MINUTE + " END",
                    "snapshot_policy.interval", "CASE WHEN snapshot_policy.interval IN (2, 3) THEN CAST(SUBSTRING_INDEX(snapshot_policy.schedule, ':', -1) AS UNSIGNED) ELSE 0 END"));

    private SnapshotPolicyListOrdering() { }

    public static Filter filter(String key, String order, Long offset, Long limit) {
        String field = key == null ? "id" : key.toLowerCase(Locale.ROOT);
        String direction = order == null ? "desc" : order.toLowerCase(Locale.ROOT);
        if (!FIELDS.containsKey(field) || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new InvalidParameterValueException("Unsupported snapshot policy sortkey or sortorder");
        }
        String suffix = "asc".equals(direction) ? " ASC" : " DESC";
        String sql = " ORDER BY " + FIELDS.get(field).stream().map(expression -> expression + suffix).collect(Collectors.joining(", "))
                + ("id".equals(field) ? "" : ", snapshot_policy.id" + suffix);
        return new Filter(offset, limit) {
            @Override
            public String getOrderBy() { return sql; }
        };
    }
}
