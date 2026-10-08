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
        default boolean forwardRecoveryRequired(){return false;}
    }
    private final StorageServiceTemplateUpgradeDao upgrades;
    public StorageServiceTemplateUpgradeEngine(StorageServiceTemplateUpgradeDao upgrades) { this.upgrades=upgrades; }
    private static final java.util.List<String> FORWARD = java.util.List.of("PREFLIGHT","STAGING_ROOT","SNAPSHOTTING_CONFIG","QUIESCING","SWAPPING_ROOT","BOOTING_TARGET","RESTORING_IDENTITY","RECONCILING","VERIFYING","COMMITTING");
    public void execute(StorageServiceTemplateUpgradeVO row,Runtime runtime) {
        if (!java.util.Set.of("PLANNED","RUNNING","RECOVERY_REQUIRED").contains(row.getState())) throw new CloudRuntimeException("Template upgrade is not executable in its current state");
        if(runtime.forwardRecoveryRequired()) {row.setState("RUNNING");try{phase(row,"VERIFYING",85);runtime.verify();phase(row,"COMMITTING",95);runtime.commit();if(!"COMPLETE".equals(row.getState())){row.setState("COMPLETE");row.setCompleted(new Date());phase(row,"COMPLETE",100);}cleanup(row,runtime,true);return;}catch(RuntimeException pending){row.setState("RECOVERY_REQUIRED");row.setErrorCode("COMMITTED_ROOT_FINALIZATION_REQUIRED");row.setErrorMessage(safe(pending));phase(row,"RECOVERY_REQUIRED",95);throw pending;}}
        String resume = row.getPhase();
        if ("RECOVERY_REQUIRED".equals(row.getState()) || resume.startsWith("ROLLING_BACK") || resume.equals("BOOTING_PREVIOUS") || resume.equals("RECONCILING_PREVIOUS")) {
            rollback(row,runtime);return;
        }
        int first = "PLANNED".equals(row.getState()) ? 0 : FORWARD.indexOf(resume);
        if (first < 0) throw new CloudRuntimeException("Unknown durable ROOT phase requires administrator recovery");
        row.setState("RUNNING");if (row.getStarted()==null) row.setStarted(new Date());
        boolean attempted=first>0;
        try {
            if (first<=0) {phase(row,"PREFLIGHT",2);runtime.preflight();}
            attempted=true;
            if (first<=1) {phase(row,"STAGING_ROOT",10);runtime.stageRoot();}
            if (first<=2) {phase(row,"SNAPSHOTTING_CONFIG",20);runtime.checkpoint();}
            if (first<=3) {phase(row,"QUIESCING",30);runtime.quiesce();}
            if (first<=4) {phase(row,"SWAPPING_ROOT",40);runtime.swapRoot();}
            if (first<=5) {phase(row,"BOOTING_TARGET",50);runtime.bootTarget();}
            if (first<=6) {phase(row,"RESTORING_IDENTITY",60);runtime.restoreIdentity();}
            if (first<=7) {phase(row,"RECONCILING",70);runtime.reconcile();}
            if (first<=8) {phase(row,"VERIFYING",85);runtime.verify();}
            if (first<=9) {phase(row,"COMMITTING",95);runtime.commit();}
            if (!"COMPLETE".equals(row.getState())) {row.setState("COMPLETE");row.setCompleted(new Date());phase(row,"COMPLETE",100);}
        } catch (RuntimeException failed) {
            row.setState("RUNNING");row.setErrorCode("TEMPLATE_UPGRADE_FAILED");row.setErrorMessage(safe(failed));
            if (!attempted) {
                row.setState("BLOCKED");row.setCompleted(new Date());phase(row,"BLOCKED",100);cleanup(row,runtime,false);
                throw new CloudRuntimeException("Template upgrade blocked before ROOT staging",failed);
            }
            try {if(runtime.forwardRecoveryRequired()){row.setState("RECOVERY_REQUIRED");row.setErrorCode("COMMITTED_ROOT_FINALIZATION_REQUIRED");phase(row,"RECOVERY_REQUIRED",95);throw new CloudRuntimeException("Committed target ROOT requires forward finalization",failed);}}
            catch(RuntimeException uncertain){row.setState("RECOVERY_REQUIRED");phase(row,"RECOVERY_REQUIRED",95);throw uncertain;}
            rollback(row,runtime);
            throw new CloudRuntimeException("Template upgrade failed; previous ROOT recovered",failed);
        }
        cleanup(row,runtime,true);
    }
    public void rollback(StorageServiceTemplateUpgradeVO row,Runtime runtime) {
        row.setState("RUNNING");
        try {
            phase(row,"ROLLING_BACK_ROOT",85);runtime.restorePreviousRoot();
            phase(row,"BOOTING_PREVIOUS",90);runtime.bootPrevious();
            phase(row,"RECONCILING_PREVIOUS",95);runtime.reconcilePrevious();runtime.verifyPrevious();
            if (!"ROLLED_BACK".equals(row.getState())) {row.setState("ROLLED_BACK");row.setCompleted(new Date());phase(row,"ROLLED_BACK",100);}
        } catch (RuntimeException failed) {
            row.setState("RECOVERY_REQUIRED");row.setErrorCode("TEMPLATE_UPGRADE_RECOVERY_REQUIRED");
            row.setErrorMessage((row.getErrorMessage()==null?"":row.getErrorMessage()+" | ")+"rollback: "+safe(failed));phase(row,"RECOVERY_REQUIRED",100);
            throw new CloudRuntimeException("Previous ROOT recovery requires reconciliation",failed);
        }
        cleanup(row,runtime,false);
    }
    private void cleanup(StorageServiceTemplateUpgradeVO row,Runtime runtime,boolean success) {
        try { runtime.finished(success); }
        catch (RuntimeException pending) {
            if (success) row.setErrorCode("TEMPLATE_UPGRADE_CLEANUP_PENDING");
            row.setErrorMessage((row.getErrorMessage()==null?"":row.getErrorMessage()+" | ")+"cleanup pending: "+safe(pending));
            try { upgrades.update(row.getId(),row); }
            catch (RuntimeException auditPending) {
                org.apache.logging.log4j.LogManager.getLogger(StorageServiceTemplateUpgradeEngine.class)
                        .warn("Template upgrade cleanup audit is pending for {}",row.getUuid());
            }
        }
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
