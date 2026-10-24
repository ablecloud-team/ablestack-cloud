// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.api.query;

import java.util.Locale;
import java.util.Set;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Snapshot;

/** Snapshot kind and recurrence are intersected independently of the source volume. */
public final class SnapshotListFilters {
    private SnapshotListFilters() { }

    public static Object[] types(String kind, String interval) {
        String type = kind == null ? null : kind.toUpperCase(Locale.ROOT);
        String period = interval == null ? null : interval.toUpperCase(Locale.ROOT);
        if (type != null && !Set.of("MANUAL", "RECURRING").contains(type)) {
            throw new InvalidParameterValueException("Unsupported snapshot type " + kind);
        }
        if (period != null && !Set.of("HOURLY", "DAILY", "WEEKLY", "MONTHLY").contains(period)) {
            throw new InvalidParameterValueException("Unsupported snapshot interval type " + interval);
        }
        if ("MANUAL".equals(type) && period != null) {
            throw new InvalidParameterValueException("intervaltype is only valid for recurring snapshots");
        }
        if (period != null) {
            return new Object[]{Snapshot.Type.valueOf(period).ordinal()};
        }
        if ("MANUAL".equals(type)) {
            return new Object[]{Snapshot.Type.MANUAL.ordinal()};
        }
        if ("RECURRING".equals(type)) {
            return new Object[]{Snapshot.Type.HOURLY.ordinal(), Snapshot.Type.DAILY.ordinal(),
                    Snapshot.Type.WEEKLY.ordinal(), Snapshot.Type.MONTHLY.ordinal()};
        }
        return null; // Retain the existing default exclusion of TEMPLATE/GROUP.
    }
}
