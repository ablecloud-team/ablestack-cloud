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

package org.apache.cloudstack.storage.dataservice;

import java.util.Collections;
import java.util.Set;

public class StorageServiceGuestCommand {
    private final long vmId;
    private final String operation;
    private final String payload;
    private final int timeoutSeconds;
    private final Set<String> maskedFields;

    public StorageServiceGuestCommand(long vmId, String operation, String payload, int timeoutSeconds, Set<String> maskedFields) {
        this.vmId = vmId;
        this.operation = operation;
        this.payload = payload;
        this.timeoutSeconds = timeoutSeconds;
        this.maskedFields = maskedFields == null ? Collections.emptySet() : Collections.unmodifiableSet(maskedFields);
    }

    public long getVmId() {
        return vmId;
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
}
