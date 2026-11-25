// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import java.util.concurrent.TimeUnit;

import com.cloud.utils.exception.CloudRuntimeException;

/** Bounded admission before a NetBackup request has allocated a backup. */
public final class BackupPreparation {
    public static final long MAX_WAIT_MILLIS = TimeUnit.MINUTES.toMillis(5);
    public static final long POLL_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private BackupPreparation() { }

    public static long deadline(long now, Long requestedDeadline, int hookTimeoutSeconds) {
        if (hookTimeoutSeconds <= 0) {
            throw new CloudRuntimeException("NetBackup Hook timeout must be positive");
        }
        if (requestedDeadline != null && requestedDeadline <= 0) {
            throw new CloudRuntimeException("Backup preparation deadline must be a positive Unix epoch millisecond value");
        }
        long budget = Math.min(MAX_WAIT_MILLIS, TimeUnit.SECONDS.toMillis(hookTimeoutSeconds));
        long deadline = now + budget;
        return requestedDeadline == null ? deadline : Math.min(deadline, requestedDeadline);
    }

    public static void checkDeadline(long deadline, long now) {
        if (deadline > 0 && now >= deadline) {
            throw new CloudRuntimeException("BACKUP_PREPARATION_TIMEOUT: Backup preparation deadline expired; "
                    + "no new backup was admitted and no schedule was consumed");
        }
    }

    /** Only emitted by pre-allocation checks or the initial lifecycle acquisition. */
    public static final class Busy extends CloudRuntimeException {
        private final long vmId;

        public Busy(long vmId) {
            super("BACKUP_PREPARATION_WAITING: Previous backup creation or artifact cleanup is in progress for VM " + vmId);
            this.vmId = vmId;
        }

        public long getVmId() { return vmId; }
    }
}
