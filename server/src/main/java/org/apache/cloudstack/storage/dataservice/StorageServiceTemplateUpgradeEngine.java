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
package org.apache.cloudstack.storage.dataservice;

import java.util.Date;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;

/** The durable phase always precedes an external side effect. Runtime steps must reconcile actual bindings. */
public final class StorageServiceTemplateUpgradeEngine {
    public interface Runtime {
        void preflight();
        void stageRoot();
        void checkpoint();
        void quiesce();
        void swapRoot();
        void bootTarget();
        void restoreIdentity();
        void reconcile();
        void verify();
        void commit();
        void restorePreviousRoot();
        void bootPrevious();
        void reconcilePrevious();
        void verifyPrevious();
        void finished(boolean success);
    }
    private final StorageServiceTemplateUpgradeDao upgrades;
    public StorageServiceTemplateUpgradeEngine(StorageServiceTemplateUpgradeDao upgrades) { this.upgrades=upgrades; }
    public void execute(StorageServiceTemplateUpgradeVO row,Runtime runtime) {
        if (!java.util.Set.of("PLANNED","RUNNING","RECOVERY_REQUIRED").contains(row.getState())) {
            throw new CloudRuntimeException("Template upgrade is not executable in its current state");
        }
        row.setState("RUNNING");if (row.getStarted()==null) row.setStarted(new Date());
        boolean attempted=false;
        try {
            phase(row,"PREFLIGHT",2);runtime.preflight();
            attempted=true;
            phase(row,"STAGING_ROOT",10);runtime.stageRoot();
            phase(row,"SNAPSHOTTING_CONFIG",20);runtime.checkpoint();
            phase(row,"QUIESCING",30);runtime.quiesce();
            phase(row,"SWAPPING_ROOT",40);runtime.swapRoot();
            phase(row,"BOOTING_TARGET",50);runtime.bootTarget();
            phase(row,"RESTORING_IDENTITY",60);runtime.restoreIdentity();
            phase(row,"RECONCILING",70);runtime.reconcile();
            phase(row,"VERIFYING",85);runtime.verify();
            phase(row,"COMMITTING",95);runtime.commit();
            if (!"COMPLETE".equals(row.getState())) {
                row.setState("COMPLETE");row.setCompleted(new Date());phase(row,"COMPLETE",100);
            }
        } catch (RuntimeException failed) {
            row.setState("RUNNING");
            row.setErrorCode("TEMPLATE_UPGRADE_FAILED");row.setErrorMessage(safe(failed));
            if (!attempted) {
                row.setState("BLOCKED");row.setCompleted(new Date());phase(row,"BLOCKED",100);
                throw new CloudRuntimeException("Template upgrade blocked before ROOT staging",failed);
            }
            try {
                phase(row,"ROLLING_BACK_ROOT",85);runtime.restorePreviousRoot();
                phase(row,"BOOTING_PREVIOUS",90);runtime.bootPrevious();
                phase(row,"RECONCILING_PREVIOUS",95);runtime.reconcilePrevious();runtime.verifyPrevious();
                row.setState("ROLLED_BACK");row.setCompleted(new Date());phase(row,"ROLLED_BACK",100);
                runtime.finished(false);
            } catch (RuntimeException rollback) {
                row.setState("RECOVERY_REQUIRED");row.setErrorCode("TEMPLATE_UPGRADE_RECOVERY_REQUIRED");
                row.setErrorMessage(safe(failed)+" | rollback: "+safe(rollback));phase(row,"RECOVERY_REQUIRED",100);
                throw new CloudRuntimeException("Template upgrade and previous ROOT recovery require reconciliation",rollback);
            }
            throw new CloudRuntimeException("Template upgrade failed; previous ROOT recovered",failed);
        }
        // Cleanup cannot invalidate an already committed ROOT/verified configuration.
        runtime.finished(true);
    }
    private void phase(StorageServiceTemplateUpgradeVO row,String phase,int progress) {
        row.setPhase(phase);row.setProgress(progress);row.setHeartbeat(new Date());
        if (!upgrades.update(row.getId(),row)) throw new CloudRuntimeException("Unable to persist template upgrade phase");
    }
    private String safe(RuntimeException failure) {
        String value=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        return value.substring(0,Math.min(2000,value.length()));
    }
}
