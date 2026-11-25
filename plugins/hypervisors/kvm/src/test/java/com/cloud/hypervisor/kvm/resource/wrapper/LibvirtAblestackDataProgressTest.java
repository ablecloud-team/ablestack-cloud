// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackDataProgressTest {
    @Test
    public void onlyIncreasingNativeProgressExtendsTheIdleDeadline() {
        AtomicLong now = new AtomicLong();
        var guard = new LibvirtAblestackDataProgress.Deadline(10, now::get);
        guard.observe(0);
        now.set(TimeUnit.MILLISECONDS.toNanos(9));
        guard.observe(0);
        guard.observe(-1);
        guard.observe(Double.NaN);
        assertFalse(guard.stalled());
        now.set(TimeUnit.MILLISECONDS.toNanos(10));
        assertTrue(guard.stalled());
        guard.observe(1);
        assertFalse(guard.stalled());
        now.set(TimeUnit.MILLISECONDS.toNanos(19));
        guard.observe(0.5);
        assertFalse(guard.stalled());
        now.set(TimeUnit.MILLISECONDS.toNanos(20));
        assertTrue(guard.stalled());
    }

    @Test
    public void unknownMetricsWarnOnceAndDoNotAuthorizeCancellation() {
        AtomicLong now = new AtomicLong();
        var guard = new LibvirtAblestackDataProgress.Deadline(10, now::get);
        now.set(TimeUnit.SECONDS.toNanos(100));
        assertFalse(guard.stalled());
        assertTrue(guard.warnUnavailable());
        assertFalse(guard.warnUnavailable());
        guard.observe(0);
        assertFalse(guard.warnUnavailable());
        assertFalse(guard.stalled());
    }

    @Test
    public void hungConversionIsTerminatedBeforeTheExecutionBudgetExpires() {
        long started = System.nanoTime();
        var result = LibvirtAblestackDataProgress.execute(
                "/bin/sh -c 'printf \"(1.00/100%%)\\r\"; exec sleep 5'", 3000L, 150L, "qemu", message -> { });
        assertEquals(Integer.valueOf(124), result.first());
        assertTrue(result.second(), result.second().contains("NO_PROGRESS"));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
    }

    @Test
    public void responsiveProcessWithoutNativeMetricsIsAllowedToComplete() {
        var warnings = new ArrayList<String>();
        var result = LibvirtAblestackDataProgress.execute(
                "/bin/sh -c 'printf alive; exec sleep 0.5'", 3000L, 100L, "qemu", warnings::add);
        assertEquals(Integer.valueOf(0), result.first());
        assertEquals("alive", result.second());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("PROGRESS_UNAVAILABLE"));
    }

    @Test
    public void initialZeroDuringPreallocationDoesNotAuthorizeCancellation() {
        var warnings = new ArrayList<String>();
        var result = LibvirtAblestackDataProgress.execute(
                "/bin/sh -c 'printf \"(0.00/100%%)\\r\"; exec sleep 0.5'", 3000L, 100L, "qemu", warnings::add);
        assertEquals(Integer.valueOf(0), result.first());
        assertEquals(1, warnings.size());
    }

    @Test
    public void completedCopyMayFinishItsFlushWithoutBeingCanceled() {
        var warnings = new ArrayList<String>();
        var result = LibvirtAblestackDataProgress.execute(
                "/bin/sh -c 'printf \"(100.00/100%%)\\r\"; exec sleep 0.5'", 3000L, 100L, "qemu", warnings::add);
        assertEquals(Integer.valueOf(0), result.first());
        assertEquals(1, warnings.size());
    }

    @Test
    public void absoluteExecutionBudgetStillAppliesWithoutProgressMetrics() {
        var result = LibvirtAblestackDataProgress.execute("sleep 5", 150L, 3000L, "rbd", message -> { });
        assertEquals(Integer.valueOf(124), result.first());
        assertTrue(result.second(), result.second().contains("execution timed out"));
    }
}
