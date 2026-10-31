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

import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.BucketCredentialResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.object.Bucket;

@APICommand(name = "getBucketCredentials", description = "Explicit, audited POST credential lookup. Defaults to masked metadata and never creates or rotates keys.",
        responseObject = BucketCredentialResponse.class, entityType = {Bucket.class}, httpMethod = "POST",
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = true, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class GetBucketCredentialsCmd extends BaseCmd implements UserCmd {
    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = BucketResponse.class, required = true, description = "Bucket ID")
    private Long id;
    @Parameter(name = "reveal", type = CommandType.BOOLEAN, description = "Return key values for this authorized bucket operation. Default false.")
    private Boolean reveal;
    public Long getId() { return id; }
    protected void requirePost() {
        if (getHttpMethod() != HTTPMethod.POST) throw new ServerApiException(ApiErrorCode.BAD_REQUEST, "Use POST for credential operations");
    }
    @Override public long getEntityOwnerId() {
        Bucket bucket = _entityMgr.findById(Bucket.class, getId());
        return bucket == null ? Account.ACCOUNT_ID_SYSTEM : bucket.getAccountId();
    }
    @Override public String getCommandName() { return "getbucketcredentialsresponse"; }
    @Override public void execute() {
        requirePost();
        BucketCredentialResponse response = _bucketService.getCredentials(getId(), CallContext.current().getCallingAccount(), Boolean.TRUE.equals(reveal));
        response.setResponseName(getCommandName()); setResponseObject(response);
    }
}
