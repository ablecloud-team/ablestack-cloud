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
    private String vmName;
    private String operation;
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
        return true;
    }
}
