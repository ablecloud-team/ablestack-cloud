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
package org.apache.cloudstack.storage.dataservice;

import java.util.Date;
import java.util.List;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

/** Durable orphan recovery runs only under the same instance writer lock as normal changes. */
public final class InterruptedStateChange {
    public interface Runtime {
        void idle();
        void started(StorageServiceOperationVO operation);
        void applyPrevious();
        void verify();
        void verifyCurrent(StorageServiceOperationVO latest);
        void finished(StorageServiceOperationVO operation);
    }
    private final StorageServiceOperationDao operations;
    private final StorageServiceDesiredSnapshot snapshots;
    public InterruptedStateChange(StorageServiceOperationDao operations, StorageServiceDesiredSnapshot snapshots) {
        this.operations = operations;this.snapshots = snapshots;
    }
    public static boolean stale(StorageServiceOperationVO operation, long now) {
        Date heartbeat = operation.getHeartbeat() == null ? operation.getCreated() : operation.getHeartbeat();
        return "RUNNING".equals(operation.getState()) && heartbeat != null && now - heartbeat.getTime() >= 120000;
    }

    public void recover(StorageServiceOperationVO operation, List<StorageServiceOperationVO> history, Runtime runtime, long now) {
        boolean retry = "RECOVERY_REQUIRED".equals(operation.getState()) && operation.getPreviousSnapshotJson() != null;
        if (!stale(operation, now) && !retry) throw new CloudRuntimeException("Storage Service writer is not eligible for interrupted recovery");
        // Idle failure leaves the durable RUNNING record and checkpoint intact for the next bounded retry.
        runtime.idle();
        runtime.started(operation);
        try {
            StorageServiceOperationVO latest = history.stream()
                    .filter(row -> row.getInstanceId() == operation.getInstanceId() && "COMPLETE".equals(row.getState()))
                    .max(java.util.Comparator.comparingLong(StorageServiceOperationVO::getRevision)).orElse(null);
            if (latest != null && latest.getRevision() >= operation.getRevision()) {
                runtime.verifyCurrent(latest);
                complete(operation, "RECONCILED_SUPERSEDED", "CURRENT_CONFIG_VERIFIED",
                        "Interrupted writer superseded by a later verified revision");
                return;
            }
            if (operation.getPreviousSnapshotJson() == null) {
                complete(operation, "BLOCKED", "INTERRUPTED_BEFORE_PREPARE", "Interrupted before a reversible desired-state checkpoint");
                return;
            }
            phase(operation, "RECOVERING_INTERRUPTED_WRITER", 85);
            snapshots.restore(operation.getInstanceId(), operation.getPreviousSnapshotJson());
            runtime.applyPrevious();
            runtime.verify();
            complete(operation, "ROLLED_BACK", "INTERRUPTED_WRITER_ROLLED_BACK", "Interrupted writer restored its previous configuration");
        } catch (RuntimeException failure) {
            complete(operation, "RECOVERY_REQUIRED", "INTERRUPTED_RECOVERY_REQUIRED",
                    "Interrupted writer recovery failed: " + safeMessage(failure));
            throw new CloudRuntimeException("Interrupted Storage Service writer requires recovery: " + operation.getUuid(), failure);
        } finally {
            runtime.finished(operation);
        }
    }
    private void complete(StorageServiceOperationVO operation, String state, String phase, String reason) {
        String original = operation.getDiagnostic();
        operation.setDiagnostic((original == null ? "" : original + " | ") + reason);
        if (operation.getDiagnostic().length() > 2048) operation.setDiagnostic(operation.getDiagnostic().substring(0, 2048));
        operation.setState(state);operation.setCompleted(new Date());
        phase(operation, phase, 100);
    }
    private void phase(StorageServiceOperationVO operation, String phase, int progress) {
        operation.setPhase(phase);operation.setProgress(progress);operation.setHeartbeat(new Date());
        if (!operations.update(operation.getId(), operation)) throw new CloudRuntimeException("Unable to persist interrupted writer recovery");
    }
    private String safeMessage(RuntimeException failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.substring(0, Math.min(1024, value.length()));
    }
}
