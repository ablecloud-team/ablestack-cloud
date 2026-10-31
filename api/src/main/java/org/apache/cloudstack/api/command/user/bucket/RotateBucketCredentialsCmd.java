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
package org.apache.cloudstack.api.command.user.bucket;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.BucketCredentialResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.object.Bucket;

@APICommand(name = "rotateBucketCredentials", description = "Explicit account/store S3 key rotation with persisted candidate, old-key grace and idempotent retry. Does not rotate administrator credentials.",
        responseObject = BucketCredentialResponse.class, entityType = {Bucket.class}, httpMethod = "POST",
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = true, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class RotateBucketCredentialsCmd extends GetBucketCredentialsCmd {
    @Parameter(name = "expectedrevision", type = CommandType.LONG, required = true, description = "Expected current credential revision") private Long revision;
    @Parameter(name = "rotationid", type = CommandType.STRING, description = "Required operation UUID for retry/retire") private String operationId;
    @Parameter(name = "action", type = CommandType.STRING, description = "rotate (default), retry or retire") private String action;
    @Parameter(name = "graceperiod", type = CommandType.INTEGER, description = "Old key grace in seconds: 5..86400; default 300") private Integer grace;
    @Override public String getCommandName() { return "rotatebucketcredentialsresponse"; }
    @Override public void execute() {
        requirePost();
        BucketCredentialResponse response = _bucketService.rotateCredentials(getId(), CallContext.current().getCallingAccount(), revision,
                operationId, action == null ? "rotate" : action, grace == null ? 300 : grace);
        response.setResponseName(getCommandName()); setResponseObject(response);
    }
}
