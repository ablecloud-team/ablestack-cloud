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
package com.cloud.desktop.cluster;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.user.desktop.cluster.AddDesktopClusterIpRangeCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.DeleteDesktopClusterIpRangeCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.ListDesktopClusterCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.ListDesktopClusterIpRangeCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.CreateDesktopClusterCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.DeleteDesktopClusterCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.StartDesktopClusterCmd;
import org.apache.cloudstack.api.command.user.desktop.cluster.StopDesktopClusterCmd;
import org.apache.cloudstack.api.response.DesktopClusterResponse;
import org.apache.cloudstack.api.response.DesktopClusterIpRangeResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.cloud.api.ApiDBUtils;
import org.apache.cloudstack.api.ApiConstants.VMDetails;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.desktop.cluster.actionworkers.DesktopClusterDestroyWorker;
import com.cloud.desktop.cluster.actionworkers.DesktopClusterStartWorker;
import com.cloud.desktop.cluster.actionworkers.DesktopClusterStopWorker;
import com.cloud.desktop.version.dao.DesktopControllerVersionDao;
import com.cloud.desktop.version.dao.DesktopTemplateMapDao;
import com.cloud.desktop.cluster.dao.DesktopClusterDao;
import com.cloud.desktop.cluster.dao.DesktopClusterIpRangeDao;
import com.cloud.desktop.cluster.dao.DesktopClusterVmMapDao;
import com.cloud.desktop.version.DesktopControllerVersionVO;
import com.cloud.desktop.version.DesktopTemplateMapVO;
import com.cloud.desktop.version.DesktopControllerVersion;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Network.GuestType;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.event.ActionEvent;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
//import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.Ternary;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.component.ManagerBase;

import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;

public class DesktopClusterManagerImpl extends ManagerBase implements DesktopClusterService {

    private static final Logger LOGGER = Logger.getLogger(DesktopClusterManagerImpl.class);

    protected StateMachine2<DesktopCluster.State, DesktopCluster.Event, DesktopCluster> _stateMachine = DesktopCluster.State.getStateMachine();

    ScheduledExecutorService _gcExecutor;
    ScheduledExecutorService _stateScanner;

    @Inject
    public DesktopClusterDao desktopClusterDao;
    @Inject
    public DesktopClusterIpRangeDao desktopClusterIpRangeDao;
    @Inject
    public DesktopClusterVmMapDao desktopClusterVmMapDao;
    @Inject
    public DesktopControllerVersionDao desktopControllerVersionDao;
    @Inject
    public DesktopTemplateMapDao desktopTemplateMapDao;
    @Inject
    protected AccountManager accountManager;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected AccountService accountService;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected UserVmJoinDao userVmJoinDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected TemplateDataStoreDao _tmplStoreDao;
    @Inject
    protected NetworkService networkService;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected ResourceTagDao resourceTagDao;
    @Inject
    protected NetworkModel networkModel;

    private void logMessage(final Level logLevel, final String message, final Exception e) {
        if (logLevel == Level.WARN) {
            if (e != null) {
                LOGGER.warn(message, e);
            } else {
                LOGGER.warn(message);
            }
        } else {
            if (e != null) {
                LOGGER.error(message, e);
            } else {
                LOGGER.error(message);
            }
        }
    }

    private void logTransitStateAndThrow(final Level logLevel, final String message, final Long desktopClusterId, final DesktopCluster.Event event, final Exception e) throws CloudRuntimeException {
        logMessage(logLevel, message, e);
        if (desktopClusterId != null && event != null) {
            stateTransitTo(desktopClusterId, event);
        }
        if (e == null) {
            throw new CloudRuntimeException(message);
        }
        throw new CloudRuntimeException(message, e);
    }

    private void logAndThrow(final Level logLevel, final String message) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, null);
    }

    private void logAndThrow(final Level logLevel, final String message, final Exception ex) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, ex);
    }

    @Override
    public DesktopClusterResponse createDesktopClusterResponse(long desktopCusterId) {
        DesktopClusterVO desktop = desktopClusterDao.findById(desktopCusterId);
        DesktopClusterResponse response = new DesktopClusterResponse();
        response.setObjectName(DesktopCluster.class.getSimpleName().toLowerCase());
        response.setId(desktop.getUuid());
        response.setName(desktop.getName());
        response.setDescription(desktop.getDescription());
        response.setAdDomainName(desktop.getAdDomainName());
        response.setState(desktop.getState().toString());
        response.setCreated(desktop.getCreated());

        DataCenterVO zone = ApiDBUtils.findZoneById(desktop.getZoneId());
        response.setZoneId(zone.getUuid());
        response.setZoneName(zone.getName());

        ServiceOfferingVO offering = serviceOfferingDao.findById(desktop.getServiceOfferingId());
        response.setServiceOfferingId(offering.getUuid());
        response.setServiceOfferingName(offering.getName());

        DesktopControllerVersionVO version = desktopControllerVersionDao.findById(desktop.getDesktopVersionId());
        if (version != null) {
            response.setControllerVersionName(version.getName());
            response.setControllerVersion(version.getVersion());
        }

        Account account = ApiDBUtils.findAccountById(desktop.getAccountId());
        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }

        Domain domain = ApiDBUtils.findDomainById(desktop.getDomainId());
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());

        NetworkVO ntwk = networkDao.findByIdIncludingRemoved(desktop.getNetworkId());
        response.setNetworkId(ntwk.getUuid());
        response.setAssociatedNetworkName(ntwk.getName());
        response.setNetworkType(ntwk.getGuestType());

        List<UserVmResponse> controlVmResponses = new ArrayList<UserVmResponse>();
        List<UserVmResponse> desktopVmResponses = new ArrayList<UserVmResponse>();
        List<DesktopClusterVmMapVO> controlVmList = desktopClusterVmMapDao.listByDesktopClusterIdAndNotVmType(desktop.getId(), "desktopvm");

        ResponseView respView = ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountService.isRootAdmin(caller.getId())) {
            respView = ResponseView.Full;
        }
        String responseName = "controlvmlist";
        if (controlVmList != null && !controlVmList.isEmpty()) {
            for (DesktopClusterVmMapVO vmMapVO : controlVmList) {
                UserVmJoinVO userVM = userVmJoinDao.findById(vmMapVO.getVmId());
                if (userVM != null) {
                    UserVmResponse cvmResponse = ApiDBUtils.newUserVmResponse(respView, responseName, userVM, EnumSet.of(VMDetails.nics), caller);
                    controlVmResponses.add(cvmResponse);
                    if("worksvm".equals(vmMapVO.getType())) {
                        List<? extends IpAddress> addresses = networkModel.listPublicIpsAssignedToGuestNtwk(ntwk.getId(), true);
                        for (IpAddress address : addresses) {
                            if (address.isSourceNat()) {
                                response.setWorksVmIp(address.getAddress().addr());
                            }
                        }
                    }
                    if("dcvm".equals(vmMapVO.getType())) {
                        response.setDcVmIp(userVM.getIpAddress());
                    }
                }
            }
        }

        List<VMInstanceVO> vmList = vmInstanceDao.listByZoneId(desktop.getZoneId());
        responseName = "desktopvmlist";
        String resourceKey = "ClusterName";
        if (vmList != null && !vmList.isEmpty()) {
            for (VMInstanceVO vmVO : vmList) {
                ResourceTag desktopvm = resourceTagDao.findByKey(vmVO.getId(), ResourceObjectType.UserVm, resourceKey);
                if (desktopvm != null) {
                    if (desktopvm.getValue().equals(desktop.getName())) {
                        UserVmJoinVO userVM = userVmJoinDao.findById(vmVO.getId());
                        if (userVM != null) {
                            UserVmResponse dvmResponse = ApiDBUtils.newUserVmResponse(respView, responseName, userVM, EnumSet.of(VMDetails.nics), caller);
                            desktopVmResponses.add(dvmResponse);
                        }
                    }
                }
            }
        }

        response.setControlVms(controlVmResponses);
        response.setDesktopVms(desktopVmResponses);
        return response;
    }

    @Override
    public ListResponse<DesktopClusterResponse> listDesktopCluster(ListDesktopClusterCmd cmd) {
        if (!DesktopServiceEnabled.value()) {
            logAndThrow(Level.ERROR, "Desktop Service plugin is disabled");
        }
        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        final Long desktopClusterId = cmd.getId();
        final String state = cmd.getState();
        final String name = cmd.getName();
        final String keyword = cmd.getKeyword();
        List<DesktopClusterResponse> responsesList = new ArrayList<DesktopClusterResponse>();
        List<Long> permittedAccounts = new ArrayList<Long>();
        Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, Project.ListProjectResourcesCriteria>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, desktopClusterId, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(DesktopClusterVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<DesktopClusterVO> sb = desktopClusterDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.IN);
        SearchCriteria<DesktopClusterVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (state != null) {
            sc.setParameters("state", state);
        }
        if (keyword != null){
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        if (desktopClusterId != null) {
            sc.setParameters("id", desktopClusterId);
        }
        if (name != null) {
            sc.setParameters("name", name);
        }
        List<DesktopClusterVO> desktop = desktopClusterDao.search(sc, searchFilter);
        for (DesktopClusterVO cluster : desktop) {
            DesktopClusterResponse desktopClusterResponse = createDesktopClusterResponse(cluster.getId());
            responsesList.add(desktopClusterResponse);
        }
        ListResponse<DesktopClusterResponse> response = new ListResponse<DesktopClusterResponse>();
        response.setResponses(responsesList);
        return response;
    }

    @Override
    public DesktopClusterIpRangeResponse addDesktopClusterIpRangeResponse(long ipRangeId) {
        DesktopClusterIpRangeVO desktopIp = desktopClusterIpRangeDao.findById(ipRangeId);
        DesktopClusterIpRangeResponse response = new DesktopClusterIpRangeResponse();
        response.setObjectName(DesktopClusterIpRange.class.getSimpleName().toLowerCase());
        response.setId(desktopIp.getUuid());
        response.setGateway(desktopIp.getGateway());
        response.setNetmask(desktopIp.getNetmask());
        response.setStartIp(desktopIp.getStartIp());
        response.setEndIp(desktopIp.getEndIp());
        DesktopClusterVO desktop = desktopClusterDao.findById(desktopIp.getDesktopClusterId());
        if (desktop != null) {
            response.setDesktopClusterName(desktop.getName());
            NetworkVO desktopNetwork = networkDao.findByIdIncludingRemoved(desktop.getNetworkId());
            response.setNetworkId(desktopNetwork.getUuid());
            response.setAssociatedNetworkName(desktopNetwork.getName());
        }
        return response;
    }

    @Override
    public ListResponse<DesktopClusterIpRangeResponse> listDesktopClusterIpRanges(ListDesktopClusterIpRangeCmd cmd) {
        if (!DesktopServiceEnabled.value()) {
            logAndThrow(Level.ERROR, "Desktop Service plugin is disabled");
        }
        final Long rangeId = cmd.getId();
        final Long desktopClusterId = cmd.getDesktopClusterId();
        Filter searchFilter = new Filter(DesktopClusterIpRangeVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<DesktopClusterIpRangeVO> sb = desktopClusterIpRangeDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("desktopClusterId", sb.entity().getDesktopClusterId(), SearchCriteria.Op.EQ);
        SearchCriteria<DesktopClusterIpRangeVO> sc = sb.create();
        if (rangeId != null) {
            sc.setParameters("id", rangeId);
        }
        if (desktopClusterId != null) {
            sc.setParameters("desktopClusterId", desktopClusterId);
        }
        List<DesktopClusterIpRangeResponse> responsesList = new ArrayList<DesktopClusterIpRangeResponse>();
        List<DesktopClusterIpRangeVO> id = desktopClusterIpRangeDao.search(sc, searchFilter);
        for (DesktopClusterIpRangeVO ip : id) {
            DesktopClusterIpRangeResponse desktopClusterIpRangeResponse = addDesktopClusterIpRangeResponse(ip.getId());
            responsesList.add(desktopClusterIpRangeResponse);
        }
        ListResponse<DesktopClusterIpRangeResponse> response = new ListResponse<DesktopClusterIpRangeResponse>();
        response.setResponses(responsesList);
        return response;
    }

    @Override
    @ActionEvent(eventType = DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_IP_RANGE_ADD, eventDescription = "Adding Desktop cluster ip range")
    public DesktopClusterIpRange addDesktopClusterIpRange(final AddDesktopClusterIpRangeCmd cmd) {
        if (!DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        boolean ipv4 = false;
        final String gateway = cmd.getGateway();
        final String netmask = cmd.getNetmask();
        final String startIp = cmd.getStartIp();
        final String endIp = cmd.getEndIp();

        if (startIp != null) {
            ipv4 = true;
        }
        if (!ipv4) {
            throw new InvalidParameterValueException("Please specify IP address.");
        }
        if (ipv4) {
            // Make sure the gateway is valid
            if (!NetUtils.isValidIp4(gateway)) {
                throw new InvalidParameterValueException("Please specify a valid gateway");
            }

            // Make sure the netmask is valid
            if (!NetUtils.isValidIp4Netmask(netmask)) {
                throw new InvalidParameterValueException("Please specify a valid netmask");
            }

            final String newCidr = NetUtils.getCidrFromGatewayAndNetmask(gateway, netmask);

            //Make sure start and end ips are with in the range of cidr calculated for this gateway and netmask {
            if (!NetUtils.isIpWithInCidrRange(gateway, newCidr) || !NetUtils.isIpWithInCidrRange(startIp, newCidr) || !NetUtils.isIpWithInCidrRange(endIp, newCidr)) {
                throw new InvalidParameterValueException("Please specify a valid IP range or valid netmask or valid gateway");
            }

            final List<DesktopClusterIpRangeVO> ips = desktopClusterIpRangeDao.listAll();
            for (final DesktopClusterIpRangeVO range : ips) {
                final String otherGateway = range.getGateway();
                final String otherNetmask = range.getNetmask();
                final String otherStartIp = range.getStartIp();
                final String otherEndIp = range.getEndIp();

                // Continue if it's not IPv4
                if ( otherGateway == null || otherNetmask == null ) {
                    continue;
                }
                final String otherCidr = NetUtils.getCidrFromGatewayAndNetmask(otherGateway, otherNetmask);
                if( !NetUtils.isNetworksOverlap(newCidr,  otherCidr)) {
                    continue;
                }

                // extend IP range
                if (!gateway.equals(otherGateway) || !netmask.equals(range.getNetmask())) {
                    throw new InvalidParameterValueException("The IP range has already been added with gateway "
                            + otherGateway + " ,and netmask " + otherNetmask
                            + ", Please specify the gateway/netmask if you want to extend ip range" );
                }
                if (!NetUtils.is31PrefixCidr(newCidr)) {
                    if (NetUtils.ipRangesOverlap(startIp, endIp, otherStartIp, otherEndIp)) {
                        throw new InvalidParameterValueException("The IP range already has IPs that overlap with the new range." +
                                " Please specify a different start IP/end IP.");
                    }
                }
            }
        }

        DesktopClusterVO desktopCluster = desktopClusterDao.findById(cmd.getDesktopClusterId());

        if (desktopCluster == null) {
            throw new InvalidParameterValueException("Invalid desktop cluster specified");
        }
        Long desktopClusterId = desktopCluster.getId();

        final DesktopClusterIpRangeVO cluster = Transaction.execute(new TransactionCallback<DesktopClusterIpRangeVO>() {
            @Override
            public DesktopClusterIpRangeVO doInTransaction(TransactionStatus status) {
                DesktopClusterIpRangeVO newCluster = new DesktopClusterIpRangeVO(desktopClusterId, gateway, netmask, startIp, endIp);
                desktopClusterIpRangeDao.persist(newCluster);
                return newCluster;
            }
        });

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Desktop cluster ID: %s has been created", cluster.getUuid()));
        }
        return cluster;
    }

    @Override
    @ActionEvent(eventType = DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_IP_RANGE_DELETE, eventDescription = "Deleting Desktop cluster ip range")
    public boolean deleteDesktopClusterIpRange(final DeleteDesktopClusterIpRangeCmd cmd) {
        if (!DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long ipRangeId = cmd.getId();
        DesktopClusterIpRange iprange = desktopClusterIpRangeDao.findById(ipRangeId);
        if (iprange == null) {
            throw new InvalidParameterValueException("Invalid Desktop cluster ip range id specified");
        }

        return desktopClusterIpRangeDao.remove(iprange.getId());
    }

    protected boolean stateTransitTo(long desktopClusterId, DesktopCluster.Event e) {
        DesktopClusterVO desktopCluster = desktopClusterDao.findById(desktopClusterId);
        try {
            return _stateMachine.transitTo(desktopCluster, e, null, desktopClusterDao);
        } catch (NoTransitionException nte) {
            LOGGER.warn(String.format("Failed to transition state of the Desktop cluster : %s in state %s on event %s", desktopCluster.getName(), desktopCluster.getState().toString(), e.toString()), nte);
            return false;
        }
    }

    @Override
    public DesktopCluster createDesktopCluster(CreateDesktopClusterCmd cmd) throws CloudRuntimeException {
        if (!DesktopServiceEnabled.value()) {
            logAndThrow(Level.ERROR, "Desktop Service plugin is disabled");
        }

        validateDesktopClusterCreateParameters(cmd);

        final String L2Type = "internal";
        final ServiceOffering serviceOffering = serviceOfferingDao.findById(cmd.getServiceOfferingId());
        final Account owner = accountService.getActiveAccountById(cmd.getEntityOwnerId());
        final DesktopControllerVersion clusterDesktopVersion = desktopControllerVersionDao.findById(cmd.getControllerVersion());
        final DesktopClusterVO cluster = Transaction.execute(new TransactionCallback<DesktopClusterVO>() {
            @Override
            public DesktopClusterVO doInTransaction(TransactionStatus status) {
                DesktopClusterVO newCluster = new DesktopClusterVO(cmd.getName(), cmd.getDescription(), clusterDesktopVersion.getZoneId(), clusterDesktopVersion.getId(),
                        serviceOffering.getId(), cmd.getAdDomainName(), cmd.getNetworkId(), cmd.getAccessType(),
                        owner.getDomainId(), owner.getAccountId(), DesktopCluster.State.Created, cmd.getDcIp(), cmd.getWorksIp());
                desktopClusterDao.persist(newCluster);
                if(cmd.getAccessType().equals(L2Type)){
                    addDesktopClusterIpRangeInDeployCluster(newCluster, cmd);
                }
                return newCluster;
            }
        });
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Desktop cluster name: %s and ID: %s has been created", cluster.getName(), cluster.getUuid()));
        }
        return cluster;
    }

    private void validateDesktopClusterCreateParameters(final CreateDesktopClusterCmd cmd) throws CloudRuntimeException {
        final String name = cmd.getName();
        final String description = cmd.getDescription();
        final String adDomainName = cmd.getAdDomainName();
        // final String password = cmd.getPassword();
        final Long desktopVersionId = cmd.getControllerVersion();
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        final Long networkId = cmd.getNetworkId();

        if (name == null || name.isEmpty()) {
            throw new InvalidParameterValueException("Invalid name for the Desktop cluster name:" + name);
        }
        if (!NetUtils.verifyDomainNameLabel(name, true) || name.length() > 8) {
            throw new InvalidParameterValueException("Invalid name. desktop cluster name can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                    + "and the hyphen ('-'), must be between 1 and 8 characters long, and can't start or end with \"-\" and can't start with digit");
        }
        final List<DesktopClusterVO> clusters = desktopClusterDao.listAll();
        for (final DesktopClusterVO cluster : clusters) {
            final String otherName = cluster.getName();
            final String otherAdDomainName = cluster.getAdDomainName();
            final Long otherNetwork = cluster.getNetworkId();
            if (otherName.equals(name)) {
                throw new InvalidParameterValueException("cluster name '" + name + "' already exists.");
            }
            if (otherAdDomainName.equals(adDomainName)) {
                throw new InvalidParameterValueException("cluster ad domain name '" + adDomainName + "' already exists.");
            }
            if (otherNetwork.equals(networkId)){
                throw new InvalidParameterValueException("cluster network id '" + networkId + "' already cluster deployed.");
            }
        }
        if (description == null || description.isEmpty()) {
            throw new InvalidParameterValueException("Invalid description for the Desktop cluster description:" + description);
        }
        if (adDomainName == null || adDomainName.isEmpty()) {
            throw new InvalidParameterValueException("Invalid AD Domain Name for the Desktop cluster AD Domain name:" + adDomainName);
        } else {
            if (adDomainName.contains(".")) {
                throw new InvalidParameterValueException("AD domain name is fixed in *.local format, '.' cannot be used.");
            }
            if (!NetUtils.verifyDomainNameLabel(adDomainName, true)) {
                throw new InvalidParameterValueException("Invalid AD domain name. desktop cluster AD domain name can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                        + "and the hyphen ('-'), must be between 1 and 63 characters long, and can't start or end with \"-\" and can't start with digit");
            }
        }
        // if (password == null || password.isEmpty()) {
        //     throw new InvalidParameterValueException("Invalid password for the Desktop cluster password:" + password);
        // }

        final DesktopControllerVersion clusterDesktopVersion = desktopControllerVersionDao.findById(desktopVersionId);
        if (clusterDesktopVersion == null) {
            throw new InvalidParameterValueException("Unable to find given Desktop version in supported versions");
        }
        if (!DesktopControllerVersion.State.Enabled.equals(clusterDesktopVersion.getState())) {
            throw new InvalidParameterValueException(String.format("Desktop version ID: %s is in %s state", clusterDesktopVersion.getUuid(), clusterDesktopVersion.getState()));
        }

        TemplateDataStoreVO tmpltStoreRef = null;
        List<DesktopTemplateMapVO> templateList = desktopTemplateMapDao.listByVersionId(clusterDesktopVersion.getId());
        if (templateList != null && !templateList.isEmpty()) {
            for (DesktopTemplateMapVO templateMapVO : templateList) {
                tmpltStoreRef = _tmplStoreDao.findByStoreTemplate(clusterDesktopVersion.getZoneId(), templateMapVO.getTemplateId());
                if (tmpltStoreRef != null) {
                    if (tmpltStoreRef.getDownloadState() != VMTemplateStorageResourceAssoc.Status.DOWNLOADED) {
                        throw new InvalidParameterValueException("Unable to deploy cluster, Desktop Controller Template " + templateMapVO.getTemplateId() + " has not been completely downloaded to zone " + clusterDesktopVersion.getZoneId());
                    }
                }
            }
        }

        DataCenter zone = dataCenterDao.findById(clusterDesktopVersion.getZoneId());
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by ID: " + clusterDesktopVersion.getZoneId());
        }
        if (Grouping.AllocationState.Disabled == zone.getAllocationState()) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, zone ID: %s is currently disabled", zone.getUuid()));
        }
        if (clusterDesktopVersion.getZoneId() != null && !clusterDesktopVersion.getZoneId().equals(zone.getId())) {
            throw new InvalidParameterValueException(String.format("Desktop version ID: %s is not available for zone ID: %s", clusterDesktopVersion.getUuid(), zone.getUuid()));
        }
        if (clusterDesktopVersion.getZoneId() != null && clusterDesktopVersion.getZoneId() != zone.getId()) {
            throw new InvalidParameterValueException(String.format("Desktop version ID: %s is not available for zone ID: %s", clusterDesktopVersion.getUuid(), zone.getUuid()));
        }

        ServiceOffering serviceOffering = serviceOfferingDao.findById(serviceOfferingId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException("No service offering with ID: " + serviceOfferingId);
        }

        Network network = null;
        if (networkId != null) {
            network = networkService.getNetwork(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find network with given ID");
            }
            final String dcIp = cmd.getDcIp();
            final String worksIp = cmd.getWorksIp();
            final String cider = network.getCidr();
            if (dcIp == null || dcIp.isEmpty()) {
                throw new InvalidParameterValueException("Invalid IP for the Desktop cluster DC VM IP:" + dcIp);
            }
            if (worksIp == null || worksIp.isEmpty()) {
                throw new InvalidParameterValueException("Invalid IP for the Desktop cluster Works VM IP:" + worksIp);
            }
            if (network.getGuestType().equals(GuestType.L2)){
                //L2 일 경우 IP 범위 조회하여 벨리데이션 체크
                final String gateway = cmd.getGateway();
                final String netmask = cmd.getNetmask();
                final String startIp = cmd.getStartIp();
                final String endIp = cmd.getEndIp();

                if (gateway == null || gateway.isEmpty()) {
                    throw new InvalidParameterValueException("Invalid gateway for the Desktop cluster gateway:" + gateway);
                }
                if (netmask == null || netmask.isEmpty()) {
                    throw new InvalidParameterValueException("Invalid netmask for the Desktop cluster netmask:" + netmask);
                }
                if (startIp == null || startIp.isEmpty()) {
                    throw new InvalidParameterValueException("Invalid startIp for the Desktop cluster nastartIpme:" + startIp);
                }
                if (endIp == null || endIp.isEmpty()) {
                    throw new InvalidParameterValueException("Invalid endIp for the Desktop cluster endIp:" + endIp);
                }
                if (!NetUtils.isValidIp4(gateway)) {
                    throw new InvalidParameterValueException("Please specify a valid gateway");
                }
                if (!NetUtils.isValidIp4Netmask(netmask)) {
                    throw new InvalidParameterValueException("Please specify a valid netmask");
                }
                final String newCidr = NetUtils.getCidrFromGatewayAndNetmask(gateway, netmask);
                if (!NetUtils.isIpWithInCidrRange(gateway, newCidr) || !NetUtils.isIpWithInCidrRange(startIp, newCidr) || !NetUtils.isIpWithInCidrRange(endIp, newCidr)) {
                    throw new InvalidParameterValueException("Please specify a valid IP range or valid netmask or valid gateway");
                }
                final List<DesktopClusterIpRangeVO> ips = desktopClusterIpRangeDao.listAll();
                for (final DesktopClusterIpRangeVO range : ips) {
                    final String otherGateway = range.getGateway();
                    final String otherNetmask = range.getNetmask();
                    final String otherStartIp = range.getStartIp();
                    final String otherEndIp = range.getEndIp();
                    if ( otherGateway == null || otherNetmask == null ) {
                        continue;
                    }
                    final String otherCidr = NetUtils.getCidrFromGatewayAndNetmask(otherGateway, otherNetmask);
                    if( !NetUtils.isNetworksOverlap(newCidr,  otherCidr)) {
                        continue;
                    }
                    if (!gateway.equals(otherGateway) || !netmask.equals(range.getNetmask())) {
                        throw new InvalidParameterValueException("The IP range has already been added with gateway "
                                + otherGateway + " ,and netmask " + otherNetmask
                                + ", Please specify the gateway/netmask if you want to extend ip range" );
                    }
                    if (!NetUtils.is31PrefixCidr(newCidr)) {
                        if (NetUtils.ipRangesOverlap(startIp, endIp, otherStartIp, otherEndIp)) {
                            throw new InvalidParameterValueException("The IP range already has IPs that overlap with the new range." +
                                    " Please specify a different start IP/end IP.");
                        }
                    }
                }
                //L2 일 경우 dc ip, works ip 입력된 경우 벨리데이션 체크
                if ((dcIp != null && !dcIp.isEmpty()) && (worksIp != null && !worksIp.isEmpty())) {
                    if (!NetUtils.isIpInRange(dcIp, startIp, endIp) || !NetUtils.isIpInRange(worksIp, startIp, endIp)) {
                        throw new InvalidParameterValueException("DC or Works VM IP provided is not within the specified range: " + startIp + " - " + endIp);
                    }
                    if (dcIp == worksIp) {
                        throw new InvalidParameterValueException("Please enter different Works IP and DC IP");
                    }
                }
            }
            if (network.getGuestType().equals(GuestType.Isolated) || network.getGuestType().equals(GuestType.Shared)) {
                //Isolated, Shared 일 경우 dc ip, works ip 입력된 경우 벨리데이션 체크
                if ((dcIp != null && !dcIp.isEmpty()) && (worksIp != null && !worksIp.isEmpty())) {
                    if (!NetUtils.isIpWithInCidrRange(dcIp, cider) || !NetUtils.isIpWithInCidrRange(worksIp, cider)) {
                        throw new InvalidParameterValueException("Please specify a valid IP range or valid netmask or valid gateway");
                    }
                    if (dcIp == worksIp) {
                        throw new InvalidParameterValueException("Please enter different Works IP and DC IP");
                    }
                }
            }
        }
    }

    private void addDesktopClusterIpRangeInDeployCluster(final DesktopCluster desktopCluster, final CreateDesktopClusterCmd cmd) {
        final long desktopClusterId = desktopCluster.getId();
        final String gateway = cmd.getGateway();
        final String netmask = cmd.getNetmask();
        final String startIp = cmd.getStartIp();
        final String endIp = cmd.getEndIp();
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                DesktopClusterIpRangeVO iprange = new DesktopClusterIpRangeVO(desktopClusterId, gateway, netmask, startIp, endIp);
                desktopClusterIpRangeDao.persist(iprange);
            }
        });
    }

    @Override
    public boolean startDesktopCluster(long desktopClusterId, boolean onCreate) throws CloudRuntimeException {
        if (!DesktopServiceEnabled.value()) {
            logAndThrow(Level.ERROR, "Desktop Service plugin is disabled");
        }
        final DesktopClusterVO desktopCluster = desktopClusterDao.findById(desktopClusterId);
        if (desktopCluster == null) {
            throw new InvalidParameterValueException("Failed to find Desktop cluster with given ID");
        }
        if (desktopCluster.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("Desktop cluster : %s is already deleted", desktopCluster.getName()));
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, desktopCluster);
        if (desktopCluster.getState().equals(DesktopCluster.State.Running)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Desktop cluster : %s is in running state", desktopCluster.getName()));
            }
            return true;
        }
        if (desktopCluster.getState().equals(DesktopCluster.State.Starting)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Desktop cluster : %s is already in starting state", desktopCluster.getName()));
            }
            return true;
        }
        final DesktopControllerVersion clusterDesktopVersion = desktopControllerVersionDao.findById(desktopCluster.getDesktopVersionId());
        final DataCenter zone = dataCenterDao.findById(clusterDesktopVersion.getZoneId());
        if (zone == null) {
            logAndThrow(Level.WARN, String.format("Unable to find zone for Desktop cluster : %s", desktopCluster.getName()));
        }
        DesktopClusterStartWorker startWorker =
                new DesktopClusterStartWorker(desktopCluster, this);
        startWorker = ComponentContext.inject(startWorker);
        if (onCreate) {
            // Start for Desktop cluster in 'Created' state
            return startWorker.startDesktopClusterOnCreate();
        } else {
            // Start for Desktop cluster in 'Stopped' state. Resources are already provisioned, just need to be started
            return startWorker.startStoppedDesktopCluster();
        }
    }

    @Override
    public boolean stopDesktopCluster(long desktopClusterId) throws CloudRuntimeException {
        if (!DesktopServiceEnabled.value()) {
            logAndThrow(Level.ERROR, "Desktop Service plugin is disabled");
        }
        final DesktopClusterVO desktopCluster = desktopClusterDao.findById(desktopClusterId);
        if (desktopCluster == null) {
            throw new InvalidParameterValueException("Failed to find Desktop cluster with given ID");
        }
        if (desktopCluster.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("Desktop cluster : %s is already deleted", desktopCluster.getName()));
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, desktopCluster);
        if (desktopCluster.getState().equals(DesktopCluster.State.Stopped)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Desktop cluster : %s is already stopped", desktopCluster.getName()));
            }
            return true;
        }
        if (desktopCluster.getState().equals(DesktopCluster.State.Stopping)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Desktop cluster : %s is getting stopped", desktopCluster.getName()));
            }
            return true;
        }
        DesktopClusterStopWorker stopWorker = new DesktopClusterStopWorker(desktopCluster, this);
        stopWorker = ComponentContext.inject(stopWorker);
        return stopWorker.stop();
    }

    @Override
    public boolean deleteDesktopCluster(Long desktopClusterId) throws CloudRuntimeException {
        if (!DesktopServiceEnabled.value()) {
            logAndThrow(Level.ERROR, "Desktop Service plugin is disabled");
        }
        DesktopClusterVO cluster = desktopClusterDao.findById(desktopClusterId);
        if (cluster == null) {
            throw new InvalidParameterValueException("Invalid cluster id specified");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, cluster);
        DesktopClusterDestroyWorker destroyWorker = new DesktopClusterDestroyWorker(cluster, this);
        destroyWorker = ComponentContext.inject(destroyWorker);
        return destroyWorker.destroy();
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!DesktopServiceEnabled.value()) {
            return cmdList;
        }

        cmdList.add(ListDesktopClusterCmd.class);
        cmdList.add(StartDesktopClusterCmd.class);
        cmdList.add(StopDesktopClusterCmd.class);
        cmdList.add(CreateDesktopClusterCmd.class);
        cmdList.add(DeleteDesktopClusterCmd.class);
        cmdList.add(ListDesktopClusterIpRangeCmd.class);
        cmdList.add(AddDesktopClusterIpRangeCmd.class);
        cmdList.add(DeleteDesktopClusterIpRangeCmd.class);
        return cmdList;
    }

    @Override
    public DesktopCluster findById(final Long id) {
        return desktopClusterDao.findById(id);
    }

    // Garbage collector periodically run through the Desktop clusters marked for GC. For each Desktop cluster
    // marked for GC, attempt is made to destroy cluster.
    public class DesktopClusterGarbageCollector extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            GlobalLock gcLock = GlobalLock.getInternLock("DesktopCluster.GC.Lock");
            try {
                if (gcLock.lock(3)) {
                    try {
                        reallyRun();
                    } finally {
                        gcLock.unlock();
                    }
                }
            } finally {
                gcLock.releaseRef();
            }
        }

        public void reallyRun() {
            try {
                List<DesktopClusterVO> desktopClusters = desktopClusterDao.findDesktopClustersToGarbageCollect();
                for (DesktopCluster desktopCluster : desktopClusters) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format("Running Desktop cluster garbage collector on Desktop cluster : %s", desktopCluster.getName()));
                    }
                    try {
                        DesktopClusterDestroyWorker destroyWorker = new DesktopClusterDestroyWorker(desktopCluster, DesktopClusterManagerImpl.this);
                        destroyWorker = ComponentContext.inject(destroyWorker);
                        if (destroyWorker.destroy()) {
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info(String.format("Garbage collection complete for Desktop cluster : %s", desktopCluster.getName()));
                            }
                        } else {
                            LOGGER.warn(String.format("Garbage collection failed for Desktop cluster : %s, it will be attempted to garbage collected in next run", desktopCluster.getName()));
                        }
                    } catch (CloudRuntimeException e) {
                        LOGGER.warn(String.format("Failed to destroy Desktop cluster : %s during GC", desktopCluster.getName()), e);
                        // proceed further with rest of the Desktop cluster garbage collection
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Caught exception while running Desktop cluster gc: ", e);
            }
        }
    }

    /* Desktop cluster scanner checks if the Desktop cluster is in desired state. If it detects Desktop cluster
       is not in desired state, it will trigger an event and marks the Desktop cluster to be 'Alert' state. For e.g a
       Desktop cluster in 'Running' state should mean all the cluster of controller VM's in the custer should be running
       and the controller VM's is running. It is possible due to out of band changes by user or hosts going down,
       we may end up one or more VM's in stopped state. in which case scanner detects these changes and marks the cluster
       in 'Alert' state. Similarly cluster in 'Stopped' state means all the cluster VM's are in stopped state any mismatch
       in states should get picked up by Desktop cluster and mark the Desktop cluster to be 'Alert' state.
       Through recovery API, or reconciliation clusters in 'Alert' will be brought back to known good state or desired state.
     */
    public class DesktopClusterStatusScanner extends ManagedContextRunnable {
        private boolean firstRun = true;
        @Override
        protected void runInContext() {
            GlobalLock gcLock = GlobalLock.getInternLock("DesktopCluster.State.Scanner.Lock");
            try {
                if (gcLock.lock(3)) {
                    try {
                        reallyRun();
                    } finally {
                        gcLock.unlock();
                    }
                }
            } finally {
                gcLock.releaseRef();
            }
        }

        public void reallyRun() {
            try {
                // run through Desktop clusters in 'Running' state and ensure all the VM's are Running in the cluster
                List<DesktopClusterVO> runningDesktopClusters = desktopClusterDao.findDesktopClustersInState(DesktopCluster.State.Running);
                for (DesktopCluster desktopCluster : runningDesktopClusters) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format("Running Desktop cluster state scanner on Desktop cluster : %s",desktopCluster.getName()));
                    }
                    try {
                        if (!isClusterVMsInDesiredState(desktopCluster, VirtualMachine.State.Running)) {
                            stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.FaultsDetected);
                        }
                    } catch (Exception e) {
                        LOGGER.warn(String.format("Failed to run Desktop cluster Running state scanner on Desktop cluster : %s status scanner", desktopCluster.getName()), e);
                    }
                }

                // run through Desktop clusters in 'Stopped' state and ensure all the VM's are Stopped in the cluster
                List<DesktopClusterVO> stoppedDesktopClusters = desktopClusterDao.findDesktopClustersInState(DesktopCluster.State.Stopped);
                for (DesktopCluster desktopCluster : stoppedDesktopClusters) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format("Running Desktop cluster state scanner on Desktop cluster : %s for state: %s", desktopCluster.getName(), DesktopCluster.State.Stopped.toString()));
                    }
                    try {
                        if (!isClusterVMsInDesiredState(desktopCluster, VirtualMachine.State.Stopped)) {
                            stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.FaultsDetected);
                        }
                    } catch (Exception e) {
                        LOGGER.warn(String.format("Failed to run Desktop cluster Stopped state scanner on Desktop cluster : %s status scanner", desktopCluster.getName()), e);
                    }
                }

                // run through Desktop clusters in 'Alert' state and reconcile state as 'Running' if the VM's are running or 'Stopped' if VM's are stopped
                List<DesktopClusterVO> alertDesktopClusters = desktopClusterDao.findDesktopClustersInState(DesktopCluster.State.Alert);
                for (DesktopClusterVO desktopCluster : alertDesktopClusters) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format("Running Desktop cluster state scanner on Desktop cluster : %s for state: %s", desktopCluster.getName(), DesktopCluster.State.Alert.toString()));
                    }
                    try {
                        if (isClusterVMsInDesiredState(desktopCluster, VirtualMachine.State.Running)) {
                            DesktopClusterStartWorker startWorker =
                                    new DesktopClusterStartWorker(desktopCluster, DesktopClusterManagerImpl.this);
                            startWorker = ComponentContext.inject(startWorker);
                            startWorker.reconcileAlertCluster();
                        } else if (isClusterVMsInDesiredState(desktopCluster, VirtualMachine.State.Stopped)) {
                            stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.StopRequested);
                            stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
                        }
                    } catch (Exception e) {
                        LOGGER.warn(String.format("Failed to run Desktop cluster Alert state scanner on Desktop cluster : %s status scanner", desktopCluster.getName()), e);
                    }
                }


                if (firstRun) {
                    // run through Desktop clusters in 'Starting' state and reconcile state as 'Alert' or 'Error' if the VM's are running
                    List<DesktopClusterVO> startingDesktopClusters = desktopClusterDao.findDesktopClustersInState(DesktopCluster.State.Starting);
                    for (DesktopCluster desktopCluster : startingDesktopClusters) {
                        if ((new Date()).getTime() - desktopCluster.getCreated().getTime() < 10*60*1000) {
                            continue;
                        }
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info(String.format("Running Desktop cluster state scanner on Desktop cluster : %s for state: %s", desktopCluster.getName(), DesktopCluster.State.Starting.toString()));
                        }
                        try {
                            if (isClusterVMsInDesiredState(desktopCluster, VirtualMachine.State.Running)) {
                                stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.FaultsDetected);
                            } else {
                                stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationFailed);
                            }
                        } catch (Exception e) {
                            LOGGER.warn(String.format("Failed to run Desktop cluster Starting state scanner on Desktop cluster : %s status scanner", desktopCluster.getName()), e);
                        }
                    }
                    List<DesktopClusterVO> destroyingDesktopClusters = desktopClusterDao.findDesktopClustersInState(DesktopCluster.State.Destroying);
                    for (DesktopCluster desktopCluster : destroyingDesktopClusters) {
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info(String.format("Running Desktop cluster state scanner on Desktop cluster : %s for state: %s", desktopCluster.getName(), DesktopCluster.State.Destroying.toString()));
                        }
                        try {
                            DesktopClusterDestroyWorker destroyWorker = new DesktopClusterDestroyWorker(desktopCluster, DesktopClusterManagerImpl.this);
                            destroyWorker = ComponentContext.inject(destroyWorker);
                            destroyWorker.destroy();
                        } catch (Exception e) {
                            LOGGER.warn(String.format("Failed to run Desktop cluster Destroying state scanner on Desktop cluster : %s status scanner", desktopCluster.getName()), e);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Caught exception while running Desktop cluster state scanner", e);
            }
            firstRun = false;
        }
    }

    // checks if Desktop cluster is in desired state
    boolean isClusterVMsInDesiredState(DesktopCluster desktopCluster, VirtualMachine.State state) {
        List<DesktopClusterVmMapVO> clusterVMs = desktopClusterVmMapDao.listByDesktopClusterIdAndNotVmType(desktopCluster.getId(), "desktopvm");

        // check if all the VM's are in same state
        for (DesktopClusterVmMapVO clusterVm : clusterVMs) {
            VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(clusterVm.getVmId());
            if (vm.getState() != state) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(String.format("Found VM : %s in the Desktop cluster : %s in state: %s while expected to be in state: %s. So moving the cluster to Alert state for reconciliation",
                            vm.getUuid(), desktopCluster.getName(), vm.getState().toString(), state.toString()));
                }
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean start() {
        _gcExecutor.scheduleWithFixedDelay(new DesktopClusterGarbageCollector(), 300, 300, TimeUnit.SECONDS);
        _stateScanner.scheduleWithFixedDelay(new DesktopClusterStatusScanner(), 300, 30, TimeUnit.SECONDS);

        return true;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _name = name;
        _configParams = params;
        _gcExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Desktop-Cluster-Scavenger"));
        _stateScanner = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Desktop-Cluster-State-Scanner"));

        return true;
    }

    @Override
    public String getConfigComponentName() {
        return DesktopClusterService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                DesktopServiceEnabled,
                DesktopWorksAdminPortalPort,
                DesktopWorksUserPortalPort
        };
    }
}
