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
package org.apache.cloudstack.api.command.admin.storage.dataservice;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "registerStorageServiceRuntimeBundle", responseObject = StorageServiceRuntimeBundleResponse.class,
        description = "Registers a signed Storage Service runtime bundle catalog entry.", since = "4.23.0",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {RoleType.Admin})
public class RegisterStorageServiceRuntimeBundleCmd extends BaseCmd implements AdminCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "version", type = CommandType.STRING, required = true) private String version;
    @Parameter(name = "runtimeabiversion", type = CommandType.STRING, required = true) private String runtimeAbiVersion;
    @Parameter(name = "desiredstateschemaversion", type = CommandType.STRING, required = true) private String desiredStateSchemaVersion;
    @Parameter(name = "serviceimpact", type = CommandType.STRING, required = true) private String serviceImpact;
    @Parameter(name = "artifacturl", type = CommandType.STRING, required = true) private String artifactUrl;
    @Parameter(name = "manifesturl", type = CommandType.STRING, required = true) private String manifestUrl;
    @Parameter(name = "signatureurl", type = CommandType.STRING, required = true) private String signatureUrl;
    @Parameter(name = "artifactsize", type = CommandType.LONG) private Long artifactSize;
    @Parameter(name = "sha256", type = CommandType.STRING, required = true) private String sha256;
    @Parameter(name = "manifestsha256", type = CommandType.STRING, required = true) private String manifestSha256;
    @Parameter(name = "signingkeyid", type = CommandType.STRING, required = true) private String signingKeyId;
    @Parameter(name = "releasechannel", type = CommandType.STRING) private String releaseChannel;
    @Parameter(name = "releasenotes", type = CommandType.STRING) private String releaseNotes;
    public String getReleaseChannel() { return releaseChannel; }
    public String getReleaseNotes() { return releaseNotes; }
    public String getVersion() { return version; }
    public String getRuntimeAbiVersion() { return runtimeAbiVersion; }
    public String getDesiredStateSchemaVersion() { return desiredStateSchemaVersion; }
    public String getServiceImpact() { return serviceImpact; }
    public String getArtifactUrl() { return artifactUrl; }
    public String getManifestUrl() { return manifestUrl; }
    public String getSignatureUrl() { return signatureUrl; }
    public Long getArtifactSize() { return artifactSize; }
    public String getSha256() { return sha256; }
    public String getManifestSha256() { return manifestSha256; }
    public String getSigningKeyId() { return signingKeyId; }
    public long getEntityOwnerId() { return 0; }
    public void execute() { final StorageServiceRuntimeBundleResponse response = storageService.registerStorageServiceRuntimeBundle(this); response.setResponseName(getCommandName()); setResponseObject(response); }
}
