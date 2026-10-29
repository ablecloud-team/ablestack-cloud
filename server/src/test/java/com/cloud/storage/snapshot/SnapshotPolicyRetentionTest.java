// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.DateUtil.IntervalType;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotPolicyRetentionTest {
    @Test public void retentionUsesSuppliedRuntimeMaximum() {
        for (int maximum : new int[] {1, 3, 11}) {
            SnapshotPolicyInputValidator.validateRetention(1, maximum, IntervalType.HOURLY);
            SnapshotPolicyInputValidator.validateRetention(maximum, maximum, IntervalType.HOURLY);
            try {
                SnapshotPolicyInputValidator.validateRetention(maximum + 1, maximum, IntervalType.HOURLY);
                fail("above applied maximum");
            } catch (InvalidParameterValueException expected) { assertTrue(expected.getMessage().contains("1 and " + maximum)); }
        }
    }
    @Test public void zeroNegativeAndMissingRetentionAreRejected() {
        for (Integer count : new Integer[] {null, 0, -1}) {
            try { SnapshotPolicyInputValidator.validateRetention(count, 8, IntervalType.DAILY); fail("invalid retention"); }
            catch (InvalidParameterValueException expected) { assertTrue(expected.getMessage().contains("whole number")); }
        }
    }
    @Test public void disabledIntervalDoesNotAllowAnyRetention() {
        for (int maximum : new int[] {0, -1}) {
            try { SnapshotPolicyInputValidator.validateRetention(1, maximum, IntervalType.MONTHLY); fail("disabled interval"); }
            catch (InvalidParameterValueException expected) { assertTrue(expected.getMessage().contains("disabled")); }
        }
    }
}
