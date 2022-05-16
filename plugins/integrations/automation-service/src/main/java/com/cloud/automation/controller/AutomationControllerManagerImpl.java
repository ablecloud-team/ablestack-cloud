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
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.automation.controller.actionworkers.AutomationControllerDestroyWorker;
import com.cloud.automation.controller.actionworkers.AutomationControllerStartWorker;
import com.cloud.automation.controller.actionworkers.AutomationControllerStopWorker;
import com.cloud.automation.controller.dao.AutomationControllerDao;
import com.cloud.automation.controller.dao.AutomationControllerVmMapDao;
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
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
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
import org.apache.log4j.Logger;

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
    public static final Logger LOGGER = Logger.getLogger(AutomationControllerManagerImpl.class.getName());

    protected StateMachine2<AutomationController.State, AutomationController.Event, AutomationController> _stateMachine = AutomationController.State.getStateMachine();

    ScheduledExecutorService _gcExecutor;
    ScheduledExecutorService _stateScanner;

    @Inject
    private AutomationControllerVersionDao automationControllerVersionDao;
    @Inject
    public AutomationControllerDao automationControllerDao;
    @Inject
    public AutomationControllerVmMapDao automationControllerVmMapDao;
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

//    private void logTransitStateAndThrow(final Level logLevel, final String message, final Long automationControllerId, final AutomationController.Event event, final Exception e) throws CloudRuntimeException {
//        logMessage(logLevel, message, e);
//        if (automationControllerId != null && event != null) {
//            stateTransitTo(automationControllerId, event);
//        }
//        if (e == null) {
//            throw new CloudRuntimeException(message);
//        }
//        throw new CloudRuntimeException(message, e);
//    }

//    private void logAndThrow(final Level logLevel, final String message) throws CloudRuntimeException {
//        logTransitStateAndThrow(logLevel, message, null, null, null);
//    }

    @Override
    public AutomationControllerResponse addAutomationControllerResponse(final AutomationController automationController) {
        AutomationControllerResponse response = new AutomationControllerResponse();
        AutomationControllerVO controller = automationControllerDao.findById(automationController.getId());
        response.setObjectName("automationcontroller");
        response.setId(automationController.getUuid());
        response.setName(automationController.getName());
        response.setDescription(automationController.getDescription());
        response.setCreated(automationController.getCreated());
        response.setServiceIp(automationController.getServiceIp());
        response.setAutomationTemplateId(String.valueOf(automationController.getAutomationTemplateId()));

        AutomationControllerVersionVO acTemplate = automationControllerVersionDao.findById(automationController.getAutomationTemplateId());
        if (acTemplate != null) {
//            response.setTemplateId(template.getUuid());
            response.setAutomationTemplateName(acTemplate.getName());
//            response.setTemplateState(template.getState().toString());
//            response.setTemplateOSType(template.getGuestOSName());
        }

        NetworkVO ntwk = networkDao.findByIdIncludingRemoved(automationController.getNetworkId());
        response.setNetworkId(ntwk.getUuid());


        response.getServiceIp(automationController.getServiceIp());
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

//        VMInstanceVO vmname = vmInstanceDao.findVMByInstanceName(automationController.getV());

        List<UserVmResponse> automationControllerVmResponses = new ArrayList<UserVmResponse>();
        List<VMInstanceVO> vmList = vmInstanceDao.listByZoneId(automationController.getZoneId());
//        List<AutomationControllerVmMapVO> controlVmList = automationControllerVmMapDao.listByAutomationControllerIdAndNotVmType(controller.getId(), "automationvm");

//        ResponseObject.ResponseView respView = ResponseObject.ResponseView.Restricted;
//        Account caller = CallContext.current().getCallingAccount();
//        if (accountService.isRootAdmin(caller.getId())) {
//            respView = ResponseObject.ResponseView.Full;
//        }
//
//        String responseName = "controllervmlist";
//        String resourceKey = "ControllerName";
//        if (vmList != null && !vmList.isEmpty()) {
//            for (VMInstanceVO vmVO : vmList) {
//                ResourceTag controllervm = resourceTagDao.findByKey(vmVO.getId(), ResourceTag.ResourceObjectType.UserVm, resourceKey);
//                if (controllervm != null) {
//                    if (controllervm.getValue().equals(controller.getName())) {
//                        UserVmJoinVO userVM = userVmJoinDao.findById(vmVO.getId());
//                        if (userVM != null) {
//                            UserVmResponse dvmResponse = ApiDBUtils.newUserVmResponse(respView, responseName, userVM, EnumSet.of(ApiConstants.VMDetails.nics), caller);
//                            AutomationControllerResponse.add(dvmResponse);
//                        }
//                    }
//                }
//            }
//        }

        response.setAutomationControllerVms(automationControllerVmResponses);

        return response;
    }

    @Override
    public ListResponse<AutomationControllerResponse> listAutomationController(final ListAutomationControllerCmd cmd) {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        final Long zoneId = cmd.getZoneId();
        Filter searchFilter = new Filter(AutomationControllerVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<AutomationControllerVO> sb = automationControllerDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        SearchCriteria<AutomationControllerVO> sc = sb.create();
        String keyword = cmd.getKeyword();
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
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <AutomationControllerVO> controllers = automationControllerDao.search(sc, searchFilter);

        return createAutomationControllerListResponse(controllers);
    }

    private ListResponse<AutomationControllerResponse> createAutomationControllerListResponse(List<AutomationControllerVO> controllers) {
        List<AutomationControllerResponse> responseList = new ArrayList<>();
        for (AutomationControllerVO name : controllers) {
            responseList.add(addAutomationControllerResponse(name));
        }
        ListResponse<AutomationControllerResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    @Override
    public AutomationController addAutomationController(AddAutomationControllerCmd cmd) throws CloudRuntimeException {
        if (!AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }

//        validateAutomationControllerCreateParameters(cmd);

        final String L2Type = "internal";
        final ServiceOffering serviceOffering = serviceOfferingDao.findById(cmd.getServiceOfferingId());
        final Account owner = accountService.getActiveAccountById(cmd.getEntityOwnerId());
        final AutomationControllerVersion automationControllerVersion = automationControllerVersionDao.findById(cmd.getAutomationTemplateId());
        final AutomationControllerVO cluster = Transaction.execute(new TransactionCallback<AutomationControllerVO>() {
            @Override
            public AutomationControllerVO doInTransaction(TransactionStatus status) {
                AutomationControllerVO newController = new AutomationControllerVO(cmd.getName(), cmd.getDescription(), automationControllerVersion.getZoneId(), automationControllerVersion.getId(),
                        serviceOffering.getId(), owner.getDomainId(), owner.getAccountId(), AutomationController.State.Created);
                automationControllerDao.persist(newController);
                return newController;
            }
        });
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Automation Controller name: %s and ID: %s has been created", cluster.getName(), cluster.getUuid()));
        }
        return cluster;
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
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Automation Controller : %s is in running state", automationController.getName()));
            }
            return true;
        }
        if (automationController.getState().equals(AutomationController.State.Starting)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Automation Controller : %s is already in starting state", automationController.getName()));
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
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Automation Controller : %s is already stopped", automationController.getName()));
            }
            return true;
        }
        if (automationController.getState().equals(AutomationController.State.Stopping)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Automation Controller : %s is getting stopped", automationController.getName()));
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

    // @Override
    // public String getConfigComponentName() {
    //     return AutomationControllerService.class.getSimpleName();
    // }

}