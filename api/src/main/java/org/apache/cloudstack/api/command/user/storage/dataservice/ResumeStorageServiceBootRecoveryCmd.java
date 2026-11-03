// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.api.command.user.storage.dataservice;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "resumeStorageServiceBootRecovery", responseObject = StorageServiceRuntimeResponse.class,
    description = "Resume pending NFS boot observations without changing desired configuration",
    requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
    authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ResumeStorageServiceBootRecoveryCmd extends BaseAsyncCmd implements UserCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class,
        required = true, description = "Storage Service instance ID")
    private Long instanceId;
    @Parameter(name = "bootid", type = CommandType.STRING, required = true, description = "Observed SystemVM boot UUID")
    private String bootId;
    @Parameter(name = "expecteddesiredhash", type = CommandType.STRING, required = true, description = "Observed desired configuration SHA-256")
    private String expectedDesiredHash;
    @Parameter(name = "protocol", type = CommandType.STRING, description = "NFS only")
    private String protocol;
    public Long getInstanceId() { return instanceId; }
    public String getBootId() { return bootId; }
    public String getExpectedDesiredHash() { return expectedDesiredHash; }
    public String getProtocol() { return protocol == null ? "NFS" : protocol; }
    @Override public long getEntityOwnerId() { return 0; }
    @Override public String getEventType() { return "STORAGE.BOOT.RECOVERY.RESUME"; }
    @Override public String getEventDescription() { return "Resuming pending Storage Service boot observations"; }
    @Override public void execute() {
        StorageServiceRuntimeResponse response = storageService.resumeStorageServiceBootRecovery(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
