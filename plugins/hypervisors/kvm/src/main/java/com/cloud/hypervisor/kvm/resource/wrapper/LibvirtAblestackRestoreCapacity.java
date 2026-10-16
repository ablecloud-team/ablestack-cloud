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

import org.apache.cloudstack.backup.BackupRestoreCapacity;

import com.cloud.utils.exception.CloudRuntimeException;

/** Capacity policy captured by one restore worker; shared helpers retain their default outside its scope. */
final class LibvirtAblestackRestoreCapacity implements AutoCloseable {
    private static final ThreadLocal<LibvirtAblestackRestoreCapacity> CURRENT = new ThreadLocal<>();
    private final LibvirtAblestackRestoreCapacity previous;
    private final int bufferPercent;

    private LibvirtAblestackRestoreCapacity(int percent) {
        if (percent < 0 || percent > 100) {
            throw new CloudRuntimeException("Restore primary storage capacity buffer percent must be between 0 and 100");
        }
        bufferPercent = percent;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static LibvirtAblestackRestoreCapacity begin(int percent) {
        return new LibvirtAblestackRestoreCapacity(percent);
    }

    static int bufferPercent() {
        LibvirtAblestackRestoreCapacity capacity = CURRENT.get();
        return capacity == null ? BackupRestoreCapacity.DEFAULT_BUFFER_PERCENT : capacity.bufferPercent;
    }

    static long bufferBytes(long requiredBytes) {
        if (requiredBytes < 0) { throw new CloudRuntimeException("Restore capacity must be non-negative"); }
        return BackupRestoreCapacity.bufferBytes(requiredBytes, bufferPercent());
    }

    @Override
    public void close() {
        if (previous == null) { CURRENT.remove(); }
        else { CURRENT.set(previous); }
    }
}
