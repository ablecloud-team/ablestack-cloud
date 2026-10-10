/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.api.command.user.vm;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VmCreationSourceResponse;
import com.cloud.exception.InvalidParameterValueException;

@APICommand(name = "validateVirtualMachineCreation", description = "Revalidates a creation source without allocating resources.",
        authorized = {org.apache.cloudstack.acl.RoleType.Admin, org.apache.cloudstack.acl.RoleType.ResourceAdmin,
                org.apache.cloudstack.acl.RoleType.DomainAdmin, org.apache.cloudstack.acl.RoleType.User},
        responseObject = VmCreationSourceResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ValidateVirtualMachineCreationCmd extends ListVirtualMachineCreationSourcesCmd {
    @Override public void execute() {
        if (getId() == null) { throw new InvalidParameterValueException("id is required for source validation"); }
        VmCreationSourceResponse response = sourceService.validate(this);
        // List commands are enriched by ApiServer as ListResponse, including single-source preflight.
        ListResponse<VmCreationSourceResponse> result = new ListResponse<>();
        result.setResponses(java.util.Collections.singletonList(response), 1);
        result.setResponseName(getCommandName()); setResponseObject(result);
    }
}
