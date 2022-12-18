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

package org.apache.cloudstack.ha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.ha.ConfigureHAForHostCmd;
import org.apache.cloudstack.api.command.admin.ha.DisableHAForClusterCmd;
import org.apache.cloudstack.api.command.admin.ha.DisableHAForHostCmd;
import org.apache.cloudstack.api.command.admin.ha.DisableHAForZoneCmd;
import org.apache.cloudstack.api.command.admin.ha.EnableHAForClusterCmd;
import org.apache.cloudstack.api.command.admin.ha.EnableBalancingClusterCmd;
import org.apache.cloudstack.api.command.admin.ha.DisableBalancingClusterCmd;
import org.apache.cloudstack.api.command.admin.ha.EnableHAForHostCmd;
import org.apache.cloudstack.api.command.admin.ha.EnableHAForZoneCmd;
import org.apache.cloudstack.api.command.admin.ha.ListHostHAProvidersCmd;
import org.apache.cloudstack.api.command.admin.ha.ListHostHAResourcesCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.ha.dao.HAConfigDao;
import org.apache.cloudstack.ha.provider.HAProvider;
import org.apache.cloudstack.ha.provider.HAProvider.HAProviderConfig;
import org.apache.cloudstack.ha.task.ActivityCheckTask;
import org.apache.cloudstack.ha.task.FenceTask;
import org.apache.cloudstack.ha.task.HealthCheckTask;
import org.apache.cloudstack.ha.task.RecoveryTask;
import org.apache.cloudstack.kernel.Partition;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.poll.BackgroundPollManager;
import org.apache.cloudstack.poll.BackgroundPollTask;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import com.cloud.host.HostVO;
import com.cloud.vm.VMInstanceVO;
import org.apache.cloudstack.api.response.HostResponse;
import com.cloud.cluster.ClusterManagerListener;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterDetailVO;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.UserVmService;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceService;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.google.common.base.Preconditions;
import org.apache.cloudstack.api.ResponseGenerator;

public final class HAManagerImpl extends ManagerBase implements HAManager, ClusterManagerListener, PluggableService, Configurable, StateListener<HAConfig.HAState, HAConfig.Event, HAConfig> {
    public static final Logger LOG = Logger.getLogger(HAManagerImpl.class);

    @Inject
    private HAConfigDao haConfigDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    protected UserVmService userVmService;

    @Inject
    protected ResourceService resourceService;

    @Inject
    private ClusterDetailsDao clusterDetailsDao;

    @Inject
    private DataCenterDetailsDao dataCenterDetailsDao;

    @Inject
    private BackgroundPollManager pollManager;

    @Inject
    public ResponseGenerator _responseGenerator;

    private List<HAProvider<HAResource>> haProviders;
    private Map<String, HAProvider<HAResource>> haProviderMap = new HashMap<>();

    private static ExecutorService healthCheckExecutor;
    private static ExecutorService activityCheckExecutor;
    private static ExecutorService recoveryExecutor;
    private static ExecutorService fenceExecutor;

    private static final String HA_ENABLED_DETAIL = "resourceHAEnabled";
    private static final String Balancing_ENABLED_DETAIL = "resourceBalancingEnabled";

    //////////////////////////////////////////////////////
    //////////////// HA Manager methods //////////////////
    //////////////////////////////////////////////////////

    public Map<String, HAResourceCounter> haCounterMap = new ConcurrentHashMap<>();

    public HAProvider<HAResource> getHAProvider(final String name) {
        return haProviderMap.get(name);
    }

    private String resourceCounterKey(final Long resourceId, final HAResource.ResourceType resourceType) {
        return resourceId.toString() + resourceType.toString();
    }

    public synchronized HAResourceCounter getHACounter(final Long resourceId, final HAResource.ResourceType resourceType) {
        final String key = resourceCounterKey(resourceId, resourceType);
        if (!haCounterMap.containsKey(key)) {
            haCounterMap.put(key, new HAResourceCounter());
        }
        return haCounterMap.get(key);
    }

    public synchronized void purgeHACounter(final Long resourceId, final HAResource.ResourceType resourceType) {
        final String key = resourceCounterKey(resourceId, resourceType);
        if (haCounterMap.containsKey(key)) {
            haCounterMap.remove(key);
        }
    }

    public boolean transitionHAState(final HAConfig.Event event, final HAConfig haConfig) {
        if (event == null || haConfig == null) {
            return false;
        }
        final HAConfig.HAState currentHAState = haConfig.getState();
        try {
            final HAConfig.HAState nextState = HAConfig.HAState.getStateMachine().getNextState(currentHAState, event);
            boolean result = HAConfig.HAState.getStateMachine().transitTo(haConfig, event, null, haConfigDao);
            if (result) {
                final String message = String.format("Transitioned host HA state from:%s to:%s due to event:%s for the host id:%d",
                        currentHAState, nextState, event, haConfig.getResourceId());
                LOG.debug(message);

                if (nextState == HAConfig.HAState.Recovering || nextState == HAConfig.HAState.Fencing || nextState == HAConfig.HAState.Fenced) {
                    ActionEventUtils.onActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(),
                            Domain.ROOT_DOMAIN, EventTypes.EVENT_HA_STATE_TRANSITION, message, haConfig.getResourceId(), ApiCommandResourceType.Host.toString());
                }
            }
            return result;
        } catch (NoTransitionException e) {
            LOG.warn(String.format("Unable to find next HA state for current HA state=[%s] for event=[%s] for host=[%s].", currentHAState, event, haConfig.getResourceId()), e);
        }
        return false;
    }

    private boolean transitionResourceStateToDisabled(final Partition partition) {
        List<? extends HAResource> resources;
        if (partition.partitionType() == Partition.PartitionType.Cluster) {
            resources = hostDao.findByClusterId(partition.getId());
        } else if (partition.partitionType() == Partition.PartitionType.Zone) {
            resources = hostDao.findByDataCenterId(partition.getId());
        } else {
            return true;
        }

        boolean result = true;
        for (final HAResource resource: resources) {
            result = result && transitionHAState(HAConfig.Event.Disabled,
                    haConfigDao.findHAResource(resource.getId(), resource.resourceType()));
        }
        return result;
    }

    private boolean checkHAOwnership(final HAConfig haConfig) {
        // Skip for resources not owned by this mgmt server
        return !(haConfig.getManagementServerId() != null
                && haConfig.getManagementServerId() != ManagementServerNode.getManagementServerId());
    }

    private HAResource validateAndFindHAResource(final HAConfig haConfig) {
        HAResource resource = null;
        if (haConfig == null) {
            return null;
        }
        if (haConfig.getResourceType() == HAResource.ResourceType.Host) {
            final Host host = hostDao.findById(haConfig.getResourceId());
            if (host != null && host.getRemoved() != null) {
                return null;
            }
            resource = host;
            if (haConfig.getState() == null || (resource == null && haConfig.getState() != HAConfig.HAState.Disabled)) {
                disableHA(haConfig.getResourceId(), haConfig.getResourceType());
                return null;
            }
        }
        if (!haConfig.isEnabled() || !isHAEnabledForZone(resource) || !isHAEnabledForCluster(resource)) {
            if (haConfig.getState() != HAConfig.HAState.Disabled) {
                if (transitionHAState(HAConfig.Event.Disabled, haConfig) ) {
                    purgeHACounter(haConfig.getResourceId(), haConfig.getResourceType());
                }
            }
            return null;
        } else if (haConfig.getState() == HAConfig.HAState.Disabled) {
            transitionHAState(HAConfig.Event.Enabled, haConfig);
        }
        return resource;
    }

    private HAProvider<HAResource> validateAndFindHAProvider(final HAConfig haConfig, final HAResource resource) {
        if (haConfig == null) {
            return null;
        }
        final HAProvider<HAResource> haProvider = haProviderMap.get(haConfig.getHaProvider());
        if (haProvider != null && !haProvider.isEligible(resource)) {
            if (haConfig.getState() != HAConfig.HAState.Ineligible) {
                transitionHAState(HAConfig.Event.Ineligible, haConfig);
            }
            return null;
        } else if (haConfig.getState() == HAConfig.HAState.Ineligible) {
            transitionHAState(HAConfig.Event.Eligible, haConfig);
        }
        return haProvider;
    }

    public boolean isHAEnabledForZone(final HAResource resource) {
        if (resource == null || resource.getDataCenterId() < 1L) {
            return true;
        }
        final DataCenterDetailVO zoneDetails = dataCenterDetailsDao.findDetail(resource.getDataCenterId(), HA_ENABLED_DETAIL);
        return zoneDetails == null || StringUtils.isEmpty(zoneDetails.getValue()) || Boolean.valueOf(zoneDetails.getValue());
    }

    private boolean isHAEnabledForCluster(final HAResource resource) {
        if (resource == null || resource.getClusterId() == null) {
            return true;
        }
        final ClusterDetailsVO clusterDetails = clusterDetailsDao.findDetail(resource.getClusterId(), HA_ENABLED_DETAIL);
        return clusterDetails == null || StringUtils.isEmpty(clusterDetails.getValue()) || Boolean.valueOf(clusterDetails.getValue());
    }

    private boolean isHAEligibleForResource(final HAResource resource) {
        if (resource == null || resource.getId() < 1L) {
            return false;
        }
        HAResource.ResourceType resourceType = null;
        if (resource instanceof Host) {
            resourceType = HAResource.ResourceType.Host;
        }
        if (resourceType == null) {
            return false;
        }
        final HAConfig haConfig = haConfigDao.findHAResource(resource.getId(), resourceType);
        return haConfig != null && haConfig.isEnabled()
                && haConfig.getState() != HAConfig.HAState.Disabled
                && haConfig.getState() != HAConfig.HAState.Ineligible;
    }

    public boolean isHAEligible(final HAResource resource) {
        return resource != null && isHAEnabledForZone(resource)
                && isHAEnabledForCluster(resource)
                && isHAEligibleForResource(resource);
    }

    public void validateHAProviderConfigForResource(final Long resourceId, final HAResource.ResourceType resourceType, final HAProvider<HAResource> haProvider) {
        if (HAResource.ResourceType.Host.equals(resourceType)) {
            final Host host = hostDao.findById(resourceId);

            if (host == null) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Resource [%s] not found.", resourceId));
            }

            if (host.getHypervisorType() == null) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("No hypervisor type provided on resource [%s].", resourceId));
            }

            if (haProvider.resourceSubType() == null) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "No hypervisor type provided on haprovider.");
            }

            if (!host.getHypervisorType().toString().equals(haProvider.resourceSubType().toString())) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Incompatible haprovider provided [%s] for the resource [%s] of hypervisor type: [%s].", haProvider.resourceSubType().toString(), host.getId(),host.getHypervisorType()));
            }
        }
    }

    ////////////////////////////////////////////////////////////////////
    //////////////// HA Investigator wrapper for Old HA ////////////////
    ////////////////////////////////////////////////////////////////////

    public Boolean isVMAliveOnHost(final Host host) throws Investigator.UnknownVM {
        final HAConfig haConfig = haConfigDao.findHAResource(host.getId(), HAResource.ResourceType.Host);
        if (haConfig != null) {
            if (haConfig.getState() == HAConfig.HAState.Fenced) {
                LOG.debug(String.format("HA: Host [%s] is fenced.", host.getId()));
                return false;
            }
            LOG.debug(String.format("HA: Host [%s] is alive.", host.getId()));
            return true;
        }
        throw new Investigator.UnknownVM();
    }

    public Status getHostStatus(final Host host) {
        final HAConfig haConfig = haConfigDao.findHAResource(host.getId(), HAResource.ResourceType.Host);
        if (haConfig != null) {
            if (haConfig.getState() == HAConfig.HAState.Fenced) {
                LOG.debug(String.format("HA: Agent [%s] is available/suspect/checking Up.", host.getId()));
                return Status.Down;
            } else if (haConfig.getState() == HAConfig.HAState.Degraded || haConfig.getState() == HAConfig.HAState.Recovering || haConfig.getState() == HAConfig.HAState.Fencing) {
                LOG.debug(String.format("HA: Agent [%s] is disconnected. State: %s, %s.", host.getId(), haConfig.getState(), haConfig.getState().getDescription()));
                return Status.Disconnected;
            }
            return Status.Up;
        }
        return Status.Unknown;
    }

    //////////////////////////////////////////////////////
    //////////////// HA API handlers /////////////////////
    //////////////////////////////////////////////////////

    private boolean configureHA(final Long resourceId, final HAResource.ResourceType resourceType, final Boolean enable, final String haProvider) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                HAConfigVO haConfig = (HAConfigVO) haConfigDao.findHAResource(resourceId, resourceType);
                if (haConfig == null) {
                    haConfig = new HAConfigVO();
                    if (haProvider != null) {
                        haConfig.setHaProvider(haProvider);
                    }
                    if (enable != null) {
                        haConfig.setEnabled(enable);
                        haConfig.setManagementServerId(ManagementServerNode.getManagementServerId());
                    }
                    if (haProvider != null && enable != null) {
                        haConfig.setHastate(HAConfig.HAState.Available);
                    }
                    haConfig.setResourceId(resourceId);
                    haConfig.setResourceType(resourceType);
                    if (StringUtils.isEmpty(haConfig.getHaProvider())) {
                        throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("HAProvider is not provided for the resource [%s], failing configuration.", resourceId));
                    }
                    if (haConfigDao.persist(haConfig) != null) {
                        return true;
                    }
                } else {
                    if (enable != null) {
                        haConfig.setEnabled(enable);
                    }
                    if (haProvider != null) {
                        haConfig.setHaProvider(haProvider);
                    }
                    if (StringUtils.isEmpty(haConfig.getHaProvider())) {
                        throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("HAProvider is not provided for the resource [%s], failing configuration.", resourceId));
                    }
                    return haConfigDao.update(haConfig.getId(), haConfig);
                }
                return false;
            }
        });
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_CONFIGURE, eventDescription = "configuring HA for resource")
    public boolean configureHA(final Long resourceId, final HAResource.ResourceType resourceType, final String haProvider) {
        Preconditions.checkArgument(resourceId != null && resourceId > 0L);
        Preconditions.checkArgument(resourceType != null);
        Preconditions.checkArgument(StringUtils.isNotEmpty(haProvider));

        if (!haProviderMap.containsKey(haProvider.toLowerCase())) {
            throw new CloudRuntimeException(String.format("Given HA provider [%s] does not exist.", haProvider));
        }
        validateHAProviderConfigForResource(resourceId, resourceType, haProviderMap.get(haProvider.toLowerCase()));
        return configureHA(resourceId, resourceType, null, haProvider.toLowerCase());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_ENABLE, eventDescription = "enabling HA for resource")
    public boolean enableHA(final Long resourceId, final HAResource.ResourceType resourceType) {
        Preconditions.checkArgument(resourceId != null && resourceId > 0L);
        Preconditions.checkArgument(resourceType != null);
        return configureHA(resourceId, resourceType, true, null);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_DISABLE, eventDescription = "disabling HA for resource")
    public boolean disableHA(final Long resourceId, final HAResource.ResourceType resourceType) {
        Preconditions.checkArgument(resourceId != null && resourceId > 0L);
        Preconditions.checkArgument(resourceType != null);
        boolean result = configureHA(resourceId, resourceType, false, null);
        if (result) {
            transitionHAState(HAConfig.Event.Disabled, haConfigDao.findHAResource(resourceId, resourceType));
            purgeHACounter(resourceId, resourceType);
        }
        return result;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_ENABLE, eventDescription = "enabling HA for a cluster")
    public boolean enableHA(final Cluster cluster, Boolean includeHost) {
        clusterDetailsDao.persist(cluster.getId(), HA_ENABLED_DETAIL, String.valueOf(true));

        //host enableHA
        if (includeHost) {
            List<? extends HAResource> resources = hostDao.findByClusterId(cluster.getId());
            for (HAResource resource : resources) {
                final HAConfig haConfig = haConfigDao.findHAResource(resource.getId(), resource.resourceType());
                if (haConfig == null) {
                    boolean configureHA = configureHA(resource.getId(), resource.resourceType(), true, "kvmhaprovider");
                } else {
                    boolean result = enableHA(resource.getId(), resource.resourceType());
                }
            }
        }
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_DISABLE, eventDescription = "disabling HA for a cluster")
    public boolean disableHA(final Cluster cluster, Boolean includeHost) {
        clusterDetailsDao.persist(cluster.getId(), HA_ENABLED_DETAIL, String.valueOf(false));

        //host disableHA
        if (includeHost) {
            List<? extends HAResource> resources = hostDao.findByClusterId(cluster.getId());
            for (HAResource resource : resources) {
                final HAConfig haConfig = haConfigDao.findHAResource(resource.getId(), resource.resourceType());
                if (haConfig != null && haConfig.isEnabled()) {
                    boolean result = disableHA(resource.getId(), resource.resourceType());
                }
            }
        }
        return transitionResourceStateToDisabled(cluster);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_ENABLE, eventDescription = "enabling HA for a zone")
    public boolean enableHA(final DataCenter zone) {
        dataCenterDetailsDao.persist(zone.getId(), HA_ENABLED_DETAIL, String.valueOf(true));
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_DISABLE, eventDescription = "disabling HA for a zone")
    public boolean disableHA(final DataCenter zone) {
        dataCenterDetailsDao.persist(zone.getId(), HA_ENABLED_DETAIL, String.valueOf(false));
        return transitionResourceStateToDisabled(zone);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_ENABLE, eventDescription = "enabling Balancing for a cluster")
    public boolean enableBalancing(final Cluster cluster) {
        clusterDetailsDao.persist(cluster.getId(), Balancing_ENABLED_DETAIL, String.valueOf(true));

        /*
        LOG.info("======================");
        Map<String, Integer> hostMemMap = new ConcurrentHashMap<String, Integer>();
        List<? extends HostVO> hosts = hostDao.findByClusterId(cluster.getId());
        for (HostVO host : hosts) {
            LOG.info("Host = "+host);
            LOG.info("Host id = "+host.getId());
            LOG.info("Host cap = "+host.getCapabilities());
            HostResponse hostResponse = _responseGenerator.createHostResponse(host);
            LOG.info("hostResponse =" + hostResponse);
            LOG.info(hostResponse.getId());
            LOG.info(hostResponse.getMemoryAllocated());
            LOG.info(hostResponse.getMemoryTotal());
            LOG.info(hostResponse.getMemoryUsed());
            LOG.info(hostResponse.getMemoryAllocated()*100/hostResponse.getMemoryTotal());
            LOG.info("======================");
        }
        */

        // BalancingRunnable balancingRunnable = new BalancingRunnable();
        // balancingRunnable.balancingCheck(cluster.getId());

        // balancingCheck(cluster.getId());

        /* Using Runnable Interface */
        Thread th4 = new Thread(new ThirdThread(cluster.getId()));
        th4.start();

        return true;
    }

    //내부 클래스 - Runnable 구현
    class ThirdThread implements Runnable{
        private long clusterId;
        public ThirdThread(long clusterid) {
            this.clusterId = clusterid;
        }

        @Override
        public void run() {
            balancingCheck(clusterId);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HA_RESOURCE_ENABLE, eventDescription = "enabling Balancing for a cluster")
    public boolean disableBalancing(final Cluster cluster) {
        clusterDetailsDao.persist(cluster.getId(), Balancing_ENABLED_DETAIL, String.valueOf(false));
        return true;
    }

    public void balancingCheck (long clusterId) {
        LOG.info("======================");
        LOG.info("clusterId = " + clusterId);
        HashMap<Long, Long> hostMemMap = new HashMap<Long, Long>();
        // List keyList = new ArrayList();
        // List<? extends HostVO> hosts = hostDao.findByClusterId(clusterId);
        // LOG.info("Hostsss = "+hosts);
        for (final HostVO host: hostDao.findByClusterId(clusterId)) {
        // for (HostVO host : hosts) {
            LOG.info("Host = "+host);
            LOG.info("Host id = "+host.getId());
            LOG.info("Host cap = "+host.getCapabilities());
            HostResponse hostResponse = _responseGenerator.createHostResponse(host);
            LOG.info("hostResponse =" + hostResponse);
            LOG.info(hostResponse.getId());
            LOG.info("allocated = " + hostResponse.getMemoryAllocated());
            LOG.info("total = " + hostResponse.getMemoryTotal());
            LOG.info("used = " + hostResponse.getMemoryUsed());
            LOG.info(hostResponse.getMemoryAllocated()*100/hostResponse.getMemoryTotal());
            LOG.info("======================");

            // hostMemMap.put(hostResponse.getId(), hostResponse.getMemoryUsed()*100/hostResponse.getMemoryTotal());
            hostMemMap.put(host.getId(), hostResponse.getMemoryAllocated()*100/hostResponse.getMemoryTotal());

            // keyList.add(hostResponse.getMemoryAllocated()*100/hostResponse.getMemoryTotal());
            // keyList.add(host.getPrivateIpAddress());

            // hostMemMap.put(hostResponse.getId(), keyList);
            // {hostResponse.getMemoryAllocated()*100/hostResponse.getMemoryTotal(), host.getPrivateIpAddress()};
        }

        // Comparator 정의
        Comparator<Entry<Long, Long>> comparator = new Comparator<Entry<Long, Long>>() {
            @Override
            public int compare(Entry<Long, Long> e1, Entry<Long, Long> e2) {
                return e1.getValue().compareTo(e2.getValue());
            }
        };

        // Max Value의 key, value
        Entry<Long, Long> maxEntry = Collections.max(hostMemMap.entrySet(), comparator);

        // Min Value의 key, value
        Entry<Long, Long> minEntry = Collections.min(hostMemMap.entrySet(), comparator);

        LOG.info("start======================");
        LOG.info("maxEntry = " + maxEntry.getValue() + ", minEntry = " + minEntry.getValue());
        LOG.info("persent = " + (maxEntry.getValue() - minEntry.getValue()));
        //memory 값이 10% 이상 차이나면 vm migration
        if ((maxEntry.getValue() - minEntry.getValue()) > 10 ) {
            String hostIp = "";
            for (final HostVO host: hostDao.findByClusterId(clusterId)) {
                LOG.info("maxEntry.getKey() = "+maxEntry.getKey());
                LOG.info("host.getUuid() = "+host.getUuid());
                LOG.info("host.getId() = "+host.getId());
                if(host.getId() == maxEntry.getKey()) {
                    hostIp = host.getPrivateIpAddress();
                }
            }
            balancingMonitor(maxEntry.getKey(), hostIp);
        }

        //1분 체크
        try {
            Thread.sleep(60000);
            balancingCheck(clusterId);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        LOG.info("end======================");
    }

    private void balancingMonitor(Long hostId, String hostIp) {
        // List<? extends VMInstanceVO> vmList = vmInstanceDao.listByHostId(hostId);
        Map<Long, Long> vmMemMap = new ConcurrentHashMap<Long, Long>();

        for (final VMInstanceVO vm: vmInstanceDao.listByHostId(hostId)) {
            //host ip 조회
            LOG.info("hostId = "+hostId);
            // HostVO host = hostDao.findByUuid(hostId);
            // LOG.info("hostIp = "+host.getPrivateIpAddress());
            String instanceName = vm.getInstanceName();
            String s;
            String vm_pid = "";
            Long oomScore;
            try {
                //vm pid

                LOG.info("instanceName = "+instanceName);
                /*
                Runtime runtime = Runtime.getRuntime();
                String command = "sh /root/1218_lb_rpm/oomScore.sh "+hostIp+" "+instanceName;
                Process process = runtime.exec(command);
                process.waitFor();

                InputStream is = process.getInputStream();
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr);
                LOG.info("br = "+br);
                

                Runtime runtime = Runtime.getRuntime();
                Process process = runtime.exec("sh /root/1218_lb_rpm/oomScore.sh "+hostIp+" "+instanceName);
                InputStream is = process.getInputStream();
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr);
                String line;

                while((line = br.readLine()) != null) {
                    LOG.info("result = " + line);
                    oomScore = Long.parseLong(line);
                }
*/
                String cmd = "ssh";
                String url = hostIp;
                String args = "";
                String line = "";
                ProcessBuilder processBuilder = new ProcessBuilder();
                Process process = null;
                processBuilder.command().add("ssh");
                processBuilder.command().add("root@"+hostIp);
                processBuilder.command().add("ps -aux | grep "+ instanceName);
                processBuilder.command().add("| awk '{print $2}' | head -1");
                LOG.info("command = " + processBuilder.command());
                try {
                    process = processBuilder.start();
                    LOG.info("process = " + process);
                    InputStream is = process.getInputStream();
                    InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader br = new BufferedReader(isr);
                    LOG.info("br = " + br);

                    while ((line = br.readLine()) != null) {
                        String contents = line + "\n";
                        LOG.info("contents = " + contents);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                

                // LOG.info("oomScore = "+oomScore);
                // String cmd = "ps -aux | grep "+ instanceName +" | awk '{print $2}' | head -1";
                // String cmd = "ssh root@"+ hostIp +" ps -aux | grep "+ instanceName +" | awk '{print $2}' | head -1";
                // String pid_cmd = "";
/*
                String cmd1 = "date";
                LOG.info("cmd1 = "+cmd1);
                Process p2 = Runtime.getRuntime().exec(cmd1);
                p2.waitFor();
                LOG.info("p3 = "+p2);

                String cmd2 = "ssh root@"+ hostIp +" date";
                LOG.info("cmd2 = "+cmd2);
                Process p = Runtime.getRuntime().exec(cmd2);
                p.waitFor();
                LOG.info("p2 = "+p);

                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                LOG.info("br = "+br);
                String sb = "";
                while ((s = br.readLine()) != null)
                    sb += s;
                LOG.info("sb = "+sb);
                vm_pid = sb.toString();
                p.waitFor();
                p.destroy();

                LOG.info("vm_pid = "+vm_pid);
*/

                //oom_score
                // cmd = "cat /proc/"+ vm_pid +"/oom_score";
/*                cmd = "ssh root@"+ hostIp +" cat /proc/"+ vm_pid +"/oom_score";
                // cmd = { "ssh", "root@"+hostIp, "cat /proc/"+vm_pid+"/oom_score" };
                p = Runtime.getRuntime().exec(cmd);
                br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                sb = "";
                while ((s = br.readLine()) != null)
                    sb += s;
                oomScore = Long.parseLong(sb.toString());
                p.waitFor();
                p.destroy();

                LOG.info("oomScore = "+oomScore);
                LOG.info("vm.getId() = "+vm.getId());*/

                // vmMemMap.put(vm.getId(), oomScore);
            } catch (Exception e) {
            }
        }

        // Comparator 정의
        Comparator<Entry<Long, Long>> comparator = new Comparator<Entry<Long, Long>>() {
            @Override
            public int compare(Entry<Long, Long> e1, Entry<Long, Long> e2) {
                return e1.getValue().compareTo(e2.getValue());
            }
        };

        // Min Value의 key, value
        Entry<Long, Long> minEntry = Collections.min(vmMemMap.entrySet(), comparator);

        LOG.info("vm minEntry = "+minEntry.getKey());

        //vm migration
        try {
            Host destinationHost = resourceService.getHost(hostId);
            userVmService.migrateVirtualMachine(minEntry.getKey(), destinationHost);
        } catch (ResourceUnavailableException ex) {
            LOG.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        } catch (VirtualMachineMigrationException | ConcurrentOperationException | ManagementServerException e) {
            LOG.warn("Exception: ", e);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public List<HAConfig> listHAResources(final Long resourceId, final HAResource.ResourceType resourceType) {
        return haConfigDao.listHAResource(resourceId, resourceType);
    }

    @Override
    public List<String> listHAProviders(final HAResource.ResourceType resourceType, final HAResource.ResourceSubType entityType) {
        final List<String> haProviderNames = new ArrayList<>();
        for (final HAProvider<HAResource> haProvider : haProviders) {
            if (haProvider.resourceType().equals(resourceType) && haProvider.resourceSubType().equals(entityType)) {
                haProviderNames.add(haProvider.getClass().getSimpleName());
            }
        }
        return haProviderNames;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(ConfigureHAForHostCmd.class);
        cmdList.add(EnableHAForHostCmd.class);
        cmdList.add(EnableHAForClusterCmd.class);
        cmdList.add(EnableHAForZoneCmd.class);
        cmdList.add(DisableHAForHostCmd.class);
        cmdList.add(DisableHAForClusterCmd.class);
        cmdList.add(DisableHAForZoneCmd.class);
        cmdList.add(EnableBalancingClusterCmd.class);
        cmdList.add(DisableBalancingClusterCmd.class);
        cmdList.add(ListHostHAResourcesCmd.class);
        cmdList.add(ListHostHAProvidersCmd.class);
        return cmdList;
    }

    //////////////////////////////////////////////////////
    //////////////// Event Listeners /////////////////////
    //////////////////////////////////////////////////////

    @Override
    public void onManagementNodeJoined(List<? extends ManagementServerHost> nodeList, long selfNodeId) {
    }

    @Override
    public void onManagementNodeLeft(List<? extends ManagementServerHost> nodeList, long selfNodeId) {
    }

    @Override
    public void onManagementNodeIsolated() {
    }

    private boolean processHAStateChange(final HAConfig haConfig, final HAConfig.HAState newState, final boolean status) {
        if (!status || !checkHAOwnership(haConfig)) {
            return false;
        }

        final HAResource resource = validateAndFindHAResource(haConfig);
        if (resource == null) {
            return false;
        }

        final HAProvider<HAResource> haProvider = validateAndFindHAProvider(haConfig, resource);
        if (haProvider == null) {
            return false;
        }

        final HAResourceCounter counter = getHACounter(haConfig.getResourceId(), haConfig.getResourceType());

        // Perform activity checks
        if (newState == HAConfig.HAState.Checking) {
            final ActivityCheckTask job = ComponentContext.inject(new ActivityCheckTask(resource, haProvider, haConfig,
                    HAProviderConfig.ActivityCheckTimeout, activityCheckExecutor, counter.getSuspectTimeStamp()));
            activityCheckExecutor.submit(job);
        }

        // Attempt recovery
        if (newState == HAConfig.HAState.Recovering) {
            if (counter.getRecoveryCounter() >= (Long) (haProvider.getConfigValue(HAProviderConfig.MaxRecoveryAttempts, resource))) {
                return false;
            }
            final RecoveryTask task = ComponentContext.inject(new RecoveryTask(resource, haProvider, haConfig,
                    HAProviderConfig.RecoveryTimeout, recoveryExecutor));
            final Future<Boolean> recoveryFuture = recoveryExecutor.submit(task);
            counter.setRecoveryFuture(recoveryFuture);
        }

        // Fencing
        if (newState == HAConfig.HAState.Fencing) {
            final FenceTask task = ComponentContext.inject(new FenceTask(resource, haProvider, haConfig,
                    HAProviderConfig.FenceTimeout, fenceExecutor));
            final Future<Boolean> fenceFuture = fenceExecutor.submit(task);
            counter.setFenceFuture(fenceFuture);
        }
        return true;
    }

    @Override
    public boolean preStateTransitionEvent(final HAConfig.HAState oldState, final HAConfig.Event event, final HAConfig.HAState newState, final HAConfig haConfig, final boolean status, final Object opaque) {
        if (oldState != newState || newState == HAConfig.HAState.Suspect || newState == HAConfig.HAState.Checking) {
            return false;
        }

        LOG.debug(String.format("HA state pre-transition:: new state=[%s], old state=[%s], for resource id=[%s], status=[%s], ha config state=[%s]." , newState, oldState, haConfig.getResourceId(), status, haConfig.getState()));

        if (status && haConfig.getState() != newState) {
            LOG.warn(String.format("HA state pre-transition:: HA state is not equal to transition state, HA state=[%s], new state=[%s].", haConfig.getState(), newState));
        }
        return processHAStateChange(haConfig, newState, status);
    }

    @Override
    public boolean postStateTransitionEvent(final StateMachine2.Transition<HAConfig.HAState, HAConfig.Event> transition, final HAConfig haConfig, final boolean status, final Object opaque) {
        LOG.debug(String.format("HA state post-transition:: new state=[%s], old state=[%s], for resource id=[%s], status=[%s], ha config state=[%s].", transition.getToState(), transition.getCurrentState(),  haConfig.getResourceId(), status, haConfig.getState()));

        if (status && haConfig.getState() != transition.getToState()) {
            LOG.warn(String.format("HA state post-transition:: HA state is not equal to transition state, HA state=[%s], new state=[%s].", haConfig.getState(), transition.getToState()));
        }
        return processHAStateChange(haConfig, transition.getToState(), status);
    }

    ///////////////////////////////////////////////////
    //////////////// Manager Init /////////////////////
    ///////////////////////////////////////////////////

    @Override
    public boolean start() {
        haProviderMap.clear();
        for (final HAProvider<HAResource> haProvider : haProviders) {
            haProviderMap.put(haProvider.getClass().getSimpleName().toLowerCase(), haProvider);
        }
        return true;
    }

    @Override
    public boolean stop() {
        haConfigDao.expireServerOwnership(ManagementServerNode.getManagementServerId());
        return true;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        // Health Check
        final int healthCheckWorkers = MaxConcurrentHealthCheckOperations.value();
        final int healthCheckQueueSize = MaxPendingHealthCheckOperations.value();
        healthCheckExecutor = new ThreadPoolExecutor(healthCheckWorkers, healthCheckWorkers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(healthCheckQueueSize, true), new ThreadPoolExecutor.CallerRunsPolicy());

        // Activity Check
        final int activityCheckWorkers = MaxConcurrentActivityCheckOperations.value();
        final int activityCheckQueueSize = MaxPendingActivityCheckOperations.value();
        activityCheckExecutor = new ThreadPoolExecutor(activityCheckWorkers, activityCheckWorkers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(activityCheckQueueSize, true), new ThreadPoolExecutor.CallerRunsPolicy());

        // Recovery
        final int recoveryOperationWorkers = MaxConcurrentRecoveryOperations.value();
        final int recoveryOperationQueueSize = MaxPendingRecoveryOperations.value();
        recoveryExecutor = new ThreadPoolExecutor(recoveryOperationWorkers, recoveryOperationWorkers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(recoveryOperationQueueSize, true), new ThreadPoolExecutor.CallerRunsPolicy());

        // Fence
        final int fenceOperationWorkers = MaxConcurrentFenceOperations.value();
        final int fenceOperationQueueSize = MaxPendingFenceOperations.value();
        fenceExecutor = new ThreadPoolExecutor(fenceOperationWorkers, fenceOperationWorkers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(fenceOperationQueueSize, true), new ThreadPoolExecutor.CallerRunsPolicy());

        pollManager.submitTask(new HAManagerBgPollTask());
        HAConfig.HAState.getStateMachine().registerListener(this);

        LOG.debug("HA manager has been configured.");
        return true;
    }

    public void setHaProviders(List<HAProvider<HAResource>> haProviders) {
        this.haProviders = haProviders;
    }

    @Override
    public String getConfigComponentName() {
        return HAManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                MaxConcurrentHealthCheckOperations,
                MaxPendingHealthCheckOperations,
                MaxConcurrentActivityCheckOperations,
                MaxPendingActivityCheckOperations,
                MaxConcurrentRecoveryOperations,
                MaxPendingRecoveryOperations,
                MaxConcurrentFenceOperations,
                MaxPendingFenceOperations
        };
    }

    /////////////////////////////////////////////////
    //////////////// Poll Tasks /////////////////////
    /////////////////////////////////////////////////

    private final class HAManagerBgPollTask extends ManagedContextRunnable implements BackgroundPollTask {
        @Override
        protected void runInContext() {
            HAConfig currentHaConfig = null;

            try {
                LOG.debug("HA health check task is running...");

                final List<HAConfig> haConfigList = new ArrayList<HAConfig>(haConfigDao.listAll());
                for (final HAConfig haConfig : haConfigList) {
                    currentHaConfig = haConfig;

                    if (haConfig == null) {
                        continue;
                    }

                    if (!checkHAOwnership(haConfig)) {
                        continue;
                    }

                    final HAResource resource = validateAndFindHAResource(haConfig);
                    if (resource == null) {
                        continue;
                    }

                    final HAProvider<HAResource> haProvider = validateAndFindHAProvider(haConfig, resource);
                    if (haProvider == null) {
                        continue;
                    }

                    switch (haConfig.getState()) {
                        case Available:
                        case Suspect:
                        case Degraded:
                        case Fenced:
                            final HealthCheckTask task = ComponentContext.inject(new HealthCheckTask(resource, haProvider, haConfig,
                                    HAProviderConfig.HealthCheckTimeout, healthCheckExecutor));
                            healthCheckExecutor.submit(task);
                            break;
                    default:
                        break;
                    }

                    final HAResourceCounter counter = getHACounter(haConfig.getResourceId(), haConfig.getResourceType());

                    if (haConfig.getState() == HAConfig.HAState.Suspect) {
                        if (counter.canPerformActivityCheck((Long)(haProvider.getConfigValue(HAProviderConfig.MaxActivityCheckInterval, resource)))) {
                            transitionHAState(HAConfig.Event.PerformActivityCheck, haConfig);
                        }
                    }

                    if (haConfig.getState() == HAConfig.HAState.Degraded) {
                        if (counter.canRecheckActivity((Long)(haProvider.getConfigValue(HAProviderConfig.MaxDegradedWaitTimeout, resource)))) {
                            transitionHAState(HAConfig.Event.PeriodicRecheckResourceActivity, haConfig);
                        }
                    }

                    if (haConfig.getState() == HAConfig.HAState.Recovering) {
                        if (counter.getRecoveryCounter() >= (Long) (haProvider.getConfigValue(HAProviderConfig.MaxRecoveryAttempts, resource))) {
                            transitionHAState(HAConfig.Event.RecoveryOperationThresholdExceeded, haConfig);
                        } else {
                            transitionHAState(HAConfig.Event.RetryRecovery, haConfig);
                        }
                    }

                    if (haConfig.getState() == HAConfig.HAState.Recovered) {
                        counter.markRecoveryStarted();
                        if (counter.canExitRecovery((Long)(haProvider.getConfigValue(HAProviderConfig.RecoveryWaitTimeout, resource)))) {
                            if (transitionHAState(HAConfig.Event.RecoveryWaitPeriodTimeout, haConfig)) {
                                counter.markRecoveryCompleted();
                            }
                        }
                    }

                    if (haConfig.getState() == HAConfig.HAState.Fencing && counter.canAttemptFencing()) {
                        transitionHAState(HAConfig.Event.RetryFencing, haConfig);
                    }
                }
            } catch (Throwable t) {
                if (currentHaConfig != null) {
                    LOG.error(String.format("Error trying to perform health checks in HA manager [%s].", currentHaConfig.getHaProvider()), t);
                } else {
                    LOG.error("Error trying to perform health checks in HA manager.", t);
                }
            }
        }

        @Override
        public Long getDelay() {
            return null;
        }
    }
}
