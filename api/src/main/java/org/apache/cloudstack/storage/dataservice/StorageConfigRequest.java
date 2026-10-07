// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import org.apache.cloudstack.api.BaseCmd;

public interface StorageConfigRequest {
    BaseCmd getBaseCmd();
    String getConfigAction();
    default Long getInstanceId() { return null; }
    default Long getArtifactId() { return null; }
    default Boolean getIncludeRuntime() { return null; }
    default Integer getRetentionHours() { return null; }
    default String getData() { return null; }
    default String getSha256() { return null; }
    default Integer getChunkIndex() { return null; }
    default Integer getChunkCount() { return null; }
    default String getMapping() { return null; }
    default String getCredentials() { return null; }
    default String getTargetMode() { return null; }
    default Long getTargetInstanceId() { return null; }
    default String getConfirmation() { return null; }
    default String getPlanToken() { return null; }
    default String getDownloadToken() { return null; }
}
