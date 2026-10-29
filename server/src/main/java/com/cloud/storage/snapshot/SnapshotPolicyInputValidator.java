// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage.snapshot;

import java.util.TimeZone;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.DateUtil.IntervalType;

public final class SnapshotPolicyInputValidator {
    private SnapshotPolicyInputValidator() { }

    public static String validateTimezone(String timezone) {
        if (timezone == null || timezone.isEmpty()) {
            throw new InvalidParameterValueException("timezone must identify a supported time zone.");
        }
        String normalized = TimeZone.getTimeZone(timezone).getID();
        // Java silently returns GMT for unknown IDs and malformed custom offsets.
        if ("GMT".equals(normalized) && !"GMT".equals(timezone)) {
            throw new InvalidParameterValueException("timezone must identify a supported time zone.");
        }
        return normalized;
    }

    public static void validateSchedule(IntervalType interval, String schedule) {
        if (interval == null) {
            throw new InvalidParameterValueException("intervaltype must be HOURLY, DAILY, WEEKLY or MONTHLY.");
        }
        String[] parts = schedule == null ? new String[0] : schedule.split(":", -1);
        int expected = interval == IntervalType.HOURLY ? 1 : interval == IntervalType.DAILY ? 2 : 3;
        String format = interval == IntervalType.HOURLY ? "MM" : interval == IntervalType.DAILY ? "MM:HH" : "MM:HH:DD";
        String dayRange = interval == IntervalType.WEEKLY ? "; day 1-7 (Sunday-Saturday)"
                : interval == IntervalType.MONTHLY ? "; day 1-28" : "";
        String error = "schedule for " + interval + " must use " + format + " with whole numbers: minute 0-59"
                + (expected > 1 ? "; hour 0-23" : "") + dayRange + ".";
        if (parts.length != expected) {
            throw new InvalidParameterValueException(error);
        }
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,2}")) {
                throw new InvalidParameterValueException(error);
            }
            int value = Integer.parseInt(parts[i]);
            int min = i == 2 ? 1 : 0;
            int max = i == 0 ? 59 : i == 1 ? 23 : interval == IntervalType.WEEKLY ? 7 : 28;
            if (value < min || value > max) {
                throw new InvalidParameterValueException(error);
            }
        }
    }

    public static void validateRetention(Integer count, int maximum, IntervalType interval) {
        if (maximum <= 0) {
            throw new InvalidParameterValueException("Scheduled snapshots are disabled for " + interval + ". Contact an administrator.");
        }
        if (count == null || count < 1 || count > maximum) {
            throw new InvalidParameterValueException("maxsnaps must be a whole number between 1 and " + maximum + " for " + interval + ".");
        }
    }

}
