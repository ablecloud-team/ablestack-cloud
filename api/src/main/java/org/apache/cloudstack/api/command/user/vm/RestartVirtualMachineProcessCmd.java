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
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.VmProcessActionResponse;
@APICommand(name="restartVirtualMachineProcess",description="Restart only an approved VM-scoped fixed profile; never replays a collected command line",responseObject=VmProcessActionResponse.class,requestHasSensitiveInfo=false,responseHasSensitiveInfo=true,since="4.23.0",authorized={RoleType.Admin})
public class RestartVirtualMachineProcessCmd extends BaseVmProcessActionCmd {
    @Parameter(name="profileid",type=CommandType.STRING,required=true,description="Approved profile UUID") private String id;
    @Parameter(name="profileversion",type=CommandType.INTEGER,required=true,description="Approved immutable version") private Integer version;
    @Override protected String action(){return "process.restart";}
    @Override protected String profileId(){return id;}
    @Override protected Integer profileVersion(){return version;}
}
