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
// specific language govening permissions and limitations
// under the License.

package com.cloud.agent.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class StorageServiceHostCommand extends Command {
    private static final Set<String> READ_ONLY_OPERATIONS=Set.of("health","inventory","sessions",
            "operation observe","operation verify","operation resources","operation generation status","operation generation frozen",
            "volume operation status","operation maintenance status","operation root-data inspect",
            "identity capsule capabilities","nfs idmapping preflight","operation writer-idle","operation reservation status");
    private static final Set<String> SCOPED_COORDINATION_OPERATIONS = Set.of("operation reservation renew");
    private String vmName;
    private String operation;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String payload;
    private int timeoutSeconds;
    private Set<String> maskedFields = new HashSet<>();

    protected StorageServiceHostCommand() {
    }

    public StorageServiceHostCommand(String vmName, String operation, String payload, int timeoutSeconds) {
        this.vmName = vmName;
        this.operation = operation;
        this.payload = payload;
        this.timeoutSeconds = timeoutSeconds;
        setWait(timeoutSeconds);
    }

    public StorageServiceHostCommand(String vmName, String operation, String payload, int timeoutSeconds, Set<String> maskedFields) {
        this(vmName, operation, payload, timeoutSeconds);
        this.maskedFields = maskedFields == null ? Collections.emptySet() : Collections.unmodifiableSet(maskedFields);
    }

    public String getVmName() {
        return vmName;
    }

    public String getOperation() {
        return operation;
    }

    public String getPayload() {
        return payload;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Set<String> getMaskedFields() {
        return maskedFields;
    }

    public void setMaskedFields(Set<String> maskedFields) {
        this.maskedFields = maskedFields;
    }

    @Override
    public boolean executeInSequence() {
        // Status must not wait behind a many-minute formatter in the host-wide agent queue.
        // Mutations remain serialized by the instance async queue, management lock and native writer lease.
        // Renewal changes only the exact-scope reservation receipt under its independent native control lock.
        // It must remain available while the same VM formatter holds the ordinary writer queue and FD9.
        return operation==null || !(READ_ONLY_OPERATIONS.contains(operation) || SCOPED_COORDINATION_OPERATIONS.contains(operation));
    }
}
