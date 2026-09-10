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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

final class DrFailbackDataEvidence {
    private final DrFailbackSessionVO session;
    private final List<String> missingFields;

    private DrFailbackDataEvidence(DrFailbackSessionVO session, List<String> missingFields) {
        this.session = session;
        this.missingFields = missingFields;
    }

    static DrFailbackDataEvidence from(DrFailbackSessionVO session) {
        List<String> missing = new ArrayList<String>();
        if (session == null) {
            missing.add("session");
            return new DrFailbackDataEvidence(null, missing);
        }
        require(session.getReplicationDirection(), "replication_direction", missing);
        require(session.getProviderPair(), "provider_pair", missing);
        if (session.getBaselineGeneration() == null) missing.add("baseline_generation");
        require(session.getBaselineState(), "baseline_state", missing);
        require(session.getTrackerState(), "tracker_state", missing);
        require(session.getWriterState(), "writer_state", missing);
        if (session.getTargetWritten() == null) missing.add("target_written");
        if (session.getWriteVerified() == null) missing.add("write_verified");
        require(session.getGuestCompatibilityState(), "reverse_guest_compatibility_state", missing);
        return new DrFailbackDataEvidence(session, missing);
    }

    private static void require(String value, String field, List<String> missing) {
        if (StringUtils.isBlank(value)) {
            missing.add(field);
        }
    }

    boolean isComplete() {
        return missingFields.isEmpty();
    }

    List<String> getMissingFields() {
        return Collections.unmodifiableList(missingFields);
    }

    DrFailbackSessionVO getSession() {
        return session;
    }
}
