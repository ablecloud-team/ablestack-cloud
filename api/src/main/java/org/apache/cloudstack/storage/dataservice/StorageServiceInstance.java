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
// specific language govening permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.Date;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.storage.sharedfs.SharedFS;

public interface StorageServiceInstance extends ControlledEntity, Identity, InternalIdentity {
    ConfigKey<Integer> StorageServiceCommandTimeout = new ConfigKey<Integer>("Advanced", Integer.class,
            "storage.service.command.timeout",
            "300",
            "Timeout in seconds for a Storage Service host-agent/QGA command.",
            true,
            SharedFS.SharedFSFeatureEnabled.key());

    String StorageServiceVmType = "storageservicevm";
    String StorageServiceProviderName = "STORAGESERVICEVM";

    enum State {
        Allocated,
        Starting,
        Running,
        Stopping,
        Stopped,
        Destroyed,
        Error
    }

    enum Protocol {
        NFS,
        SMB,
        ISCSI,
        NVME_OF
    }

    enum ResourceState {
        Allocated,
        Creating,
        Ready,
        Updating,
        Disabled,
        Destroyed,
        Error
    }

    enum AccessResourceType {
        FILE_SHARE,
        BLOCK_TARGET
    }

    enum PrincipalType {
        CIDR,
        IP_ADDRESS,
        LOCAL_USER,
        LOCAL_GROUP,
        AD_USER,
        AD_GROUP,
        ISCSI_INITIATOR_IQN,
        NVME_HOST_NQN
    }

    enum Permission {
        READ_ONLY,
        READ_WRITE,
        ADMIN
    }

    enum DomainJoinState {
        NOT_JOINED,
        JOINING,
        JOINED,
        LEAVING,
        ERROR
    }

    String getName();

    String getDescription();

    long getDataCenterId();

    Long getVmId();

    Long getServiceOfferingId();

    String getProvider();

    State getState();

    Date getCreated();
}
