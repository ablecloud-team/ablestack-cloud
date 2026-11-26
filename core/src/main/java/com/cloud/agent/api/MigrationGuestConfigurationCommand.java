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
package com.cloud.agent.api;

public class MigrationGuestConfigurationCommand extends Command {
    private int contractVersion = 1;
    private String action,
            vmName,
            vmUuid,
            plan,
            controllerPlan,
            volumeBindings,
            nicBindings,
            lastVerifiedBindingHash;

    public MigrationGuestConfigurationCommand() {}

    public MigrationGuestConfigurationCommand(
            String action,
            String name,
            String uuid,
            String plan,
            String controller,
            String bindings) {
        this.action = action;
        vmName = name;
        vmUuid = uuid;
        this.plan = plan;
        controllerPlan = controller;
        volumeBindings = bindings;
    }

    public int getContractVersion() {
        return contractVersion;
    }

    public String getAction() {
        return action;
    }

    public String getVmName() {
        return vmName;
    }

    public String getVmUuid() {
        return vmUuid;
    }

    public String getPlan() {
        return plan;
    }

    public String getControllerPlan() {
        return controllerPlan;
    }

    public String getVolumeBindings() {
        return volumeBindings;
    }

    public void setNicBindings(String value) {
        nicBindings = value;
    }

    public String getNicBindings() {
        return nicBindings;
    }

    public String getLastVerifiedBindingHash() {
        return lastVerifiedBindingHash;
    }

    public void setLastVerifiedBindingHash(String value) {
        lastVerifiedBindingHash = value;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
