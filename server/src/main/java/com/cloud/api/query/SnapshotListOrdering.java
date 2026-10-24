// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.api.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria.Func;

/** SQL orders and pages identities first; hydration must retain that exact order. */
public final class SnapshotListOrdering {
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("created", "created"), Map.entry("id", "id"), Map.entry("name", "name"),
            Map.entry("state", "status"), Map.entry("volumename", "volumeName"),
            Map.entry("intervaltype", "snapshotType"), Map.entry("virtualsize", "size"),
            Map.entry("account", "accountName"), Map.entry("domain", "domainName"), Map.entry("project", "projectName"));
    private final String key;
    private final boolean ascending;

    public SnapshotListOrdering(String key, String order) {
        this.key = key == null ? "created" : key.toLowerCase(Locale.ROOT);
        String direction = order == null ? "desc" : order.toLowerCase(Locale.ROOT);
        if (!FIELDS.containsKey(this.key) || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new InvalidParameterValueException("Unsupported snapshot sortkey or sortorder");
        }
        ascending = "asc".equals(direction);
    }

    public Filter filter(Long offset, Long limit, boolean unique) {
        Filter filter = new Filter(offset, limit);
        filter.addOrderBy(SnapshotJoinVO.class, FIELDS.get(key), ascending, "snapshot_view");
        if (!"id".equals(key)) {
            filter.addOrderBy(SnapshotJoinVO.class, "id", ascending, "snapshot_view");
        }
        if (!unique) {
            filter.addOrderBy(SnapshotJoinVO.class, "snapshotStorePair", true);
        }
        return filter;
    }

    public void selectPage(SearchBuilder<SnapshotJoinVO> builder, boolean unique) {
        // Select the representative from the filtered rows, never an arbitrary GROUP BY row.
        // Include every ORDER BY expression in SELECT for MySQL DISTINCT/ONLY_FULL_GROUP_BY.
        builder.select("snapshotStorePair", unique ? Func.MIN : Func.DISTINCT, builder.entity().getSnapshotStorePair());
        if (!"id".equals(key)) {
            builder.selectFields(value(builder.entity()));
        }
        // id is inherited, and SearchCriteria terminates projection at an inherited field.
        builder.selectFields(builder.entity().getId());
        if (unique) {
            if (!"id".equals(key)) {
                builder.groupBy(builder.entity().getId(), value(builder.entity()));
            } else {
                builder.groupBy(builder.entity().getId());
            }
        }
    }

    private Object value(SnapshotJoinVO row) {
        switch (key) {
            case "created": return row.getCreated();
            case "name": return row.getName();
            case "state": return row.getStatus();
            case "volumename": return row.getVolumeName();
            case "intervaltype": return row.getSnapshotType();
            case "virtualsize": return row.getSize();
            case "account": return row.getAccountName();
            case "domain": return row.getDomainName();
            case "project": return row.getProjectName();
            default: return row.getId();
        }
    }

    public static List<SnapshotJoinVO> hydrateOrder(List<SnapshotJoinVO> page, List<SnapshotJoinVO> rows) {
        Map<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < page.size(); index++) {
            positions.put(page.get(index).getSnapshotStorePair(), index);
        }
        List<SnapshotJoinVO> ordered = new ArrayList<>(rows);
        ordered.removeIf(row -> !positions.containsKey(row.getSnapshotStorePair()));
        ordered.sort(Comparator.comparingInt(row -> positions.get(row.getSnapshotStorePair())));
        return ordered;
    }
}
