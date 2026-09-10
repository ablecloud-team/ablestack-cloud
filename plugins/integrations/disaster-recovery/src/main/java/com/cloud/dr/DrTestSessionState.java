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

import org.apache.commons.lang3.StringUtils;

public final class DrTestSessionState {
    public static final String REQUESTED = "REQUESTED";
    public static final String PREPARING = "PREPARING";
    public static final String ARTIFACTS_READY = "ARTIFACTS_READY";
    public static final String CLOUD_VOLUMES_IMPORTING = "CLOUD_VOLUMES_IMPORTING";
    public static final String CLOUD_VM_CREATING = "CLOUD_VM_CREATING";
    public static final String CLOUD_VM_STARTING = "CLOUD_VM_STARTING";
    public static final String CLOUD_VM_VALIDATING = "CLOUD_VM_VALIDATING";
    public static final String ACTIVE = "ACTIVE";
    public static final String FAILED = "FAILED";
    public static final String CLOUD_CLEANUP_RUNNING = "CLOUD_CLEANUP_RUNNING";
    public static final String CLOUD_RESOURCES_REMOVED = "CLOUD_RESOURCES_REMOVED";
    public static final String CLEANED = "CLEANED";
    public static final String CLEANUP_FAILED = "CLEANUP_FAILED";

    private DrTestSessionState() {
    }

    public static String projectEngineState(String currentState, String runtimeState) {
        if (StringUtils.equalsAny(currentState, ACTIVE, CLOUD_VM_VALIDATING, FAILED, CLOUD_CLEANUP_RUNNING,
                CLOUD_RESOURCES_REMOVED, CLEANED, CLEANUP_FAILED)) {
            return currentState;
        }
        if (StringUtils.equalsAny(runtimeState, "ERROR", "FAILED")) {
            return FAILED;
        }
        if (StringUtils.equalsAny(runtimeState, "TEST_ARTIFACTS_READY", ARTIFACTS_READY)) {
            return isBeforeCloudMaterialization(currentState) ? ARTIFACTS_READY : currentState;
        }
        if (StringUtils.equalsAny(runtimeState, "TESTING", "QUEUED", "RUNNING")) {
            return StringUtils.equalsAny(currentState, null, REQUESTED, PREPARING) ? PREPARING : currentState;
        }
        return currentState;
    }

    public static boolean isMaterializationPending(String state) {
        return StringUtils.equalsAny(state, ARTIFACTS_READY, CLOUD_VOLUMES_IMPORTING,
                CLOUD_VM_CREATING, CLOUD_VM_STARTING, CLOUD_VM_VALIDATING);
    }

    public static boolean blocksNewTest(DrTestSessionVO session) {
        if (session == null || session.getRemoved() != null) {
            return false;
        }
        if (session.isCleanupRequired() || StringUtils.equals(session.getState(), CLEANUP_FAILED)) {
            return true;
        }
        if (StringUtils.equals(session.getState(), FAILED)) {
            return session.getTargetVmId() != null;
        }
        return !StringUtils.equals(session.getState(), CLEANED);
    }

    public static boolean canSoftCloseFailedSession(DrTestSessionVO session, boolean terminalCleanupProof) {
        return session != null
                && session.getRemoved() == null
                && StringUtils.equals(session.getState(), FAILED)
                && !session.isCleanupRequired()
                && session.getTargetVmId() == null
                && terminalCleanupProof;
    }

    public static boolean canRecoverArtifactFreePreMaterializationFailure(DrTestSessionVO session) {
        return session != null
                && session.getRemoved() == null
                && StringUtils.equals(session.getState(), FAILED)
                && !session.isCleanupRequired()
                && session.getTargetVmId() == null
                && StringUtils.isBlank(session.getArtifactManifest());
    }

    public static boolean canRestoreSoftClosedPreMaterializationFailure(DrTestSessionVO session) {
        boolean resumableState = session != null
                && (StringUtils.equals(session.getState(), FAILED) && !session.isCleanupRequired()
                || StringUtils.equals(session.getState(), ARTIFACTS_READY));
        return session != null
                && session.getRemoved() != null
                && resumableState
                && session.getTargetVmId() == null
                && StringUtils.isBlank(session.getArtifactManifest());
    }

    public static boolean isTerminalRunFailureWithoutArtifacts(DrTestSessionVO session, DrRunVO run) {
        return session != null
                && session.getRemoved() == null
                && run != null
                && StringUtils.equalsAny(run.getState(), DrConstants.RUN_STATE_FAILED, DrConstants.RUN_STATE_CANCELED)
                && StringUtils.equalsAny(session.getState(), REQUESTED, PREPARING, FAILED)
                && !session.isCleanupRequired()
                && session.getTargetVmId() == null
                && StringUtils.isBlank(session.getArtifactManifest());
    }

    private static boolean isBeforeCloudMaterialization(String state) {
        return StringUtils.equalsAny(state, null, REQUESTED, PREPARING, ARTIFACTS_READY);
    }
}
