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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

/** A live manager renews only the persistent heartbeat while guest calls or promotion can block. */
public final class StorageWriterHeartbeat implements AutoCloseable {
    private final ScheduledFuture<?> future;
    public StorageWriterHeartbeat(StorageServiceOperationVO operation, StorageServiceOperationDao operations,
            ScheduledExecutorService executor, java.util.function.Consumer<RuntimeException> unavailable) {
        final long id = operation.getId();
        final long instanceId = operation.getInstanceId();
        final String uuid = operation.getUuid();
        future = executor.scheduleWithFixedDelay(() -> {
            try {
                operations.touchHeartbeat(id, uuid, instanceId);
            } catch (RuntimeException failure) {
                unavailable.accept(failure);
            }
        }, 20, 20, TimeUnit.SECONDS);
    }
    public void close() { future.cancel(false); }
}
