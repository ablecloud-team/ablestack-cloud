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

package com.cloud.automation.controller;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Inject;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.automation.controller.actionworkers.AutomationControllerDestroyWorker;
import com.cloud.automation.controller.actionworkers.AutomationControllerStartWorker;
import com.cloud.automation.controller.actionworkers.AutomationControllerStopWorker;
import com.cloud.automation.controller.dao.AutomationControllerDao;
import com.cloud.automation.controller.dao.AutomationControllerVmMapDao;
import com.cloud.automation.resource.dao.AutomationDeployedResourceDao;
import com.cloud.automation.version.AutomationControllerVersion;
import com.cloud.automation.version.AutomationControllerVersionVO;
import com.cloud.automation.version.dao.AutomationControllerVersionDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.projects.Project;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.GuestOS;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.user.automation.controller.AddAutomationControllerCmd;
import org.apache.cloudstack.api.command.user.automation.controller.DeleteAutomationControllerCmd;
import org.apache.cloudstack.api.command.user.automation.controller.ListAutomationControllerCmd;
import org.apache.cloudstack.api.command.user.automation.controller.StartAutomationControllerCmd;
import org.apache.cloudstack.api.command.user.automation.controller.StopAutomationControllerCmd;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.Level;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.user.AccountService;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.template.TemplateApiService;


import com.cloud.dc.DataCenterVO;

import static com.cloud.automation.version.AutomationVersionService.AutomationServiceEnabled;

public class AutomationControllerManagerImpl extends ManagerBase implements AutomationControllerService {
    private static final int AUTOMATION_CONTROLLER_HTTP_PORT = 80;
    private static final int ALERT_RECOVERY_CONNECT_TIMEOUT_MS = 3000;
    private static final int ALERT_RECOVERY_READ_TIMEOUT_MS = 3000;

    protected StateMachine2<AutomationController.State, AutomationController.Event, AutomationController> _stateMachine = AutomationController.State.getStateMachine();

//    ScheduledExecutorService _gcExecutor;
//    ScheduledExecutorService _stateScanner;

    @Inject
    public AutomationControllerVersionDao automationControllerVersionDao;
    @Inject
    public AutomationControllerDao automationControllerDao;
    @Inject
    public AutomationControllerVmMapDao automationControllerVmMapDao;
    @Inject
    public AutomationDeployedResourceDao automationDeployedResourceDao;
    @Inject
    private TemplateJoinDao templateJoinDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    protected AccountService accountService;
    @Inject
    private TemplateApiService templateService;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private VMTemplateZoneDao templateZoneDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected ResourceTagDao resourceTagDao;
    @Inject
    protected UserVmJoinDao userVmJoinDao;

    private void logMessage(final Level logLevel, final String message, final Exception e) {
        if (logLevel == Level.WARN) {
            if (e != null) {
                logger.warn(message, e);
            } else {
                logger.warn(message);
            }
        } else {
            if (e != null) {
                logger.error(message, e);
            } else {
                logger.error(message);
            }
        }
    }

    @Override
    public AutomationControllerResponse addAutomationControllerResponse(long automationControllerId) {
        AutomationControllerVO automationController = automationControllerDao.findById(automationControllerId);
        AutomationControllerResponse response = new AutomationControllerResponse();
        response.setObjectName("automationcontroller");
        response.setId(automationController.getUuid());
        response.setName(automationController.getName());
        response.setDescription(automationController.getDescription());
        response.setCreated(automationController.getCreated());
        response.setNetworkId(String.valueOf(automationController.getNetworkId()));
        response.setNetworkName(automationController.getNetworkName());
        response.setAutomationTemplateId(String.valueOf(automationController.getAutomationTemplateId()));

        AutomationControllerVersionVO acTemplate = automationControllerVersionDao.findById(automationController.getAutomationTemplateId());
        if (acTemplate != null) {
            response.setAutomationTemplateName(acTemplate.getName());
            response.setAutomationControllerVersion(acTemplate.getVersion());
        }

        NetworkVO ntwk = networkDao.findByIdIncludingRemoved(automationController.getNetworkId());
        if (ntwk != null) {
            response.setNetworkId(ntwk.getUuid());
        }
        response.setAutomationControllerIp(automationController.getAutomationControllerIp());
        response.setRemoved(automationController.getRemoved());
        DataCenterVO zone = dataCenterDao.findById(automationController.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }

        if (ntwk != null && ntwk.getGuestType() == Network.GuestType.Isolated) {
            List<IPAddressVO> ipAddresses = ipAddressDao.listByAssociatedNetwork(ntwk.getId(), true);
            IPAddressVO sourceNatIp = findSourceNatIp(ipAddresses);
            if (sourceNatIp != null) {
                response.setIpAddress(sourceNatIp.getAddress().addr());
                response.setIpAddressId(sourceNatIp.getUuid());
            }
        }

        ServiceOfferingVO offering = serviceOfferingDao.findById(automationController.getServiceOfferingId());
        if (offering != null) {
            response.setServiceOfferingId(offering.getUuid());
            response.setServiceOfferingName(offering.getName());
        }

        Account account = ApiDBUtils.findAccountById(automationController.getAccountId());
        if (account != null && account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            if (project != null) {
                response.setProjectId(project.getUuid());
                response.setProjectName(project.getName());
            }
        } else if (account != null) {
            response.setAccountName(account.getAccountName());
        }

        List<UserVmResponse> automationControllerVmResponses = new ArrayList<UserVmResponse>();
        List<AutomationControllerVmMapVO> controlVmList = automationControllerVmMapDao.listByAutomationControllerId(automationController.getId());

        ResponseObject.ResponseView respView = ResponseObject.ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountService.isRootAdmin(caller.getId())) {
            respView = ResponseObject.ResponseView.Full;
        }

        String responseName = "controlvmlist";
        if (controlVmList != null && !controlVmList.isEmpty()) {
            for (AutomationControllerVmMapVO vmMapVO : controlVmList) {
                UserVmJoinVO userVM = userVmJoinDao.findById(vmMapVO.getVmId());
                if (userVM != null) {
                    UserVmResponse cvmResponse = ApiDBUtils.newUserVmResponse(respView, responseName, userVM, EnumSet.of(ApiConstants.VMDetails.nics), caller);
                    automationControllerVmResponses.add(cvmResponse);
                    response.setAutomationControllerIp(userVM.getIpAddress());
                    GuestOS guestOS = ApiDBUtils.findGuestOSById(userVM.getGuestOsId());
                    if (guestOS != null) {
                        response.setOsDisplayName(guestOS.getDisplayName());
                    }
                    response.setHostName(userVM.getHostName());
                } else {
                    logger.warn(String.format("VM %d mapped to automation controller %s no longer exists",
                            vmMapVO.getVmId(), automationController.getName()));
                }
            }
        }

        response.setState(resolveAutomationControllerResponseState(automationController, automationControllerVmResponses));
        response.setAutomationControllerVms(automationControllerVmResponses);

        return response;
    }

    private String resolveAutomationControllerResponseState(AutomationControllerVO automationController,
                                                            List<UserVmResponse> automationControllerVmResponses) {
        if (automationController.getState() == null) {
            return null;
        }
        if (!AutomationController.State.Alert.equals(automationController.getState())) {
            return automationController.getState().toString();
        }
        if (automationControllerVmResponses.isEmpty()) {
            return automationController.getState().toString();
        }

        final String automationControllerVmState = automationControllerVmResponses.get(0).getState();
        if (!"Running".equals(automationControllerVmState)) {
            return automationController.getState().toString();
        }

        final String address = getAutomationControllerPublicIpAddress(automationController);
        if (address != null && isAutomationControllerHttpReady(address, AUTOMATION_CONTROLLER_HTTP_PORT)) {
            if (stateTransitTo(automationController.getId(), AutomationController.Event.RecoveryRequested)
                    && stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded)) {
                logger.info(String.format("Recovered automation controller %s from Alert after its HTTP endpoint became ready",
                        automationController.getName()));
                return AutomationController.State.Running.toString();
            }
        }

        return automationController.getState().toString();
    }

    private String getAutomationControllerPublicIpAddress(AutomationControllerVO automationController) {
        NetworkVO ntwk = networkDao.findByIdIncludingRemoved(automationController.getNetworkId());
        if (ntwk == null || ntwk.getGuestType() != Network.GuestType.Isolated) {
            return null;
        }

        IPAddressVO sourceNatIp = findSourceNatIp(ipAddressDao.listByAssociatedNetwork(ntwk.getId(), true));
        return sourceNatIp == null ? null : sourceNatIp.getAddress().addr();
    }

    private IPAddressVO findSourceNatIp(List<IPAddressVO> ipAddresses) {
        if (ipAddresses == null) {
            return null;
        }
        for (IPAddressVO ipAddress : ipAddresses) {
            if (ipAddress != null && ipAddress.isSourceNat()) {
                return ipAddress;
            }
        }
        return null;
    }

    private boolean isAutomationControllerHttpReady(String address, int port) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + address + ":" + port);
            URLConnection con = url.openConnection();
            connection = (HttpURLConnection) con;
            connection.setConnectTimeout(ALERT_RECOVERY_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(ALERT_RECOVERY_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public ListResponse<AutomationControllerResponse> listAutomationController(ListAutomationControllerCmd cmd) {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long zoneId = cmd.getZoneId();
        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        final Long automationControllerId = cmd.getId();
        final String state = cmd.getState();
        final String name = cmd.getName();
        final String keyword = cmd.getKeyword();
        List<AutomationControllerResponse> responsesList = new ArrayList<>();
        List<Long> permittedAccounts = new ArrayList<Long>();
        Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, Project.ListProjectResourcesCriteria>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, automationControllerId, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(AutomationControllerVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<AutomationControllerVO> sb = automationControllerDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.IN);
        SearchCriteria<AutomationControllerVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (state != null) {
            sc.setParameters("state", state);
        }
        if (keyword != null){
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        if (automationControllerId != null) {
            sc.setParameters("id", automationControllerId);
        }
        if (name != null) {
            sc.setParameters("name", name);
        }
        if (zoneId != null) {
            SearchCriteria<AutomationControllerVO> scc = automationControllerDao.createSearchCriteria();
            scc.addOr("zoneId", SearchCriteria.Op.EQ, zoneId);
            scc.addOr("zoneId", SearchCriteria.Op.NULL);
            sc.addAnd("zoneId", SearchCriteria.Op.SC, scc);
        }
        if(keyword != null){
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <AutomationControllerVO> controllers = automationControllerDao.search(sc, searchFilter);

        for (AutomationControllerVO cluster : controllers) {
            AutomationControllerResponse automationControllerResponse = addAutomationControllerResponse(cluster.getId());
            responsesList.add(automationControllerResponse);
        }
        ListResponse<AutomationControllerResponse> response = new ListResponse<>();
        response.setResponses(responsesList);
        return response;
    }

    protected boolean stateTransitTo(long automationControllerId, AutomationController.Event e) {
        AutomationControllerVO automationController = automationControllerDao.findById(automationControllerId);
        if (automationController == null) {
            logger.warn(String.format("Failed to transition missing automation controller %d on event %s",
                    automationControllerId, e));
            return false;
        }
        try {
            return _stateMachine.transitTo(automationController, e, null, automationControllerDao);
        } catch (NoTransitionException nte) {
            logger.warn(String.format("Failed to transition state of the automation automation : %s in state %s on event %s",
                    automationController.getName(), automationController.getState().toString(), e.toString()), nte);
            return false;
        }
    }

    @Override
    public AutomationController addAutomationController(final AddAutomationControllerCmd cmd) {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        validateAutomationControllerCreateParameters(cmd);

        final Account owner = accountService.getActiveAccountById(cmd.getEntityOwnerId());
        final AutomationControllerVersion automationControllerVersion = automationControllerVersionDao.findById(cmd.getAutomationTemplateId());
        if (owner == null) {
            throw new InvalidParameterValueException("Unable to find the owner account for the Automation controller");
        }
        if (automationControllerVersion == null) {
            throw new InvalidParameterValueException("Unable to find the requested Automation controller template version");
        }
        final AutomationControllerVO controller = Transaction.execute(new TransactionCallback<AutomationControllerVO>() {
            @Override
            public AutomationControllerVO doInTransaction(TransactionStatus status) {
                AutomationControllerVO newController = new AutomationControllerVO(cmd.getName(), cmd.getDescription(), automationControllerVersion.getId(), cmd.getZoneId(),
                        cmd.getServiceOfferingId(), cmd.getNetworkId(), cmd.getNetworkName(), owner.getAccountId(), cmd.getDomainId(), AutomationController.State.Created, cmd.getAutomationControllerIp());
                automationControllerDao.persist(newController);
                return newController;
            }
        });
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Automation controller name: %s and ID: %s has been created", controller.getName(), controller.getUuid()));
        }
        return controller;

    }

    private void validateAutomationControllerCreateParameters(final AddAutomationControllerCmd cmd) throws CloudRuntimeException {
        final String name = cmd.getName();
        final String description = cmd.getDescription();
        final Long networkId = cmd.getNetworkId();
        final String networkName = cmd.getNetworkName();

        if (name == null || name.isEmpty()) {
            throw new InvalidParameterValueException("Invalid name for the Automation controller name:" + name);
        }
        if (!NetUtils.verifyDomainNameLabel(name, true)) {
            throw new InvalidParameterValueException("Invalid name. Automation controller name can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                    + "and the hyphen ('-'), and can't start or end with \"-\" and can't start with digit");
        }
        if (networkId == null) {
            throw new InvalidParameterValueException("Automation controller network ID is required");
        }
        NetworkVO network = networkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find the requested Automation controller network");
        }
        if (!Network.GuestType.Isolated.equals(network.getGuestType())) {
            throw new InvalidParameterValueException("Automation controllers require an isolated network with a source NAT IP");
        }
        if (cmd.getZoneId() == null || dataCenterDao.findById(cmd.getZoneId()) == null) {
            throw new InvalidParameterValueException("Unable to find the requested Automation controller zone");
        }
        if (network.getDataCenterId() != cmd.getZoneId()) {
            throw new InvalidParameterValueException("Automation controller network and zone do not match");
        }
        if (cmd.getServiceOfferingId() == null || serviceOfferingDao.findById(cmd.getServiceOfferingId()) == null) {
            throw new InvalidParameterValueException("Unable to find the requested Automation controller service offering");
        }
        AutomationControllerVersion version = cmd.getAutomationTemplateId() == null ? null
                : automationControllerVersionDao.findById(cmd.getAutomationTemplateId());
        if (version == null) {
            throw new InvalidParameterValueException("Unable to find the requested Automation controller template version");
        }
        if (version.getZoneId() == null || !version.getZoneId().equals(cmd.getZoneId())) {
            throw new InvalidParameterValueException("Automation controller template version and zone do not match");
        }
        final List<AutomationControllerVO> controllers = automationControllerDao.listAll();
        for (final AutomationControllerVO controller : controllers) {
            final String otherName = controller.getName();
            final Long otherNetwork = controller.getNetworkId();
            final String otherNetworkName = controller.getNetworkName();
            if (otherName.equals(name)) {
                throw new InvalidParameterValueException("Automation controller name '" + name + "' already exists.");
            }
            if (otherNetwork.equals(networkId)){
                throw new InvalidParameterValueException("Automation controller network id '" + networkId + "' already deployed.");
            }
            if (otherNetworkName != null && otherNetworkName.equals(networkName)){
                throw new InvalidParameterValueException("Automation controller network name '" + networkName + "' already deployed.");
            }
        }
        if (description == null || description.isEmpty()) {
            throw new InvalidParameterValueException("Invalid description for the Automation controller description:" + description);
        }
    }

    @Override
    public boolean startAutomationController(long automationControllerId, boolean onCreate) throws CloudRuntimeException {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final AutomationControllerVO automationController = automationControllerDao.findById(automationControllerId);
        if (automationController == null) {
            throw new InvalidParameterValueException("Failed to find Automation Controller with given ID");
        }
        if (automationController.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("Automation Controller : %s is already deleted", automationController.getName()));
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, automationController);
        if (automationController.getState().equals(AutomationController.State.Running)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Automation Controller : %s is in running state", automationController.getName()));
            }
            return true;
        }
        if (automationController.getState().equals(AutomationController.State.Starting)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Automation Controller : %s is already in starting state", automationController.getName()));
            }
            return true;
        }
        if (onCreate && !AutomationController.State.Created.equals(automationController.getState())) {
            throw new InvalidParameterValueException(String.format("Automation Controller %s cannot be created from state %s",
                    automationController.getName(), automationController.getState()));
        }
        if (!onCreate && !(AutomationController.State.Stopped.equals(automationController.getState())
                || AutomationController.State.Alert.equals(automationController.getState()))) {
            throw new InvalidParameterValueException(String.format("Automation Controller %s cannot be started from state %s",
                    automationController.getName(), automationController.getState()));
        }
        AutomationControllerStartWorker startWorker =
                new AutomationControllerStartWorker(automationController, this);
        startWorker = ComponentContext.inject(startWorker);
        if (onCreate) {
            // Start for Automation Controller in 'Created' state
            return startWorker.startAutomationControllerOnCreate();
        } else {
            // Start for Automation Controller in 'Stopped' state. Resources are already provisioned, just need to be started
            return startWorker.startStoppedAutomationController();
        }
    }

    @Override
    public boolean deleteAutomationController(Long automationControllerId) throws CloudRuntimeException {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        AutomationControllerVO cluster = automationControllerDao.findById(automationControllerId);
        if (cluster == null) {
            throw new InvalidParameterValueException("Invalid cluster id specified");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, cluster);
        AutomationControllerDestroyWorker destroyWorker = new AutomationControllerDestroyWorker(cluster, this);
        destroyWorker = ComponentContext.inject(destroyWorker);
        return destroyWorker.destroy();
    }

    @Override
    public boolean stopAutomationController(long automationControllerId) throws CloudRuntimeException {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final AutomationControllerVO automationController = automationControllerDao.findById(automationControllerId);
        if (automationController == null) {
            throw new InvalidParameterValueException("Failed to find Automation Controller with given ID");
        }
        if (automationController.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("Automation Controller : %s is already deleted", automationController.getName()));
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, (ControlledEntity) automationController);
        if (automationController.getState().equals(AutomationController.State.Stopped)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Automation Controller : %s is already stopped", automationController.getName()));
            }
            return true;
        }
        if (automationController.getState().equals(AutomationController.State.Stopping)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Automation Controller : %s is getting stopped", automationController.getName()));
            }
            return true;
        }
        if (!(AutomationController.State.Running.equals(automationController.getState())
                || AutomationController.State.Alert.equals(automationController.getState()))) {
            throw new InvalidParameterValueException(String.format("Automation Controller %s cannot be stopped from state %s",
                    automationController.getName(), automationController.getState()));
        }
        AutomationControllerStopWorker stopWorker = new AutomationControllerStopWorker(automationController, this);
        stopWorker = ComponentContext.inject(stopWorker);
        return stopWorker.stop();
    }


    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!AutomationServiceEnabled.value()) {
            return cmdList;
        }
        cmdList.add(ListAutomationControllerCmd.class);
        cmdList.add(AddAutomationControllerCmd.class);
        cmdList.add(StartAutomationControllerCmd.class);
        cmdList.add(StopAutomationControllerCmd.class);
        cmdList.add(DeleteAutomationControllerCmd.class);
        return cmdList;
    }

    @Override
    public AutomationController findById(final Long id) {
        return automationControllerDao.findById(id);
    }

}
