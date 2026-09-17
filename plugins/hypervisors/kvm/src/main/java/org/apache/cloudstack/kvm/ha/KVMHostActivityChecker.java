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

package org.apache.cloudstack.kvm.ha;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.manager.Commands;
import com.cloud.agent.api.CheckOnHostAnswer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.AgentControlAnswer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.agent.api.CheckVMActivityOnStoragePoolCommand;
import com.cloud.agent.api.CheckVMActivityOnStoragePoolAnswer;
import com.cloud.agent.api.CheckVMActivityOnStoragePoolAnswer.ActivityState;
import com.cloud.agent.api.DeleteACfileToFencedHostCommand;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.DiskOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.ha.provider.ActivityCheckerInterface;
import org.apache.cloudstack.ha.provider.HACheckerException;
import org.apache.cloudstack.ha.provider.HealthCheckerInterface;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.commons.lang.ArrayUtils;

import javax.inject.Inject;
import java.util.ArrayList;
import org.joda.time.DateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class KVMHostActivityChecker extends AdapterBase implements ActivityCheckerInterface<Host>, HealthCheckerInterface<Host> {

    @Inject
    private ClusterDao clusterDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private PrimaryDataStoreDao storagePool;
    @Inject
    private StorageManager storageManager;
    @Inject
    private ResourceManager resourceManager;

    @Override
    public boolean isActive(Host r, DateTime suspectTime) throws HACheckerException {
        try {
            return isVMActivityOnHost(r, suspectTime);
        } catch (HACheckerException e) {
            //Re-throwing the exception to avoid poluting the 'HACheckerException' already thrown
            throw e;
        } catch (Exception e){
            String message = String.format("Operation timed out, probably the %s is not reachable.", r.toString());
            logger.warn(message, e);
            throw new HACheckerException(message, e);
        }
    }

    @Override
    public boolean isHealthy(Host r) {
        return isAgentActive(r);
    }

    private boolean isAgentActive(Host agent) {
        if (agent.getHypervisorType() != Hypervisor.HypervisorType.KVM && agent.getHypervisorType() != Hypervisor.HypervisorType.LXC) {
            throw new IllegalStateException(String.format("Calling KVM investigator for non KVM Host of type [%s].", agent.getHypervisorType()));
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KVMHAConfig.KvmHAHealthCheckTimeout.valueIn(agent.getClusterId()));
        Status hostStatus = Status.Unknown;
        Status neighbourStatus = Status.Unknown;
        CheckOnHostCommand cmd = null;

        List<Volume> volume_list = new ArrayList<>();
        try {
            HashMap<StoragePool, List<Volume>> poolVolMap = getVolumeUuidOnHost(agent);
            for (StoragePool pool : poolVolMap.keySet()) {
                if (pool != null && pool.getPoolType() == StoragePoolType.RBD) {
                    volume_list.addAll(poolVolMap.get(pool));
                }
            }
            if (!volume_list.isEmpty()) {
                cmd = new CheckOnHostCommand(agent, HighAvailabilityManager.KvmHAFenceHostIfHeartbeatFailsOnStorage.value(), volume_list);
            } else {
                cmd = new CheckOnHostCommand(agent, HighAvailabilityManager.KvmHAFenceHostIfHeartbeatFailsOnStorage.value());
            }
            logger.debug(String.format("Checking %s status...", agent.toString()));
            Answer answer = sendHealthCheck(agent.getId(), cmd, deadline);
            if (answer != null) {
                hostStatus = healthStatus(answer);
                logger.debug(String.format("%s has the status [%s].", agent.toString(), hostStatus));

                if ( hostStatus == Status.Up ){
                    return true;
                }
            }
            else {
                logger.debug(String.format("Setting %s to \"Disconnected\" status.", agent.toString()));
                hostStatus = Status.Disconnected;
            }
        } catch (Exception e) {
            logger.warn(String.format("Failed to send command CheckOnHostCommand to %s.", agent.toString()), e);
        }

        List<HostVO> neighbors = resourceManager.listHostsInClusterByStatus(agent.getClusterId(), Status.Up);
        for (HostVO neighbor : neighbors) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                return false;
            }
            if (neighbor.getId() == agent.getId() || (neighbor.getHypervisorType() != Hypervisor.HypervisorType.KVM && neighbor.getHypervisorType() != Hypervisor.HypervisorType.LXC)) {
                continue;
            }

            cmd = new CheckOnHostCommand(agent, HighAvailabilityManager.KvmHAFenceHostIfHeartbeatFailsOnStorage.value());
            try {
                logger.debug(String.format("Investigating %s via neighbouring %s.", agent.toString(), neighbor.toString()));

                Answer answer = sendHealthCheck(neighbor.getId(), cmd, deadline);
                if (answer != null) {
                    neighbourStatus = healthStatus(answer);

                    logger.debug(String.format("Neighbouring %s returned status [%s] for the investigated %s.", neighbor.toString(), neighbourStatus, agent.toString()));

                    if (neighbourStatus == Status.Up) {
                        break;
                    }
                } else {
                    logger.debug(String.format("Neighbouring %s is Disconnected.", neighbor.toString()));
                }
            } catch (Exception e) {
                logger.warn(String.format("Failed to send command CheckOnHostCommand to %s.", neighbor.toString()), e);
            }
        }

        logger.debug(String.format("%s has the status [%s].", agent.toString(), hostStatus));

        return hostStatus == Status.Up;
    }

    protected Status healthStatus(Answer answer) {
        if (answer instanceof CheckOnHostAnswer && ((CheckOnHostAnswer) answer).isDetermined()) {
            return ((CheckOnHostAnswer) answer).isAlive() ? Status.Up : Status.Down;
        }
        return Status.Unknown;
    }

    protected Answer sendHealthCheck(long hostId, CheckOnHostCommand command, long deadline) throws Exception {
        return sendProbe(hostId, command, deadline);
    }

    protected Answer sendProbe(long hostId, Command command, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new TimeoutException("Health check budget exhausted");
        }
        final int waitSeconds = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                TimeUnit.NANOSECONDS.toSeconds(remaining) + 1));
        command.setWait(waitSeconds);
        final CompletableFuture<Answer> reply = new CompletableFuture<>();
        Listener listener = new Listener() {
            @Override public boolean processAnswers(long id, long sequence, Answer[] answers) {
                reply.complete(answers == null || answers.length == 0 ? null : answers[0]);
                return true;
            }
            @Override public boolean processDisconnect(long id, Status status) { reply.complete(null); return true; }
            @Override public boolean processTimeout(long id, long sequence) { reply.complete(null); return true; }
            @Override public int getTimeout() { return waitSeconds; }
            @Override public boolean isRecurring() { return false; }
            @Override public boolean processCommands(long id, long sequence, Command[] commands) { return false; }
            @Override public AgentControlAnswer processControlCommand(long id, AgentControlCommand command) { return null; }
            @Override public void processHostAdded(long id) { }
            @Override public void processConnect(Host host, StartupCommand command, boolean rebalance) { }
            @Override public void processHostAboutToBeRemoved(long id) { }
            @Override public void processHostRemoved(long id, long clusterId) { }
        };
        try {
            // The synchronous AgentManager API may wait twice and override its timeout.
            agentMgr.send(hostId, new Commands(command), listener);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Health check budget exhausted");
            }
            Answer answer = reply.get(remaining, TimeUnit.NANOSECONDS);
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                throw new TimeoutException("Health observation arrived after its deadline");
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            reply.cancel(false);
        }
    }

    private boolean isVMActivityOnHost(Host agent, DateTime suspectTime) throws HACheckerException {
        if (agent.getHypervisorType() != Hypervisor.HypervisorType.KVM && agent.getHypervisorType() != Hypervisor.HypervisorType.LXC) {
            throw new IllegalStateException(String.format("Calling KVM investigator for non KVM Host of type [%s].", agent.getHypervisorType()));
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KVMHAConfig.KvmHAActivityCheckTimeout.valueIn(agent.getClusterId()));
        boolean checkedPool = false;
        HACheckerException unknown = null;
        HashMap<StoragePool, List<Volume>> poolVolMap = getVolumeUuidOnHost(agent);
        for (StoragePool pool : poolVolMap.keySet()) {
            if (!isStoragePoolHeartbeatEnabled(pool)) {
                continue;
            }
            checkedPool = true;
            try {
                if (verifyActivityOfStorageOnHost(poolVolMap, pool, agent, suspectTime, true, deadline)) {
                    return true;
                }
            } catch (HACheckerException e) {
                unknown = e;
            } catch (RuntimeException e) {
                unknown = new HACheckerException("Unable to verify storage activity", e);
            }
        }
        if (unknown != null) {
            throw unknown;
        }
        if (!checkedPool) {
            throw new HACheckerException("No storage activity observation is available for host " + agent.getId(), null);
        }
        // Only explicit DEAD on every applicable pool establishes no activity.
        return false;
    }

    protected boolean isStoragePoolHeartbeatEnabled(StoragePool pool) {
        if (pool == null) {
            return false;
        }
        return Boolean.TRUE.equals(HighAvailabilityManager.KvmHACheckOnStorage.valueIn(pool.getId()));
    }

    public boolean hasStoragePoolHeartbeatEnabled(Host agent) {
        HashMap<StoragePool, List<Volume>> poolVolMap = getVolumeUuidOnHost(agent);
        for (StoragePool pool : poolVolMap.keySet()) {
            if (isStoragePoolHeartbeatEnabled(pool)) {
                return true;
            }
        }
        logger.debug("Host {} is not eligible for KVM HA because no storage pool has {} enabled.", agent, HighAvailabilityManager.KvmHACheckOnStorage.key());
        return false;
    }

    protected boolean verifyActivityOfStorageOnHost(HashMap<StoragePool, List<Volume>> poolVolMap, StoragePool pool, Host agent, DateTime suspectTime, boolean activityStatus) throws HACheckerException, IllegalStateException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KVMHAConfig.KvmHAActivityCheckTimeout.valueIn(agent.getClusterId()));
        return verifyActivityOfStorageOnHost(poolVolMap, pool, agent, suspectTime, activityStatus, deadline);
    }

    protected boolean verifyActivityOfStorageOnHost(HashMap<StoragePool, List<Volume>> poolVolMap, StoragePool pool, Host agent,
            DateTime suspectTime, boolean activityStatus, long deadline) throws HACheckerException {
        List<Long> connected = storageManager.getUpHostsInPool(pool.getId());
        List<Long> witnesses = new ArrayList<>();
        for (long neighbour : getNeighbors(agent)) {
            if (neighbour != agent.getId() && connected != null && connected.contains(neighbour)) {
                witnesses.add(neighbour);
            }
        }
        List<Volume> activityVolumes = getActivityVolumes(pool, poolVolMap.get(pool));
        Exception lastError = null;
        for (int index = 0; index < witnesses.size(); index++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                break;
            }
            long witnessDeadline = System.nanoTime() + remaining / (witnesses.size() - index);
            long timeoutSeconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(witnessDeadline - System.nanoTime()));
            CheckVMActivityOnStoragePoolCommand cmd = new CheckVMActivityOnStoragePoolCommand(agent, pool, activityVolumes,
                    suspectTime, timeoutSeconds);
            try {
                Answer answer = sendProbe(witnesses.get(index), cmd, Math.min(deadline, witnessDeadline));
                if (answer instanceof CheckVMActivityOnStoragePoolAnswer) {
                    ActivityState state = ((CheckVMActivityOnStoragePoolAnswer) answer).getActivityState();
                    if (state != ActivityState.UNKNOWN) {
                        return state == ActivityState.ALIVE;
                    }
                }
            } catch (Exception e) {
                lastError = e;
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        throw new HACheckerException("No explicit VM activity observation from an available storage-connected witness for host "
                + agent.getId() + " on storage " + pool.getId(), lastError);
    }

    protected List<Volume> getActivityVolumes(StoragePool pool, List<Volume> volumes) {
        if (pool.getPoolType() != StoragePoolType.RBD) {
            return volumes;
        }
        // Filter only the Activity command. Keep the pool and the original list
        // for heartbeat checks, HA eligibility and post-fencing cleanup.
        List<Volume> activityVolumes = new ArrayList<>();
        for (Volume volume : volumes) {
            if (isSharedActivityVolume(pool, volume)) {
                logger.debug("Excluding shared RBD volume {} (image {}, pool {}) from VM Activity checks",
                        volume.getId(), volume.getPath(), pool.getId());
            } else {
                activityVolumes.add(volume);
            }
        }
        // An empty list still reaches the agent: retain its existing HB-based verdict.
        return activityVolumes;
    }

    private boolean isSharedActivityVolume(StoragePool pool, Volume volume) {
        if (volume.getVolumeType() == Volume.Type.DATADISK && volume.getDiskOfferingId() != null) {
            DiskOffering offering = diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
            if (offering != null && offering.getShareable()) {
                return true;
            }
        }
        if (volume.getPath() == null || volume.getPath().isEmpty()) {
            return false;
        }
        // Shared images can have separate volume records for different VMs.
        // Detached or deleted references alone do not establish shared usage.
        for (VolumeVO reference : volumeDao.findBySharedVolume(pool.getId(), volume.getPath())) {
            if (reference.getId() != volume.getId() && reference.getRemoved() == null && reference.getInstanceId() != null
                    && !Objects.equals(reference.getInstanceId(), volume.getInstanceId())
                    && reference.getState() != Volume.State.Destroy && reference.getState() != Volume.State.Destroying
                    && reference.getState() != Volume.State.Expunging && reference.getState() != Volume.State.Expunged) {
                return true;
            }
        }
        return false;
    }

    protected HashMap<StoragePool, List<Volume>> getVolumeUuidOnHost(Host agent) {
        List<VMInstanceVO> vm_list = vmInstanceDao.listByHostId(agent.getId());
        List<VolumeVO> volume_list = new ArrayList<VolumeVO>();
        for (VirtualMachine vm : vm_list) {
            logger.debug("Retrieving volumes of VM [{}]...", vm);
            List<VolumeVO> vm_volume_list = volumeDao.findByInstance(vm.getId());
            volume_list.addAll(vm_volume_list);
        }

        HashMap<StoragePool, List<Volume>> poolVolMap = new HashMap<StoragePool, List<Volume>>();
        for (Volume vol : volume_list) {
            StoragePool sp = storagePool.findById(vol.getPoolId());
            logger.debug("Retrieving storage pool [{}] of volume [{}]...", sp, vol);
            if (!poolVolMap.containsKey(sp)) {
                List<Volume> list = new ArrayList<Volume>();
                list.add(vol);

                poolVolMap.put(sp, list);
            } else {
                poolVolMap.get(sp).add(vol);
            }
        }
        return poolVolMap;
    }

    public long[] getNeighbors(Host agent) {
        List<Long> neighbors = new ArrayList<Long>();
        List<HostVO> cluster_hosts = resourceManager.listHostsInClusterByStatus(agent.getClusterId(), Status.Up);
        logger.debug("Retrieving all \"Up\" hosts from cluster [{}]...", clusterDao.findById(agent.getClusterId()));
        for (HostVO host : cluster_hosts) {
            if (host.getId() == agent.getId() || (host.getHypervisorType() != Hypervisor.HypervisorType.KVM && host.getHypervisorType() != Hypervisor.HypervisorType.LXC)) {
                continue;
            }
            neighbors.add(host.getId());
        }
        return ArrayUtils.toPrimitive(neighbors.toArray(new Long[neighbors.size()]));
    }

    public void deleteACfileToFencedHost(Host agent) throws HACheckerException, StorageUnavailableException {
        if (agent.getHypervisorType() != Hypervisor.HypervisorType.KVM && agent.getHypervisorType() != Hypervisor.HypervisorType.LXC) {
            throw new IllegalStateException(String.format("Calling KVM investigator for non KVM Host of type [%s].", agent.getHypervisorType()));
        }
        HashMap<StoragePool, List<Volume>> poolVolMap = getVolumeUuidOnHost(agent);
        for (StoragePool pool : poolVolMap.keySet()) {
            final DeleteACfileToFencedHostCommand cmd = new DeleteACfileToFencedHostCommand(agent, pool);
            storageManager.sendToPool(pool, getNeighbors(agent), cmd);
        }
    }
}
