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

/** Versioned RPC. Unknown Agents can never positively acknowledge the contract. */
public class MigrationDiskControllerCommand extends Command {
    private int contractVersion = 1;
    private String action, machine, firmware, vmName, plan, bindings;

    protected MigrationDiskControllerCommand() {}

    public MigrationDiskControllerCommand(
            String action,
            String machine,
            String firmware,
            String vmName,
            String plan,
            String bindings) {
        this.action = action;
        this.machine = machine;
        this.firmware = firmware;
        this.vmName = vmName;
        this.plan = plan;
        this.bindings = bindings;
        setWait(60);
    }

    public int getContractVersion() {
        return contractVersion;
    }

    public String getAction() {
        return action;
    }

    public String getMachine() {
        return machine;
    }

    public String getFirmware() {
        return firmware;
    }

    public String getVmName() {
        return vmName;
    }

    public String getPlan() {
        return plan;
    }

    public String getBindings() {
        return bindings;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
