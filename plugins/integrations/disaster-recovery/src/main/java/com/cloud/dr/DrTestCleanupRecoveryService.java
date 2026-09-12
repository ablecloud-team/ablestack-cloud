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

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Provider;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.commons.lang3.StringUtils;

public class DrTestCleanupRecoveryService extends ManagerBase {
    @Inject private DrTestCleanupRecoveryStore store;
    @Inject private DrPlanDao plans;
    @Inject private DrRunDao runs;
    @Inject private DrTestSessionDao sessions;
    @Inject private Provider<FtctlDrUnifiedActionAdapter> adapter;
    private ScheduledExecutorService ticker;
    private ThreadPoolExecutor workers;
    @Override public boolean start() {
        workers=new ThreadPoolExecutor(2,2,0L,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16),new NamedThreadFactory("DrTestRestore"));
        ticker=Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DrTestRestoreScan"));
        ticker.scheduleWithFixedDelay(new ManagedContextRunnable() {
            @Override protected void runInContext() {
                try {
                    if (!Boolean.TRUE.equals(com.cloud.dr.cluster.DisasterRecoveryClusterService.DisasterRecoveryServiceEnabled.value())) return;
                    for (DrTestCleanupRecoveryStore.Intent intent : store.due()) {
                        workers.execute(new ManagedContextRunnable() {
                            @Override protected void runInContext() { restore(intent); }
                        });
                    }
                } catch (Exception e) { logger.warn("DR test cleanup recovery scan will retry",e); }
            }
        },5,5,TimeUnit.SECONDS);
        return true;
    }
    @Override public boolean stop() {
        if(ticker!=null) ticker.shutdownNow();
        if(workers!=null) workers.shutdownNow();
        return true;
    }
    void restore(DrTestCleanupRecoveryStore.Intent intent) {
        String token=UUID.randomUUID().toString();
        if(!store.claim(intent.testRunId,token)) return;
        String state="PENDING", error=null;
        try {
            DrPlanVO plan=plans.findById(intent.planId);
            DrRunVO cleanup=intent.cleanupRunId==null ? null : runs.findById(intent.cleanupRunId);
            DrRunVO latest=runs.findLatestByPlanId(intent.planId);
            if(plan==null || plan.getRemoved()!=null || StringUtils.equalsIgnoreCase(plan.getAdminState(),"DISABLED") || StringUtils.equalsIgnoreCase(plan.getActiveSide(),"TARGET")
                    || StringUtils.equalsAnyIgnoreCase(plan.getState(),"UNPROTECTED","DISABLED","FAILED_OVER")) {
                state="SUPERSEDED";
            } else if(latest!=null && intent.cleanupRunId!=null && latest.getId()>intent.cleanupRunId
                    && StringUtils.equalsAnyIgnoreCase(latest.getRunType(),"PAUSE_SYNC","RELEASE","FAILOVER")) {
                state="SUPERSEDED";
            } else {
                DrTestSessionVO active=sessions.findActiveByPlanId(plan.getId());
                if(active!=null && active.isCleanupRequired()) return;
                if(latest!=null && latest.getCompleted()==null) return;
                if(cleanup==null) throw new IllegalStateException("Cleanup Run is missing");
                if("RUNNING".equals(intent.desiredState)) {
                    adapter.get().restoreTestCheckpointProtection(plan,cleanup);
                }
                state="RESTORED";
            }
        } catch(Exception e) {
            error=StringUtils.abbreviate(e.getMessage(),1024);
            logger.warn("DR test protection restore will retry for plan "+intent.planId+": "+error);
        } finally {
            store.finish(intent.testRunId,token,state,error);
        }
    }
}
