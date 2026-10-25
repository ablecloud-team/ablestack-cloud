// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.api.query;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Snapshot;
import org.junit.Test;

public class SnapshotListFiltersTest {
    @Test
    public void recurrenceIntersectsTypeAndDoesNotRequireVolume() {
        for (String period : new String[]{"HOURLY", "DAILY", "WEEKLY", "MONTHLY"}) {
            Object[] expected = {Snapshot.Type.valueOf(period).ordinal()};
            assertArrayEquals(expected, SnapshotListFilters.types(null, period));
            assertArrayEquals(expected, SnapshotListFilters.types("recurring", period.toLowerCase()));
        }
    }

    @Test
    public void defaultsAndManualAndAllRecurringRemainCompatible() {
        assertNull(SnapshotListFilters.types(null, null));
        assertArrayEquals(new Object[]{Snapshot.Type.MANUAL.ordinal()}, SnapshotListFilters.types("MANUAL", null));
        assertArrayEquals(new Object[]{3, 4, 5, 6}, SnapshotListFilters.types("RECURRING", null));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void manualCannotPretendToApplyInterval() { SnapshotListFilters.types("MANUAL", "DAILY"); }

    @Test(expected = InvalidParameterValueException.class)
    public void internalTypeCannotBeUsedAsPublicKind() { SnapshotListFilters.types("INTERNAL", null); }

    @Test(expected = InvalidParameterValueException.class)
    public void manualIsNotARecurringInterval() { SnapshotListFilters.types("RECURRING", "MANUAL"); }

    @Test(expected = InvalidParameterValueException.class)
    public void invalidIntervalFailsEvenWhenRecurringSpecified() { SnapshotListFilters.types("RECURRING", "invalid"); }
}
