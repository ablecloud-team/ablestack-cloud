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

package com.cloud.dr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DrFtctlActionCapabilitySnapshot {
    private final Map<String, String> blockingReasons;
    private final Map<String, Map<String, String>> reasonArgs;

    DrFtctlActionCapabilitySnapshot(Map<String, String> blockingReasons,
            Map<String, Map<String, String>> reasonArgs) {
        this.blockingReasons = blockingReasons == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(blockingReasons));
        this.reasonArgs = reasonArgs == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Map<String, String>>(reasonArgs));
    }

    public String getBlockingReason(String action) {
        return blockingReasons.get(action);
    }

    public Map<String, String> getReasonArgs(String action) {
        Map<String, String> args = reasonArgs.get(action);
        return args == null ? Collections.emptyMap() : args;
    }
}
