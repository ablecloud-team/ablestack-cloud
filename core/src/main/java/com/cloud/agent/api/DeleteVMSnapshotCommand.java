//
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
//

package com.cloud.agent.api;

import java.util.List;

import org.apache.cloudstack.storage.to.VolumeObjectTO;

public class DeleteVMSnapshotCommand extends VMSnapshotBaseCommand {
    private boolean force;
    private String vmUuid;
    private String expectedCurrent;
    private List<String> knownSnapshotNames;
    private java.util.Map<Long, String> externalSnapshotPaths;

    public boolean isForce() { return force; }
    public String getVmUuid() { return vmUuid; }
    public String getExpectedCurrent() { return expectedCurrent; }
    public List<String> getKnownSnapshotNames() { return knownSnapshotNames; }
    public java.util.Map<Long, String> getExternalSnapshotPaths() { return externalSnapshotPaths; }
    public void setRecovery(String uuid, String current, List<String> names, java.util.Map<Long, String> paths) {
        force = true; vmUuid = uuid; expectedCurrent = current; knownSnapshotNames = names; externalSnapshotPaths = paths;
    }

    public DeleteVMSnapshotCommand(String vmName, VMSnapshotTO snapshot, List<VolumeObjectTO> volumeTOs, String guestOSType) {
        super(vmName, snapshot, volumeTOs, guestOSType);
    }

    public DeleteVMSnapshotCommand(String vmName, VMSnapshotTO snapshot) {
        super(vmName, snapshot, null, null);
    }
}
