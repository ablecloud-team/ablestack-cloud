// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.DateUtil.IntervalType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SnapshotPolicyInputValidatorTest {
    @Parameterized.Parameters(name = "{0} schedule={1} allowed={2}")
    public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (String s : List.of("0", "00", "59")) cases.add(new Object[] {IntervalType.HOURLY, s, true});
        for (String s : List.of("0:0", "00:00", "59:23")) cases.add(new Object[] {IntervalType.DAILY, s, true});
        for (String s : List.of("0:0:1", "59:23:7", "00:00:01")) cases.add(new Object[] {IntervalType.WEEKLY, s, true});
        for (String s : List.of("0:0:1", "59:23:28", "00:00:01")) cases.add(new Object[] {IntervalType.MONTHLY, s, true});
        for (IntervalType interval : IntervalType.values()) {
            for (String s : new String[] {null, "", " ", "60", "-1", "1.5", "+1", "0:0:1:2", "1e1", "9999999999999999999", "0::1"}) cases.add(new Object[] {interval, s, false});
        }
        for (String s : List.of("60:0", "0:24", "0:-1", "0:1.5", "0:0:", "0:0:1")) cases.add(new Object[] {IntervalType.DAILY, s, false});
        for (String s : List.of("0:0:0", "0:0:8", "0:0:1.5", "0:24:1", "60:0:1", "0:0:")) cases.add(new Object[] {IntervalType.WEEKLY, s, false});
        for (String s : List.of("0:0:0", "0:0:29", "0:0:31", "0:0:-1", "0:0:1.5", "0:24:1", "60:0:1", "0:0:")) cases.add(new Object[] {IntervalType.MONTHLY, s, false});
        cases.add(new Object[] {null, "0", false});
        return cases;
    }
    private final IntervalType interval;
    private final String schedule;
    private final boolean allowed;
    public SnapshotPolicyInputValidatorTest(IntervalType interval, String schedule, boolean allowed) {
        this.interval = interval; this.schedule = schedule; this.allowed = allowed;
    }
    @Test public void strictScheduleContract() {
        try {
            SnapshotPolicyInputValidator.validateSchedule(interval, schedule);
            assertTrue("invalid schedule accepted", allowed);
        } catch (InvalidParameterValueException e) {
            assertFalse(e.getMessage(), allowed);
            assertFalse(e.getMessage().contains("Volume {"));
        }
    }
}
