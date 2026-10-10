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
package org.apache.cloudstack.api.command.user.vm;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VmCreationSourceResponse;
import com.cloud.vm.VmCreationSourceService;
import java.util.Arrays;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.junit.Test;

public class VmCreationSourceApiContractTest {
    @Test public void sourceListingIsRegisteredForStandardOwnerRoles() {
        APICommand annotation = ListVirtualMachineCreationSourcesCmd.class.getAnnotation(APICommand.class);
        assertTrue(Arrays.asList(annotation.authorized()).containsAll(Arrays.asList(RoleType.Admin, RoleType.DomainAdmin, RoleType.ResourceAdmin, RoleType.User)));
    }
    @Test public void sourceValidationIsRegisteredForStandardOwnerRoles() {
        APICommand annotation = ValidateVirtualMachineCreationCmd.class.getAnnotation(APICommand.class);
        assertTrue(Arrays.asList(annotation.authorized()).containsAll(Arrays.asList(RoleType.Admin, RoleType.DomainAdmin, RoleType.ResourceAdmin, RoleType.User)));
    }
    @Test public void preflightResponseSupportsApiServerListCommandEnrichment() {
        ValidateVirtualMachineCreationCmd cmd = new ValidateVirtualMachineCreationCmd() {
            @Override public String getId() { return "source-uuid"; }
        };
        cmd.sourceService = mock(VmCreationSourceService.class);
        VmCreationSourceResponse source = new VmCreationSourceResponse();
        source.id = "source-uuid"; source.allowed = true;
        when(cmd.sourceService.validate(cmd)).thenReturn(source);
        cmd.execute();
        // ApiServer.buildAsyncListResponse casts every BaseListCmd response before serialization.
        ListResponse<?> response = (ListResponse<?>) cmd.getResponseObject();
        assertEquals(Integer.valueOf(1), response.getCount());
        assertEquals(source, response.getResponses().get(0));
        assertEquals(cmd.getCommandName(), response.getResponseName());
    }
}
