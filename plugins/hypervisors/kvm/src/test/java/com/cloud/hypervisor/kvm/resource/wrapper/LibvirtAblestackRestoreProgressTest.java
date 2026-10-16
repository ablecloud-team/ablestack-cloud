// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LibvirtAblestackRestoreProgressTest {
    @Test
    public void reportsTheSelectedVolumeThroughoutConversionAndComparison() {
        List<LibvirtAblestackRestoreProgress.Update> updates = new ArrayList<>();
        try (LibvirtAblestackRestoreProgress scope = LibvirtAblestackRestoreProgress.begin(updates::add)) {
            LibvirtAblestackRestoreProgress.volume(1, 2, "/nas/root.root-id.qcow2");
            LibvirtAblestackRestoreProgress.phase("RESTORE_CONVERT", "QCOW2 → QCOW2 · -S 4k");
            LibvirtAblestackRestoreProgress.volume(2, 2, "/nas/datadisk.data-id.qcow2");
            LibvirtAblestackRestoreProgress.phase("RESTORE_COMPARE", "QCOW2 ↔ QCOW2");
        }
        assertEquals("ROOT · QCOW2 → QCOW2 · -S 4k", updates.get(1).details);
        LibvirtAblestackRestoreProgress.Update comparison = updates.get(3);
        assertEquals("RESTORE_COMPARE", comparison.step);
        assertEquals(Integer.valueOf(2), comparison.volumeIndex);
        assertEquals(Integer.valueOf(2), comparison.volumeCount);
        assertEquals("DATA · QCOW2 ↔ QCOW2", comparison.details);
    }

    @Test
    public void closesFailedScopesWithoutLeakingIntoTheNextRestore() {
        List<LibvirtAblestackRestoreProgress.Update> first = new ArrayList<>();
        List<LibvirtAblestackRestoreProgress.Update> second = new ArrayList<>();
        try {
            try (LibvirtAblestackRestoreProgress scope = LibvirtAblestackRestoreProgress.begin(first::add)) {
                LibvirtAblestackRestoreProgress.volume(2, 2, "/nas/datadisk.old.qcow2");
                throw new IllegalStateException("compare failed");
            }
        } catch (IllegalStateException expected) {
            assertEquals("compare failed", expected.getMessage());
        }
        LibvirtAblestackRestoreProgress.phase("CLEANUP_SOURCE", "");
        try (LibvirtAblestackRestoreProgress scope = LibvirtAblestackRestoreProgress.begin(second::add)) {
            LibvirtAblestackRestoreProgress.volume(1, 1, "/nas/root.new.qcow2");
        }
        assertEquals(1, first.size());
        assertEquals(Integer.valueOf(1), second.get(0).volumeCount);
        assertEquals("ROOT", second.get(0).details);
    }

    @Test
    public void helpersOnOtherThreadsCannotUpdateTheCurrentRestore() throws Exception {
        List<LibvirtAblestackRestoreProgress.Update> updates = new ArrayList<>();
        try (LibvirtAblestackRestoreProgress scope = LibvirtAblestackRestoreProgress.begin(updates::add)) {
            LibvirtAblestackRestoreProgress.volume(1, 2, "/nas/root.qcow2");
            Thread otherWorker = new Thread(() -> LibvirtAblestackRestoreProgress.phase("RESTORE_COMPARE", "other job"));
            otherWorker.start();
            otherWorker.join();
            LibvirtAblestackRestoreProgress.phase("RESTORE_CONVERT", "QCOW2");
        }
        assertEquals(2, updates.size());
        assertEquals("RESTORE_CONVERT", updates.get(1).step);
    }
}
