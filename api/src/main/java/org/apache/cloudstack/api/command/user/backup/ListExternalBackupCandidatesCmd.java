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

package org.apache.cloudstack.api.command.user.backup;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ExternalBackupCandidatesResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "listExternalBackupCandidates",
        description = "Select Mold schedules or configuration targets for an ABLESTACK NetBackup/Veeam host policy",
        responseObject = ExternalBackupCandidatesResponse.class, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin})
public class ListExternalBackupCandidatesCmd extends BaseCmd {
    @Inject
    private BackupManager backupManager;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class,
            description = "ID of the KVM host executing the external policy")
    private Long hostId;

    @Parameter(name = ApiConstants.HOST_NAME, type = CommandType.STRING,
            description = "Name of the KVM host executing the external policy")
    private String hostName;

    @Parameter(name = ApiConstants.PROVIDER, type = CommandType.STRING, required = true,
            description = "ablestack-netbackup or ablestack-veeam")
    private String provider;

    @Parameter(name = ApiConstants.POLICY_ID, type = CommandType.STRING, required = true,
            description = "NetBackup policy name or Veeam parent job name")
    private String policyId;

    @Parameter(name = "vmschedulebackup", type = CommandType.BOOLEAN,
            description = "Deprecated host selection flag, ignored. Mode is controlled by backup.plugin.netbackup.vm.schedule.enabled " +
                    "or backup.plugin.ablestack-veeam.vm.schedule.enabled for the selected provider.")
    private Boolean vmScheduleBackup;

    @Parameter(name = "vminclude", type = CommandType.STRING, description = "Host VM_INCLUDE filter; required to claim a hostless CONFIG target")
    private String vmInclude;
    @Parameter(name = "vmexclude", type = CommandType.STRING, description = "Host VM_EXCLUDE filter, applied before CONFIG Worker assignment")
    private String vmExclude;
    @Parameter(name = "vmautoexclude", type = CommandType.BOOLEAN, description = "Veeam VM_AUTO_EXCLUDE setting; defaults to true")
    private Boolean vmAutoExclude;

    public Boolean getVmScheduleBackup() { return vmScheduleBackup; }
    public String getVmInclude() { return vmInclude; }
    public String getVmExclude() { return vmExclude; }
    public Boolean getVmAutoExclude() { return vmAutoExclude; }
    public Long getHostId() { return hostId; }
    public String getHostName() { return hostName; }
    public String getProvider() { return provider; }
    public String getPolicyId() { return policyId; }

    @Override
    public void execute() {
        try {
            final ExternalBackupCandidatesResponse response = backupManager.listExternalBackupCandidates(this);
            response.setResponseName(getCommandName());
            response.setObjectName("externalbackupcandidates");
            setResponseObject(response);
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
