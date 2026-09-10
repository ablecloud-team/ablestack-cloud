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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.cloudstack.api.response.dr.DrVmPlanAssociationResponse;
import org.apache.cloudstack.api.response.dr.DrVmProtectionViewResponse;

import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.component.ManagerBase;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Builds the VM detail DR view exclusively from the local Cloud database. */
public class DrVmProtectionViewServiceImpl extends ManagerBase implements DrVmProtectionViewService {
    private static final String SOURCE = "SOURCE";
    private static final String TARGET = "TARGET";

    @Inject private UserVmDao userVmDao;
    @Inject private DrPlanDao drPlanDao;
    @Inject private DrReplicaDao drReplicaDao;
    @Inject private DrTestSessionDao drTestSessionDao;
    @Inject private DrPlanRuntimeDao drPlanRuntimeDao;
    @Inject private DrRunDao drRunDao;
    @Inject private DrSiteDao drSiteDao;

    @Override
    public DrVmProtectionViewResponse getView(long vmId) {
        UserVmVO viewedVm = userVmDao.findById(vmId);
        if (viewedVm == null) {
            throw new InvalidParameterValueException("Unable to find the virtual machine");
        }

        Map<String, DrVmPlanAssociationResponse> associations = new LinkedHashMap<String, DrVmPlanAssociationResponse>();
        int permanentRelationships = 0;

        for (DrPlanVO plan : drPlanDao.listActiveBySourceVmId(vmId)) {
            addAssociation(associations, plan, "SOURCE", firstReplica(plan.getId()), null);
            permanentRelationships++;
        }
        for (DrReplicaVO replica : drReplicaDao.listActiveByTargetVmId(vmId)) {
            DrPlanVO plan = activePlan(replica.getPlanId());
            if (plan != null) {
                addAssociation(associations, plan, "RECOVERY_TARGET", replica, null);
                permanentRelationships++;
            }
        }
        for (DrTestSessionVO session : drTestSessionDao.listActiveByTargetVmId(vmId)) {
            DrPlanVO plan = activePlan(session.getPlanId());
            if (plan != null) {
                addAssociation(associations, plan, "TEST_TARGET", firstReplica(plan.getId()), session);
            }
        }

        DrVmProtectionViewResponse response = new DrVmProtectionViewResponse();
        response.setVirtualMachineId(viewedVm.getUuid());
        response.setVirtualMachineName(displayName(viewedVm));
        response.setManagementScope("LOCAL_CONTROLLER");
        response.setConfigured(!associations.isEmpty());
        response.setRelationConflict(permanentRelationships > 1);
        response.setProjectionState(aggregateProjectionState(associations.values()));
        response.setGenerated(new Date());
        response.setAssociations(new ArrayList<DrVmPlanAssociationResponse>(associations.values()));
        return response;
    }

    private void addAssociation(Map<String, DrVmPlanAssociationResponse> associations, DrPlanVO plan,
            String relationshipRole, DrReplicaVO replica, DrTestSessionVO testSession) {
        String key = plan.getId() + ":" + relationshipRole;
        if (associations.containsKey(key)) {
            return;
        }
        DrPlanRuntimeVO runtime = drPlanRuntimeDao.findByPlanId(plan.getId());
        DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());
        DrSiteVO sourceSite = drSiteDao.findById(plan.getSourceSiteId());
        DrSiteVO targetSite = drSiteDao.findById(plan.getTargetSiteId());
        boolean sourceRelationship = SOURCE.equals(relationshipRole);
        UserVmVO sourceVm = sourceRelationship && plan.getSourceVmId() != null
                ? userVmDao.findById(plan.getSourceVmId()) : null;
        UserVmVO targetVm = sourceRelationship ? null : resolveTargetVm(replica, testSession);

        DrVmPlanAssociationResponse response = new DrVmPlanAssociationResponse();
        response.setPlanId(plan.getUuid());
        response.setPlanName(plan.getName());
        response.setRelationshipRole(relationshipRole);
        response.setAuthorityRole(authorityRole(plan, relationshipRole));
        response.setPlanState(plan.getState());
        response.setAdminState(plan.getAdminState());
        response.setDirection(plan.getDirection());
        response.setSourceSiteId(sourceSite != null ? sourceSite.getUuid() : null);
        response.setSourceSiteName(sourceSite != null ? sourceSite.getName() : null);
        response.setSourceVmId(sourceVm != null ? sourceVm.getUuid() : plan.getSourceExternalRef());
        response.setSourceVmName(sourceVm != null ? displayName(sourceVm) : persistedSourceName(plan));
        response.setSourceVmState(sourceVm != null ? String.valueOf(sourceVm.getState()) : null);
        response.setTargetSiteId(targetSite != null ? targetSite.getUuid() : null);
        response.setTargetSiteName(targetSite != null ? targetSite.getName() : null);
        response.setTargetVmId(targetVm != null ? targetVm.getUuid() : targetExternalRef(replica, testSession));
        response.setTargetVmName(targetVm != null ? displayName(targetVm) : targetName(replica, testSession));
        response.setTargetVmState(targetVm != null ? String.valueOf(targetVm.getState()) : null);
        response.setTargetMaterializationState(testSession != null ? testSession.getState()
                : replica != null ? replica.getState() : "PENDING");
        response.setTargetPowerState(replica != null ? replica.getPowerState()
                : targetVm != null ? String.valueOf(targetVm.getState()) : null);
        response.setProtectionState(runtime != null ? runtime.getProtectionState() : plan.getState());
        response.setFreshnessState(runtime != null ? runtime.getFreshnessState() : "UNKNOWN");
        response.setReplicationActivity(runtime != null ? runtime.getReplicationActivityState() : "UNKNOWN");
        response.setRpoSeconds(plan.getRpoSeconds());
        response.setRpoAgeSeconds(runtime != null ? runtime.getRpoAgeSeconds() : null);
        response.setLastTargetDurableAt(runtime != null ? runtime.getLastTargetDurableAt() : plan.getLastTargetDurableAt());
        response.setProjectionState(runtime != null ? "READY" : "UNKNOWN");
        response.setDataUpdatedAt(runtime != null ? runtime.getLastStatusAt()
                : plan.getUpdated() != null ? plan.getUpdated() : plan.getCreated());
        if (latestRun != null) {
            response.setLatestRunId(latestRun.getUuid());
            response.setLatestRunType(latestRun.getRunType());
            response.setLatestRunState(latestRun.getState());
        }
        associations.put(key, response);
    }

    private DrPlanVO activePlan(long planId) {
        DrPlanVO plan = drPlanDao.findById(planId);
        return plan == null || plan.getRemoved() != null ? null : plan;
    }

    private DrReplicaVO firstReplica(long planId) {
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(planId);
        return replicas.isEmpty() ? null : replicas.get(0);
    }

    private UserVmVO resolveTargetVm(DrReplicaVO replica, DrTestSessionVO session) {
        Long targetVmId = session != null ? session.getTargetVmId() : replica != null ? replica.getTargetVmId() : null;
        return targetVmId != null ? userVmDao.findById(targetVmId) : null;
    }

    private String targetExternalRef(DrReplicaVO replica, DrTestSessionVO session) {
        if (session != null && StringUtils.isNotBlank(session.getTargetVmUuid())) {
            return session.getTargetVmUuid();
        }
        return replica != null ? replica.getTargetExternalRef() : null;
    }

    private String targetName(DrReplicaVO replica, DrTestSessionVO session) {
        return session != null ? session.getTargetVmName() : replica != null ? replica.getTargetVmName() : null;
    }

    private String authorityRole(DrPlanVO plan, String relationshipRole) {
        if ("TEST_TARGET".equals(relationshipRole)) {
            return "TEST_ISOLATED";
        }
        String side = StringUtils.upperCase(StringUtils.defaultIfBlank(plan.getActiveSide(), SOURCE));
        boolean active = ("SOURCE".equals(relationshipRole) && SOURCE.equals(side))
                || ("RECOVERY_TARGET".equals(relationshipRole) && TARGET.equals(side));
        return active ? "ACTIVE" : "STANDBY";
    }

    private String persistedSourceName(DrPlanVO plan) {
        String name = firstJsonString(plan.getMappingJson(), "sourceVmName", "sourceWorkloadName");
        return StringUtils.defaultIfBlank(name, plan.getSourceExternalRef());
    }

    private String firstJsonString(String value, String... names) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return findJsonString(JsonParser.parseString(value), names);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String findJsonString(JsonElement element, String... names) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String name : names) {
                JsonElement candidate = object.get(name);
                if (candidate != null && candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()
                        && StringUtils.isNotBlank(candidate.getAsString())) {
                    return candidate.getAsString();
                }
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String nested = findJsonString(entry.getValue(), names);
                if (StringUtils.isNotBlank(nested)) {
                    return nested;
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String nested = findJsonString(item, names);
                if (StringUtils.isNotBlank(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String aggregateProjectionState(Iterable<DrVmPlanAssociationResponse> associations) {
        boolean configured = false;
        for (DrVmPlanAssociationResponse association : associations) {
            configured = true;
            if (!"READY".equals(association.getProjectionState())) {
                return "UNKNOWN";
            }
        }
        return configured ? "READY" : "NOT_MANAGED_HERE";
    }

    private String displayName(UserVmVO vm) {
        return StringUtils.defaultIfBlank(vm.getDisplayName(), vm.getName());
    }
}
