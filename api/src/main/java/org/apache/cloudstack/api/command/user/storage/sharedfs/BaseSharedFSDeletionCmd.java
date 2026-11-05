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
package org.apache.cloudstack.api.command.user.storage.sharedfs;
import java.util.List;
import java.util.UUID;
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.command.user.UserCmd;
/** Additive deletion approval contract. Missing policy always preserves DATA. */
public abstract class BaseSharedFSDeletionCmd extends BaseAsyncCmd implements UserCmd {
    @Parameter(name="volumepolicy",type=CommandType.STRING,description="PRESERVE (default), or DELETE_SELECTED.")
    private String volumePolicy;
    @Parameter(name="deletevolumeids",type=CommandType.LIST,collectionType=CommandType.UUID,entityType=VolumeResponse.class,description="Explicit DATA volumes to delete.")
    private List<Long> deleteVolumeIds;
    @Parameter(name="requestid",type=CommandType.STRING,length=64,description="Idempotent deletion request key.")
    private String requestId;
    @Parameter(name="planfingerprint",type=CommandType.STRING,length=64,description="Fingerprint returned by deletion impact.")
    private String planFingerprint;
    @Parameter(name="confirmresource",type=CommandType.STRING,description="Exact shared filesystem name or UUID.")
    private String confirmResource;
    @Parameter(name="confirmdataloss",type=CommandType.BOOLEAN,description="Confirm loss of explicitly selected DATA only.")
    private Boolean confirmDataLoss;
    public abstract Long getId();
    public String getVolumePolicy(){return volumePolicy==null?"PRESERVE":volumePolicy;}
    public List<Long> getDeleteVolumeIds(){return deleteVolumeIds==null?java.util.Collections.emptyList():deleteVolumeIds;}
    public String getDeletionRequestId(){if(requestId==null)requestId=UUID.randomUUID().toString();return requestId;}
    public String getPlanFingerprint(){return planFingerprint;}
    public String getConfirmResource(){return confirmResource;}
    public boolean isConfirmDataLoss(){return Boolean.TRUE.equals(confirmDataLoss);}
}
