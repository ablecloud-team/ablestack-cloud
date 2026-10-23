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
package com.cloud.storage;

import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import org.springframework.stereotype.Component;
import org.apache.cloudstack.api.command.admin.storage.GetVolumeAllocationCapabilitiesCmd;
import org.apache.cloudstack.api.response.VolumeAllocationCapabilityResponse;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumeAllocationCapabilitiesCommand;
import com.cloud.agent.api.GetVolumeAllocationCapabilitiesAnswer;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.component.ManagerBase;

@Component
public class VolumeAllocationManagerImpl extends ManagerBase implements VolumeAllocationService {
    @Inject private AgentManager agentManager;
    @Inject private HostDao hostDao;
    @Inject private PrimaryDataStoreDao poolDao;
    @Inject private StoragePoolHostDao poolHostDao;

    @Override public List<Class<?>> getCommands() { return List.of(GetVolumeAllocationCapabilitiesCmd.class); }

    @Override public VolumeAllocationCapabilityResponse getCapabilities(GetVolumeAllocationCapabilitiesCmd cmd) {
        String format = cmd.getAllocationFormat().toLowerCase(Locale.ROOT);
        String operation = cmd.getOperation().toLowerCase(Locale.ROOT);
        if (!List.of("qcow2", "raw").contains(format) || !VolumeAllocationPolicy.OPERATIONS.contains(operation)) {
            throw new InvalidParameterValueException("Invalid allocationformat or operation");
        }
        StoragePoolVO pool = poolDao.findById(cmd.getStorageId());
        HostVO host = hostDao.findById(cmd.getHostId());
        if (pool == null || host == null || poolHostDao.findByPoolHost(pool.getId(), host.getId()) == null) {
            throw new InvalidParameterValueException("Host must be connected to the specified primary storage pool");
        }
        return capabilities(pool, host, format, operation, cmd.getAllocationPolicy());
    }

    private VolumeAllocationCapabilityResponse capabilities(StoragePoolVO pool, HostVO host, String format, String operation, String requestedPolicy) {
        if (requestedPolicy != null) {
            String policy = VolumeAllocationPolicy.request(requestedPolicy, format, null).get(VolumeAllocationPolicy.POLICY);
            if (pool.getPoolType() == Storage.StoragePoolType.RBD &&
                    (!"raw".equals(format) || "falloc".equals(policy) ||
                    ("full".equals(policy) && !List.of("create", "copy").contains(operation)))) {
                throw new InvalidParameterValueException("RBD supports raw off and qualified full create/copy only; lazy thick/falloc and Full lifecycle operations are unsupported");
            }
        }
        VolumeAllocationCapabilityResponse response = new VolumeAllocationCapabilityResponse();
        response.setScope(pool.getUuid(), host.getUuid(), format, operation);
        if (pool.getPoolType() != Storage.StoragePoolType.RBD && pool.getPoolType() != Storage.StoragePoolType.SharedMountPoint) {
            response.setReason("UNSUPPORTED_STORAGE_BACKEND");
            return response;
        }
        if (host.getStatus() != Status.Up || host.getHypervisorType() != HypervisorType.KVM) {
            response.setReason("HOST_NOT_AVAILABLE");
            return response;
        }
        if (!List.of("create", "copy").contains(operation) ||
                (pool.getPoolType() == Storage.StoragePoolType.RBD && !"raw".equals(format))) {
            response.setReason("ALLOCATION_OPERATION_UNSUPPORTED");
            return response;
        }
        try {
            hostDao.loadDetails(host);
        } catch (Exception e) {
            response.setReason("CAPABILITY_QUERY_FAILED");
            return response;
        }
        if (!"1".equals(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION))) {
            response.setReason("AGENT_CAPABILITY_NOT_ADVERTISED");
            return response;
        }
        GetVolumeAllocationCapabilitiesCommand command = new GetVolumeAllocationCapabilitiesCommand(pool.getUuid(), format, operation);
        try {
            Answer answer = agentManager.send(host.getId(), command);
            if (!(answer instanceof GetVolumeAllocationCapabilitiesAnswer) || !answer.getResult()) {
                response.setReason("CAPABILITY_QUERY_FAILED");
                return response;
            }
            GetVolumeAllocationCapabilitiesAnswer capability = (GetVolumeAllocationCapabilitiesAnswer) answer;
            if (capability.getContractVersion() != 1 || !pool.getUuid().equals(capability.getPoolUuid()) ||
                    !format.equals(capability.getFormat()) || !operation.equals(capability.getOperation()) ||
                    (pool.getPoolType() == Storage.StoragePoolType.RBD && !"RBD".equals(capability.getBackend())) ||
                    (pool.getPoolType() != Storage.StoragePoolType.RBD && !"GFS2".equals(capability.getBackend()))) {
                response.setReason("INVALID_AGENT_CAPABILITY");
                return response;
            }
            VolumeAllocationPolicy.validateCapability(capability.getBackend(), format, operation, capability.getMethods());
            response.setAgentPolicies(capability.getBackend(), capability.getMethods());
            boolean implemented = ("GFS2".equals(capability.getBackend()) ||
                    ("RBD".equals(capability.getBackend()) && "raw".equals(format))) &&
                    List.of("create", "copy").contains(operation) &&
                    (requestedPolicy == null || capability.getMethods().containsKey(requestedPolicy.toLowerCase(Locale.ROOT)));
            response.setSupported(implemented);
            response.setReason(implemented ? null : "ALLOCATION_EXECUTION_NOT_IMPLEMENTED");
        } catch (InvalidParameterValueException e) {
            response.setReason("INVALID_AGENT_CAPABILITY");
        } catch (Exception e) {
            logger.debug("Allocation capability query failed for host {} and pool {}", host.getUuid(), pool.getUuid(), e);
            response.setReason("CAPABILITY_QUERY_FAILED");
        }
        return response;
    }
    @Override
    public void validateCreate(java.util.Map<String, String> details, Storage.ProvisioningType legacy,
            long zoneId, Long poolId, String[] tags) {
        java.util.Map<String, String> intent = VolumeAllocationPolicy.requireCreationIntent(details);
        if (intent.isEmpty() && poolId == null) {
            return;
        }
        List<StoragePoolVO> pools = poolId == null ? poolDao.listByDataCenterId(zoneId) :
                java.util.Collections.singletonList(poolDao.findById(poolId));
        boolean qualified = false;
        for (StoragePoolVO pool : pools) {
            if (pool == null || pool.getDataCenterId() != zoneId || pool.getStatus() != StoragePoolStatus.Up) {
                continue;
            }
            List<String> poolTags = poolDao.searchForStoragePoolTags(pool.getId());
            if (tags != null && tags.length > 0 && (poolTags == null || !poolTags.containsAll(java.util.Arrays.asList(tags)))) {
                continue;
            }
            if (intent.isEmpty()) {
                if (pool.getPoolType() == Storage.StoragePoolType.RBD) {
                    VolumeAllocationPolicy.requireRbdLegacyThin(legacy);
                }
                return;
            }
            if (pool.getPoolType() == Storage.StoragePoolType.RBD &&
                    !"raw".equals(intent.get(VolumeAllocationPolicy.FORMAT))) {
                if (poolId != null) {
                    throw new InvalidParameterValueException("ALLOCATION_RBD_FORMAT_UNSUPPORTED: RBD allocation requires RAW; the requested image format is preserved");
                }
                continue;
            }
            if (pool.getPoolType() != Storage.StoragePoolType.RBD && pool.getPoolType() != Storage.StoragePoolType.SharedMountPoint) {
                // Explicit selection cannot silently fall through to a different backend.
                if (poolId != null) {
                    throw new InvalidParameterValueException("ALLOCATION_BACKEND_UNSUPPORTED: only qualified GFS2 or RBD execution is available");
                }
                continue;
            }
            boolean hasUpHost = false;
            boolean allQualified = true;
            for (com.cloud.storage.StoragePoolHostVO connection : poolHostDao.listByPoolId(pool.getId())) {
                HostVO host = hostDao.findById(connection.getHostId());
                if (host == null || host.getStatus() != Status.Up) {
                    continue;
                }
                hasUpHost = true;
                for (String operation : List.of("create", "copy")) {
                    VolumeAllocationCapabilityResponse capability = capabilities(pool, host, intent.get(VolumeAllocationPolicy.FORMAT), operation, intent.get(VolumeAllocationPolicy.POLICY));
                    allQualified &= capability.isSupported();
                }
            }
            qualified |= hasUpHost && allQualified;
            if (poolId != null && !qualified) {
                throw new InvalidParameterValueException("ALLOCATION_AGENT_UNAVAILABLE: all eligible hosts must advertise and verify the requested backend/format/policy create/copy capability");
            }
        }
        if (!intent.isEmpty() && !qualified) {
            throw new InvalidParameterValueException("ALLOCATION_AGENT_UNAVAILABLE: no matching qualified pool and Agent; no legacy fallback is allowed");
        }
    }

}
