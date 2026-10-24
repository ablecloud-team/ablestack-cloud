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
package org.apache.cloudstack.api.command.user.storage.dataservice;
import java.util.UUID;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import com.cloud.exception.InvalidParameterValueException;
/** Optional retry/revision contract shared only by configuration mutations. */
public abstract class BaseStorageServiceConfigurationCmd extends BaseAsyncCmd {
    @Parameter(name = "requestid", type = CommandType.STRING, description = "UUID of one logical configuration attempt; reuse only for observation/retry of that attempt")
    private String requestId;
    @Parameter(name = "expectedrevision", type = CommandType.LONG, description = "Expected committed configuration revision")
    private Long expectedRevision;
    public String getConfigurationRequestId() {
        if (requestId == null) requestId = UUID.randomUUID().toString();
        try { return UUID.fromString(requestId).toString(); }
        catch (IllegalArgumentException e) { throw new InvalidParameterValueException("requestid must be a UUID"); }
    }
    public Long getExpectedRevision() { return expectedRevision; }
}
