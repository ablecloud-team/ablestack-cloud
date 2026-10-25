// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class SnapshotListOrderingTest {
    @Test
    public void defaultAndEveryAllowedFieldHaveBoundedStableSqlOrder() {
        Filter defaults = new SnapshotListOrdering(null, null).filter(20L, 20L, true);
        assertTrue(defaults.getOrderBy().contains("created DESC"));
        assertTrue(defaults.getOrderBy().contains("id DESC"));
        assertEquals(Long.valueOf(20), defaults.getOffset());
        assertEquals(Long.valueOf(20), defaults.getLimit());
        for (String key : new String[]{"created", "id", "name", "state", "volumename", "intervaltype", "virtualsize", "account", "domain", "project"}) {
            for (String order : new String[]{"asc", "desc"}) {
                Filter filter = new SnapshotListOrdering(key.toUpperCase(), order.toUpperCase()).filter(0L, 10L, false);
                assertTrue(filter.getOrderBy().contains("id " + order.toUpperCase()));
                assertTrue(filter.getOrderBy().endsWith("snapshot_store_pair ASC "));
            }
        }
    }

    @Test
    public void projectionsIncludeOrderColumnsAndOnlyUniqueModeGroupsReferences() {
        new GenericDaoBase<SnapshotJoinVO, Long>() { };
        for (boolean unique : new boolean[]{true, false}) {
            for (String key : new String[]{"created", "id", "name", "state", "volumename", "intervaltype", "virtualsize", "account", "domain", "project"}) {
                SearchBuilder<SnapshotJoinVO> builder = new SearchBuilder<>(SnapshotJoinVO.class);
                SnapshotListOrdering ordering = new SnapshotListOrdering(key, "asc");
                ordering.selectPage(builder, unique);
                SearchCriteria<SnapshotJoinVO> criteria = builder.create();
                StringBuilder select = new StringBuilder("SELECT ");
                criteria.getSelect(select, 7);
                assertTrue(select.toString().startsWith(unique ? "SELECT MIN(snapshot_view.snapshot_store_pair)" : "SELECT DISTINCT(snapshot_view.snapshot_store_pair)"));
                for (String expression : ordering.filter(0L, 10L, unique).getOrderBy().replace(" ORDER BY ", "").split(",")) {
                    String column = expression.trim().split(" ")[0];
                    assertTrue(select.toString(), select.toString().contains(column));
                }
                if (unique) assertNotNull(criteria.getGroupBy());
                else assertNull(criteria.getGroupBy());
                assertFalse(select.toString().contains("tag_id"));
            }
        }
    }

    @Test
    public void hydrationKeepsSqlPageOrderTagsAndFilteredRepresentativeOnly() {
        SnapshotJoinVO a = row("1_Image_2");
        SnapshotJoinVO aTag = row("1_Image_2");
        SnapshotJoinVO b = row("2_Primary_1");
        SnapshotJoinVO outside = row("1_Primary_1");
        List<SnapshotJoinVO> rows = SnapshotListOrdering.hydrateOrder(Arrays.asList(b, a), Arrays.asList(a, outside, aTag, b));
        assertEquals(Arrays.asList(b, a, aTag), rows);
        assertTrue(SnapshotListOrdering.hydrateOrder(List.of(), List.of(a)).isEmpty());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsSqlFieldInjection() { new SnapshotListOrdering("name desc; drop table snapshots", "asc"); }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsDerivedPhysicalSize() { new SnapshotListOrdering("physicalsize", "asc"); }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsCopyDependentZone() { new SnapshotListOrdering("zonename", "asc"); }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsUnsupportedDirection() { new SnapshotListOrdering("name", "descending"); }

    private SnapshotJoinVO row(String pair) {
        SnapshotJoinVO row = mock(SnapshotJoinVO.class);
        when(row.getSnapshotStorePair()).thenReturn(pair);
        return row;
    }
}
