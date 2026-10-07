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
import java.util.UUID;
import java.util.function.Supplier;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

/** Persistent, single-writer changes with a reversible desired-state commit boundary. */
public final class DesiredStateChange {
    public interface Runtime {
        void preflight();
        void verify();
        void applyPrevious();
    }
    private final StorageServiceOperationDao operations;
    private final StorageServiceDesiredSnapshot snapshots;
    public interface WriterLock {
        boolean lock(int seconds);
        void unlock();
        void releaseRef();
    }
    private final java.util.function.LongFunction<WriterLock> locks;
    private final Gson gson = new Gson();

    public DesiredStateChange(StorageServiceOperationDao operations, StorageServiceDesiredSnapshot snapshots) {
        this(operations, snapshots, id -> {
            final GlobalLock lock = GlobalLock.getInternLock("StorageServiceWriter-" + id);
            return new WriterLock() {
                public boolean lock(int seconds) { return lock.lock(seconds); }
                public void unlock() { lock.unlock(); }
                public void releaseRef() { lock.releaseRef(); }
            };
        });
    }

    public DesiredStateChange(StorageServiceOperationDao operations, StorageServiceDesiredSnapshot snapshots,
            java.util.function.LongFunction<WriterLock> locks) {
        this.operations = operations; this.snapshots = snapshots; this.locks = locks;
    }

    public <T> T execute(long instanceId, String action, String clientKey, Long expectedRevision,
            Class<T> responseClass, Supplier<T> change, Runtime runtime) {
        final String token = clientKey == null ? UUID.randomUUID().toString() : clientKey;
        if (!token.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Invalid Storage Service idempotency key");
        final String request = action + ":" + token;
        final WriterLock lock = locks.apply(instanceId);
        try {
            if (!lock.lock(120)) throw new CloudRuntimeException("Storage Service writer is busy; operation remains serialized by the async queue");
            try {
                StorageServiceOperationVO previous = operations.findByRequest(instanceId, request);
                if (previous != null) {
                    if ("COMPLETE".equals(previous.getState())) return gson.fromJson(previous.getResultJson(), responseClass);
                    throw new CloudRuntimeException("Storage Service operation already exists: " + previous.getUuid() + " " + previous.getState());
                }
                long committed = operations.listByInstance(instanceId).stream()
                        .filter(op -> "COMPLETE".equals(op.getState())).mapToLong(StorageServiceOperationVO::getRevision).max().orElse(0);
                if (expectedRevision != null && expectedRevision != committed) throw new CloudRuntimeException("Storage Service configuration revision changed; refresh before retrying");
                StorageServiceOperationVO operation = new StorageServiceOperationVO();
                operation.setInstanceId(instanceId); operation.setAction(action); operation.setRequestKey(request);
                operation.setRevision(committed + 1); operation.setCreatedBy(CallContext.current().getCallingUserId());
                operation.setState("RUNNING"); operation.setPhase("PREFLIGHT");
                operation = operations.persist(operation);
                boolean mutated = false;
                try {
                    runtime.preflight();
                    operation.setPreviousSnapshotJson(snapshots.capture(instanceId));
                    phase(operation, "PREPARED", 15);
                    mutated = true;
                    phase(operation, "APPLYING", 30);
                    T response = change.get();
                    phase(operation, "VERIFYING", 80);
                    runtime.verify();
                    operation.setSnapshotJson(snapshots.capture(instanceId));
                    operation.setResultJson(gson.toJson(response));
                    operation.setState("COMPLETE"); operation.setCompleted(new Date());
                    phase(operation, "COMPLETE", 100);
                    return response;
                } catch (RuntimeException failure) {
                    operation.setDiagnostic(message(failure));
                    if (mutated && failure instanceof com.cloud.exception.InvalidParameterValueException && operation.getPreviousSnapshotJson()!=null) {
                        // A rejected input must not restart healthy protocols when no desired state changed.
                        try { if (operation.getPreviousSnapshotJson().equals(snapshots.capture(instanceId))) mutated=false; }
                        catch (RuntimeException uncertain) { failure.addSuppressed(uncertain); }
                    }
                    if (mutated && operation.getPreviousSnapshotJson() != null) {
                        try {
                            phase(operation, "ROLLING_BACK", 85);
                            snapshots.restore(instanceId, operation.getPreviousSnapshotJson());
                            runtime.applyPrevious();
                            runtime.verify();
                            operation.setState("ROLLED_BACK"); operation.setCompleted(new Date());
                            phase(operation, "ROLLED_BACK", 100);
                        } catch (RuntimeException rollback) {
                            operation.setState("RECOVERY_REQUIRED");
                            operation.setDiagnostic(message(failure) + " | rollback: " + message(rollback));
                            operation.setCompleted(new Date()); phase(operation, "RECOVERY_REQUIRED", 100);
                        }
                    } else {
                        operation.setState("BLOCKED"); operation.setCompleted(new Date()); phase(operation, "BLOCKED", 100);
                    }
                    throw new CloudRuntimeException("Storage Service operation " + operation.getUuid() + " " + operation.getState() + ": " + message(failure), failure);
                }
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    private void phase(StorageServiceOperationVO operation, String phase, int progress) {
        operation.setPhase(phase); operation.setProgress(progress); operation.setHeartbeat(new Date());
        operations.update(operation.getId(), operation);
    }

    private String message(RuntimeException failure) {
        String text = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return text.substring(0, Math.min(2048, text.length()));
    }
}
