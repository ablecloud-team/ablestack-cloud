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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVmGuestAgentCommand;
import com.cloud.agent.api.CheckVmGuestAgentAnswer;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import com.google.gson.JsonObject;

public class DrTestBootValidationService extends ManagerBase {
    @Inject private DrTestBootValidationStore store;
    @Inject private DrTestSessionDao sessionDao;
    @Inject private UserVmDao vmDao;
    @Inject private AgentManager agentManager;
    @Inject private javax.inject.Provider<DrTargetMaterializationService> materializer;
    private ScheduledExecutorService ticker;
    private ThreadPoolExecutor workers;
    @Override public boolean start() {
        workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
                new NamedThreadFactory("DrQgaProbe"));
        ticker = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DrQgaScan"));
        ticker.scheduleWithFixedDelay(new ManagedContextRunnable() {
            @Override protected void runInContext() {
                try {
                    for (Long id : store.due()) {
                        workers.execute(new ManagedContextRunnable() {
                            @Override protected void runInContext() {
                                try { probeOnce(id); wakeCompletion(id); }
                                catch (Exception e) { logger.warn("DR QGA probe will retry", e); }
                            }
                        });
                    }
                } catch (Exception e) { logger.warn("DR QGA due scan will retry", e); }
            }
        }, 5, 5, TimeUnit.SECONDS);
        return true;
    }
    @Override public boolean stop() {
        if (ticker != null) { ticker.shutdownNow(); }
        if (workers != null) { workers.shutdownNow(); }
        return true;
    }
    public boolean transition(DrTestSessionVO session, String state, String boot, String error) {
        return store.transition(session.getId(), state, boot, error);
    }
    public void begin(DrTestSessionVO session) { store.begin(session); }
    public String state(DrTestSessionVO session) { return store.state(session.getId()); }
    public static boolean requiresQga(DrTestSessionVO session) { return "QGA_REQUIRED".equalsIgnoreCase(session.getValidationMode()); }
    public boolean satisfied(DrTestSessionVO session) {
        return requiresQga(session) ? "QGA_VALIDATED".equals(session.getBootValidationState())
                && "QGA_VALIDATED".equals(state(session)) : "POWER_STATE_VALIDATED".equals(session.getBootValidationState());
    }
    void wakeCompletion(long id) {
        DrTestSessionVO latest = sessionDao.findById(id);
        if (latest != null && latest.getRemoved() == null && latest.getCleanupRunId() == null
                && (DrTestSessionState.CLOUD_VM_VALIDATING.equals(latest.getState())
                || DrTestSessionState.ACTIVE.equals(latest.getState()) || DrTestSessionState.FAILED.equals(latest.getState()))) {
            materializer.get().enqueueTestMaterialization(latest.getPlanId(), latest.getRunId(), latest.getDetailsJson());
        }
    }
    void probeOnce(long id) {
        String token = UUID.randomUUID().toString();
        if (store.claim(id, token) != 1) { return; }
        DrTestSessionVO session = sessionDao.findById(id);
        if (session == null || session.getTargetVmId() == null) { return; }
        int remaining = store.remaining(id);
        if (remaining <= 0) { store.finish(id, token, "QGA_TIMEOUT", "{}"); return; }
        UserVmVO vm = vmDao.findById(session.getTargetVmId());
        String result = "PENDING";
        JsonObject evidence = new JsonObject();
        evidence.addProperty("attemptToken", token);
        evidence.addProperty("runId", session.getRunId());
        evidence.addProperty("sessionUuid", session.getUuid());
        if (vm == null || vm.getRemoved() != null || vm.getState() != VirtualMachine.State.Running) {
            result = "QGA_FAILED";
        } else if (vm.getHostId() != null) {
            Long host = vm.getHostId();
            CheckVmGuestAgentCommand command = new CheckVmGuestAgentCommand(vm.getUuid(), vm.getInstanceName(), token, Math.min(5, remaining));
            Answer answer = agentManager.easySend(host, command);
            UserVmVO current = vmDao.findById(vm.getId());
            evidence.addProperty("vmUuid", vm.getUuid());
            evidence.addProperty("observedHostId", host);
            if (current != null && current.getState() == VirtualMachine.State.Running && host.equals(current.getHostId())) {
                if (answer instanceof CheckVmGuestAgentAnswer) {
                    CheckVmGuestAgentAnswer qga = (CheckVmGuestAgentAnswer) answer;
                    evidence.addProperty("status", qga.getStatus());
                    if (token.equals(qga.getToken()) && vm.getUuid().equals(qga.getVmUuid())) {
                        if (qga.getResult() && "RESPONDED".equals(qga.getStatus())) { result = "QGA_VALIDATED"; }
                        else if ("DOMAIN_MISMATCH".equals(qga.getStatus())) { result = "QGA_FAILED"; }
                    }
                } else if (answer != null && answer.getDetails() != null
                        && answer.getDetails().toLowerCase(java.util.Locale.ROOT).contains("unsupported")) {
                    result = "QGA_UNSUPPORTED";
                }
            }
        }
        store.finish(id, token, result, evidence.toString());
    }
}
