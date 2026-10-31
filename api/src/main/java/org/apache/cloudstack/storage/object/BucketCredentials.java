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
package org.apache.cloudstack.storage.object;

import java.util.Date;

/** Provider-verified credentials; never include key values in diagnostics. */
public final class BucketCredentials {
    private final String accessKey;
    private final String secretKey;
    private final String state;
    private final Date checked;
    private long revision;
    private String rotationId;
    private String rotationState;
    private boolean recoveryRequired;
    private Date retireAfter;
    public BucketCredentials withRotation(long revision, String id, String state, boolean recovery, Date retireAfter) {
        this.revision = revision; this.rotationId = id; this.rotationState = state;
        this.recoveryRequired = recovery; this.retireAfter = retireAfter == null ? null : new Date(retireAfter.getTime());
        return this;
    }
    public long getRevision() { return revision; }
    public String getRotationId() { return rotationId; }
    public String getRotationState() { return rotationState; }
    public boolean isRecoveryRequired() { return recoveryRequired; }
    public Date getRetireAfter() { return retireAfter == null ? null : new Date(retireAfter.getTime()); }

    public BucketCredentials(String accessKey, String secretKey, String state, Date checked) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.state = state;
        this.checked = checked == null ? null : new Date(checked.getTime());
    }
    public String getAccessKey() { return accessKey; }
    public String getSecretKey() { return secretKey; }
    public String getState() { return state; }
    public Date getChecked() { return checked == null ? null : new Date(checked.getTime()); }
    @Override public String toString() { return "BucketCredentials[state=" + state + "]"; }
}
