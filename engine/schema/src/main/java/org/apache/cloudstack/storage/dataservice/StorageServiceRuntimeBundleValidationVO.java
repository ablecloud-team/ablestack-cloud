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
import javax.persistence.*;

@Entity
@Table(name = "storage_service_runtime_bundle_validation")
public class StorageServiceRuntimeBundleValidationVO {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "uuid", length = 40)
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "bundle_id")
    private long bundleId;
    @Column(name = "request_id", length = 128)
    private String requestId;
    @Column(name = "observed_revision")
    private long observedRevision;
    @Column(name = "state", length = 32)
    private String state;
    @Column(name = "phase", length = 64)
    private String phase;
    @Column(name = "original_job_id")
    private Long originalJobId;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "heartbeat")
    private Date heartbeat = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "started")
    private Date started = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "completed")
    private Date completed;
    @Column(name = "metadata_digest", length = 64)
    private String metadataDigest;
    @Column(name = "trust_fingerprint", length = 64)
    private String trustFingerprint;
    @Column(name = "verifier_digest", length = 64)
    private String verifierDigest;
    @Column(name = "profile_id", length = 128)
    private String profileId;
    @Column(name = "compatibility", length = 32)
    private String compatibility;
    @Lob @Column(name = "result_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String resultJson;
    @Column(name = "error_code", length = 128)
    private String errorCode;
    @Column(name = "error_message", length = 1024)
    private String errorMessage;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created")
    private Date created = new Date();

    public Long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getBundleId() { return bundleId; }
    public void setBundleId(long value) { bundleId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public long getObservedRevision() { return observedRevision; }
    public void setObservedRevision(long value) { observedRevision = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getPhase() { return phase; }
    public void setPhase(String value) { phase = value; }
    public Long getOriginalJobId() { return originalJobId; }
    public void setOriginalJobId(Long value) { originalJobId = value; }
    public Date getHeartbeat() { return heartbeat; }
    public void setHeartbeat(Date value) { heartbeat = value; }
    public Date getStarted() { return started; }
    public void setStarted(Date value) { started = value; }
    public Date getCompleted() { return completed; }
    public void setCompleted(Date value) { completed = value; }
    public String getMetadataDigest() { return metadataDigest; }
    public void setMetadataDigest(String value) { metadataDigest = value; }
    public String getTrustFingerprint() { return trustFingerprint; }
    public void setTrustFingerprint(String value) { trustFingerprint = value; }
    public String getVerifierDigest() { return verifierDigest; }
    public void setVerifierDigest(String value) { verifierDigest = value; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String value) { profileId = value; }
    public String getCompatibility() { return compatibility; }
    public void setCompatibility(String value) { compatibility = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public Date getCreated() { return created; }
    public void setCreated(Date value) { created = value; }
}
