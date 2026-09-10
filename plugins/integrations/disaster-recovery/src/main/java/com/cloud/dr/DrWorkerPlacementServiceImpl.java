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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dr.dao.DrResourceLeaseDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.component.ManagerBase;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Selects current workers without consulting Plan-persisted host bindings. */
public class DrWorkerPlacementServiceImpl extends ManagerBase implements DrWorkerPlacementService {
    private static final String HOST_PREFIX = "HOST:";

    @Inject private DrSiteDao drSiteDao;
    @Inject private DataCenterDao dataCenterDao;
    @Inject private HostDao hostDao;
    @Inject private HostDetailsDao hostDetailsDao;
    @Inject private DrResourceLeaseDao drResourceLeaseDao;

    @Override
    public Long resolveWorkerHostId(DrPlanVO plan, DrWorkerRole role) {
        return resolveWorkerHostId(plan, null, role);
    }

    @Override
    public Long resolveWorkerHostId(DrPlanVO plan, DrRunVO run, DrWorkerRole role) {
        Long leasedHostId = leasedHostId(run);
        if (leasedHostId != null && eligible(leasedHostId, plan, role)) {
            return leasedHostId;
        }
        List<HostVO> candidates = listEligibleWorkers(plan, role);
        if (candidates.isEmpty()) {
            return null;
        }
        int seed = plan != null && StringUtils.isNotBlank(plan.getUuid())
                ? plan.getUuid().hashCode() : plan != null ? Long.valueOf(plan.getId()).hashCode() : 0;
        return candidates.get(Math.floorMod(seed, candidates.size())).getId();
    }

    @Override
    public List<HostVO> listEligibleWorkers(DrPlanVO plan, DrWorkerRole role) {
        DrSiteVO site = siteFor(plan, role);
        Long zoneId = resolveExecutionZoneId(plan, role, site);
        if (zoneId == null || hostDao == null) {
            return Collections.emptyList();
        }
        List<HostVO> hosts = hostDao.listAllHostsUpByZoneAndHypervisor(zoneId, HypervisorType.KVM);
        if (hosts == null || hosts.isEmpty()) {
            return Collections.emptyList();
        }
        List<HostVO> eligible = new ArrayList<HostVO>();
        for (HostVO host : hosts) {
            if (host == null || host.getRemoved() != null || !eligibleForRole(host, role)) {
                continue;
            }
            eligible.add(host);
        }
        eligible.sort(Comparator.comparingLong(HostVO::getId));
        return eligible;
    }

    private DrSiteVO siteFor(DrPlanVO plan, DrWorkerRole role) {
        if (plan == null || drSiteDao == null) {
            return null;
        }
        long siteId = role == DrWorkerRole.SOURCE && plan.getSourceVmId() != null
                ? plan.getSourceSiteId() : plan.getTargetSiteId();
        return drSiteDao.findById(siteId);
    }

    private boolean eligible(Long hostId, DrPlanVO plan, DrWorkerRole role) {
        if (hostId == null || hostDao == null) {
            return false;
        }
        HostVO host = hostDao.findById(hostId);
        if (host == null || host.getRemoved() != null || host.getStatus() != Status.Up
                || host.getHypervisorType() != HypervisorType.KVM || !eligibleForRole(host, role)) {
            return false;
        }
        DrSiteVO site = siteFor(plan, role);
        Long zoneId = resolveExecutionZoneId(plan, role, site);
        return zoneId != null && host.getDataCenterId() == zoneId.longValue();
    }

    Long resolveExecutionZoneId(DrPlanVO plan, DrWorkerRole role, DrSiteVO site) {
        if (site != null && site.getZoneId() != null && zoneExists(site.getZoneId())) {
            return site.getZoneId();
        }
        Long mappedZoneId = findZoneId(mappingZoneRef(plan, role));
        if (mappedZoneId != null) {
            return mappedZoneId;
        }
        List<DataCenterVO> zones = dataCenterDao != null ? dataCenterDao.listEnabledZones() : null;
        return zones != null && zones.size() == 1 && zones.get(0) != null
                ? zones.get(0).getId() : null;
    }

    private boolean zoneExists(Long zoneId) {
        return zoneId != null && (dataCenterDao == null || dataCenterDao.findById(zoneId) != null);
    }

    private Long findZoneId(String ref) {
        String value = StringUtils.trimToNull(ref);
        if (value == null || dataCenterDao == null) {
            return null;
        }
        try {
            Long numeric = Long.valueOf(value);
            DataCenterVO zone = dataCenterDao.findById(numeric);
            if (zone != null) {
                return zone.getId();
            }
        } catch (NumberFormatException ignored) {
            // UUID lookup below handles non-numeric references.
        }
        List<DataCenterVO> zones = dataCenterDao.listEnabledZones();
        if (zones == null) {
            return null;
        }
        for (DataCenterVO zone : zones) {
            if (zone != null && StringUtils.equals(value, zone.getUuid())) {
                return zone.getId();
            }
        }
        return null;
    }

    private String mappingZoneRef(DrPlanVO plan, DrWorkerRole role) {
        if (plan == null || StringUtils.isBlank(plan.getMappingJson())) {
            return null;
        }
        boolean targetRole = role != DrWorkerRole.SOURCE || plan.getSourceVmId() == null;
        String sideName = targetRole ? "target" : "source";
        try {
            JsonElement parsed = JsonParser.parseString(plan.getMappingJson());
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject mapping = parsed.getAsJsonObject();
            String topLevel = stringValue(mapping, targetRole ? "targetZoneId" : "sourceZoneId");
            JsonObject side = objectValue(mapping, sideName);
            return StringUtils.defaultIfBlank(topLevel,
                    side != null ? firstNonBlank(stringValue(side, "zoneId"), stringValue(side, "zone")) : null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private JsonObject objectValue(JsonObject object, String name) {
        JsonElement value = object != null ? object.get(name) : null;
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private String stringValue(JsonObject object, String name) {
        JsonElement value = object != null ? object.get(name) : null;
        return value != null && !value.isJsonNull() && value.isJsonPrimitive()
                ? StringUtils.trimToNull(value.getAsString()) : null;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private boolean eligibleForRole(HostVO host, DrWorkerRole role) {
        if (role != DrWorkerRole.VDDK_DATA_PLANE) {
            return true;
        }
        String libDir = detail(host.getId(), Host.HOST_VDDK_LIB_DIR);
        String supported = detail(host.getId(), Host.HOST_VDDK_SUPPORT);
        return StringUtils.isNotBlank(libDir)
                && (StringUtils.isBlank(supported) || Boolean.parseBoolean(supported));
    }

    private String detail(long hostId, String name) {
        com.cloud.host.DetailVO detail = hostDetailsDao != null ? hostDetailsDao.findDetail(hostId, name) : null;
        return detail != null ? StringUtils.trimToNull(detail.getValue()) : null;
    }

    private Long leasedHostId(DrRunVO run) {
        if (run == null || drResourceLeaseDao == null) {
            return null;
        }
        DrResourceLeaseVO lease = drResourceLeaseDao.findActiveByRunId(run.getId(), new Date());
        String key = lease != null ? lease.getResourceKey() : null;
        if (!StringUtils.startsWith(key, HOST_PREFIX)) {
            return null;
        }
        String value = StringUtils.substringBefore(StringUtils.substringAfter(key, HOST_PREFIX), ":");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
