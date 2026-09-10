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

package com.cloud.dr;

import com.cloud.agent.api.FtctlDrActionCommand;

public class DrReprotectAuthoritySpec {
    public static final String CONTRACT_VERSION = FtctlDrActionCommand.REPROTECT_AUTHORITY_CONTRACT_VERSION;

    private String contractVersion = CONTRACT_VERSION;
    private String planUuid;
    private String runUuid;
    private String expectedActiveSide;
    private long authorityGeneration;
    private long authoritySequenceFloor;
    private String cutoverSessionId;
    private long checkpointSequence;
    private long targetVmId;
    private String targetExternalRef;
    private String targetInstanceName;
    private String targetPowerState;
    private boolean targetMaterialized;
    private String targetPromotionState;
    private String bootValidationState;
    private String sourceFenceState;
    private String sourcePowerState;

    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String value) { contractVersion = value; }
    public String getPlanUuid() { return planUuid; }
    public void setPlanUuid(String value) { planUuid = value; }
    public String getRunUuid() { return runUuid; }
    public void setRunUuid(String value) { runUuid = value; }
    public String getExpectedActiveSide() { return expectedActiveSide; }
    public void setExpectedActiveSide(String value) { expectedActiveSide = value; }
    public long getAuthorityGeneration() { return authorityGeneration; }
    public void setAuthorityGeneration(long value) { authorityGeneration = value; }
    public long getAuthoritySequenceFloor() { return authoritySequenceFloor; }
    public void setAuthoritySequenceFloor(long value) { authoritySequenceFloor = value; }
    public String getCutoverSessionId() { return cutoverSessionId; }
    public void setCutoverSessionId(String value) { cutoverSessionId = value; }
    public long getCheckpointSequence() { return checkpointSequence; }
    public void setCheckpointSequence(long value) { checkpointSequence = value; }
    public long getTargetVmId() { return targetVmId; }
    public void setTargetVmId(long value) { targetVmId = value; }
    public String getTargetExternalRef() { return targetExternalRef; }
    public void setTargetExternalRef(String value) { targetExternalRef = value; }
    public String getTargetInstanceName() { return targetInstanceName; }
    public void setTargetInstanceName(String value) { targetInstanceName = value; }
    public String getTargetPowerState() { return targetPowerState; }
    public void setTargetPowerState(String value) { targetPowerState = value; }
    public boolean isTargetMaterialized() { return targetMaterialized; }
    public void setTargetMaterialized(boolean value) { targetMaterialized = value; }
    public String getTargetPromotionState() { return targetPromotionState; }
    public void setTargetPromotionState(String value) { targetPromotionState = value; }
    public String getBootValidationState() { return bootValidationState; }
    public void setBootValidationState(String value) { bootValidationState = value; }
    public String getSourceFenceState() { return sourceFenceState; }
    public void setSourceFenceState(String value) { sourceFenceState = value; }
    public String getSourcePowerState() { return sourcePowerState; }
    public void setSourcePowerState(String value) { sourcePowerState = value; }
}
