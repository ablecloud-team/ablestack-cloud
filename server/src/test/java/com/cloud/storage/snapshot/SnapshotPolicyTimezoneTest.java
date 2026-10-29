// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.TimeZone;
import java.time.Instant;
import java.util.Date;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.DateUtil;
import com.cloud.utils.DateUtil.IntervalType;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotPolicyTimezoneTest {
    @Test public void unknownAndMalformedIdsAreRejectedInsteadOfGmtFallback() {
        for (String id : new String[] {null, "", " ", "Bad/Timezone", "america/new_york", "GMT+24:00", "GMT+12:60", "UTC+09:00"}) {
            try {
                SnapshotPolicyInputValidator.validateTimezone(id);
                fail("Invalid timezone accepted: " + id);
            } catch (InvalidParameterValueException expected) {
                assertTrue(expected.getMessage().startsWith("timezone"));
            }
        }
    }

    @Test public void everyJavaRegionAndLegacyAliasRemainsCompatible() {
        for (String id : TimeZone.getAvailableIDs()) {
            assertEquals(id, SnapshotPolicyInputValidator.validateTimezone(id));
        }
    }

    @Test public void validCustomOffsetsAreNormalizedWithoutChangingTheirMeaning() {
        assertEquals("GMT", SnapshotPolicyInputValidator.validateTimezone("GMT"));
        assertEquals("GMT+09:00", SnapshotPolicyInputValidator.validateTimezone("GMT+9"));
        assertEquals("GMT-03:30", SnapshotPolicyInputValidator.validateTimezone("GMT-0330"));
        assertEquals("GMT+00:00", SnapshotPolicyInputValidator.validateTimezone("GMT+0"));
    }

    @Test public void springGapUsesTheSchedulersExistingForwardNormalization() {
        assertEquals(Instant.parse("2030-03-10T07:30:00Z"), DateUtil.getNextRunTime(IntervalType.DAILY, "30:2",
                SnapshotPolicyInputValidator.validateTimezone("America/New_York"), Date.from(Instant.parse("2030-03-10T05:00:00Z"))).toInstant());
    }

    @Test public void autumnOverlapUsesTheSchedulersExistingStandardOffset() {
        assertEquals(Instant.parse("2030-11-03T06:30:00Z"), DateUtil.getNextRunTime(IntervalType.DAILY, "30:1",
                SnapshotPolicyInputValidator.validateTimezone("America/New_York"), Date.from(Instant.parse("2030-11-03T04:00:00Z"))).toInstant());
    }
}
