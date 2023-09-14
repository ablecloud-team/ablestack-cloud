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
import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.ServiceOffering;
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
import com.cloud.vm.VMInstanceVO;
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
import org.apache.log4j.Level;

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
        response.setNetworkId(ntwk.getUuid());
        response.getAutomationControllerIp(automationController.getAutomationControllerIp());
        response.getRemoved(automationController.getRemoved());
        if (automationController.getState() != null) {
            response.setState(automationController.getState().toString());
        }
        DataCenterVO zone = dataCenterDao.findById(automationController.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }

        if (ntwk.getGuestType() == Network.GuestType.Isolated) {
            List<IPAddressVO> ipAddresses = ipAddressDao.listByAssociatedNetwork(ntwk.getId(), true);
            if (ipAddresses != null && ipAddresses.size() == 1) {
                response.setIpAddress(ipAddresses.get(0).getAddress().addr());
                response.setIpAddressId(ipAddresses.get(0).getUuid());
            }
        }

        ServiceOfferingVO offering = serviceOfferingDao.findById(automationController.getServiceOfferingId());
        response.setServiceOfferingId(offering.getUuid());
        response.setServiceOfferingName(offering.getName());

        Account account = ApiDBUtils.findAccountById(automationController.getAccountId());
        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }

        List<UserVmResponse> automationControllerVmResponses = new ArrayList<UserVmResponse>();
        List<VMInstanceVO> vmList = vmInstanceDao.listByZoneId(automationController.getZoneId());
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
                    automationController.setAutomationControllerIp(userVM.getIpAddress());
                }
                try {
                    GuestOS guestOS = ApiDBUtils.findGuestOSById(userVM.getGuestOsId());
                    if (guestOS != null) {
                        response.setOsDisplayName(guestOS.getDisplayName());
                    }
                } catch (NullPointerException e) {
                    deleteAutomationController(automationController.getId());
                    logger.warn(String.format("Failed to run Automation controller Alert state scanner on Automation controller : %s status scanner", automationController.getName()), e);
                }
                response.setHostName(userVM.getHostName());
            }

            String automationControllerState = String.valueOf(automationController.getState());
            String automationControllerVmState = automationControllerVmResponses.get(0).getState();
            try {
                if (automationControllerState != "Starting") {
                    if (automationControllerVmState == "Stopped" && automationControllerState != "Stopped") {
                        stateTransitTo(automationController.getId(), AutomationController.Event.StopRequested);
                        stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
                    }
                }
                if (automationControllerState != "Destroyed" || automationControllerState != "Expunging") {
                    if (automationControllerVmState == "Destroyed" ) {
                        stopAutomationController(automationController.getId());
                    }else if (automationControllerVmState == "Expunging") {
                        deleteAutomationController(automationController.getId());
                    }
                }
            } catch (Exception e) {
                logger.warn(String.format("Failed to run Automation controller Alert state scanner on Automation controller : %s status scanner", automationController.getName()), e);
            }
        }

        response.setAutomationControllerVms(automationControllerVmResponses);

        return response;
    }

    @Override
    public ListResponse<AutomationControllerResponse> listAutomationController(ListAutomationControllerCmd cmd) {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
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
        if (versionId != null) {
            sc.setParameters("id", versionId);
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

        final String L2Type = "internal";
        final ServiceOffering serviceOffering = serviceOfferingDao.findById(cmd.getServiceOfferingId());
        final Account owner = accountService.getActiveAccountById(cmd.getEntityOwnerId());
        final AutomationControllerVersion automationControllerVersion = automationControllerVersionDao.findById(cmd.getAutomationTemplateId());
        AutomationControllerResponse response = new AutomationControllerResponse();
        Long instanceId = Long.valueOf(3);
        final AutomationControllerVO controller = Transaction.execute(new TransactionCallback<AutomationControllerVO>() {
            @Override
            public AutomationControllerVO doInTransaction(TransactionStatus status) {
                AutomationControllerVO newController = new AutomationControllerVO(automationControllerVersion.getId(), cmd.getName(), cmd.getDescription(), cmd.getAutomationTemplateId(), cmd.getZoneId(),
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
        final Long accountId = cmd.getAccountId();
        final Long networkId = cmd.getNetworkId();
        final String networkName = cmd.getNetworkName();
        final String ipAddress = cmd.getAutomationControllerIp();

        if (name == null || name.isEmpty()) {
            throw new InvalidParameterValueException("Invalid name for the Automation controller name:" + name);
        }
        if (!NetUtils.verifyDomainNameLabel(name, true)) {
            throw new InvalidParameterValueException("Invalid name. Automation controller name can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                    + "and the hyphen ('-'), and can't start or end with \"-\" and can't start with digit");
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
            if (otherNetworkName.equals(networkName)){
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
//            logAndThrow(Level.ERROR, "Automation Service plugin is disabled");
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
        final AutomationControllerVersion AutomationControllerVersion = automationControllerVersionDao.findById(automationController.getAutomationTemplateId());
        final DataCenter zone = dataCenterDao.findById(AutomationControllerVersion.getZoneId());
        if (zone == null) {
//            logAndThrow(Level.WARN, String.format("Unable to find zone for Automation Controller : %s", automationController.getName()));
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
//            logAndThrow(Level.ERROR, "Automation Service plugin is disabled");
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
