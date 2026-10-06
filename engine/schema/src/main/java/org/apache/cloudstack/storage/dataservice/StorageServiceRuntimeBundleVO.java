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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "storage_service_runtime_bundle")
public class StorageServiceRuntimeBundleVO implements StorageServiceRuntimeBundle {
    public enum State { REGISTERED, VERIFIED, AVAILABLE, DISABLED, DEPRECATED, REVOKED }
    public enum ServiceImpact { NONE, PROTOCOL_RESTART, VM_REBOOT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;
    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "version")
    private String version;
    @Column(name = "runtime_abi_version")
    private String runtimeAbiVersion;
    @Column(name = "desired_state_schema_version")
    private String desiredStateSchemaVersion;
    @Column(name = "service_impact")
    @Enumerated(EnumType.STRING)
    private ServiceImpact serviceImpact;
    @Column(name = "artifact_url")
    private String artifactUrl;
    @Column(name = "manifest_url")
    private String manifestUrl;
    @Column(name = "signature_url")
    private String signatureUrl;
    @Column(name = "artifact_size")
    private Long artifactSize;
    @Column(name = "sha256")
    private String sha256;
    @Column(name = "manifest_sha256")
    private String manifestSha256;
    @Column(name = "signing_key_id")
    private String signingKeyId;
    @Column(name = "state")
    @Enumerated(EnumType.STRING)
    private State state = State.REGISTERED;
    @Column(name = "catalog_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String catalogJson;
    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();
    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    public StorageServiceRuntimeBundleVO() { }

    public StorageServiceRuntimeBundleVO(final String version, final String runtimeAbiVersion,
            final String desiredStateSchemaVersion, final ServiceImpact serviceImpact,
            final String artifactUrl, final String manifestUrl, final String signatureUrl,
            final Long artifactSize, final String sha256, final String manifestSha256,
            final String signingKeyId) {
        this.version = version;
        this.runtimeAbiVersion = runtimeAbiVersion;
        this.desiredStateSchemaVersion = desiredStateSchemaVersion;
        this.serviceImpact = serviceImpact;
        this.artifactUrl = artifactUrl;
        this.manifestUrl = manifestUrl;
        this.signatureUrl = signatureUrl;
        this.artifactSize = artifactSize;
        this.sha256 = sha256;
        this.manifestSha256 = manifestSha256;
        this.signingKeyId = signingKeyId;
    }

    public String getCatalogJson() { return catalogJson; }
    public void setCatalogJson(String value) { catalogJson = value; }

    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public String getVersion() { return version; }
    public String getRuntimeAbiVersion() { return runtimeAbiVersion; }
    public String getDesiredStateSchemaVersion() { return desiredStateSchemaVersion; }
    public ServiceImpact getServiceImpact() { return serviceImpact; }
    public String getArtifactUrl() { return artifactUrl; }
    public String getManifestUrl() { return manifestUrl; }
    public String getSignatureUrl() { return signatureUrl; }
    public Long getArtifactSize() { return artifactSize; }
    public String getSha256() { return sha256; }
    public String getManifestSha256() { return manifestSha256; }
    public String getSigningKeyId() { return signingKeyId; }
    public State getState() { return state; }
    public Date getCreated() { return created; }
    public Date getRemoved() { return removed; }
    public void setState(final State state) { this.state = state; }
}
