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
import javax.inject.Inject;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.automation.controller.dao.AutomationControllerDao;
import com.cloud.automation.version.AutomationControllerVersionVO;
import com.cloud.automation.version.dao.AutomationControllerVersionDao;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.projects.Project;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.api.command.user.automation.controller.AddAutomationControllerCmd;
import org.apache.cloudstack.api.command.user.automation.controller.ListAutomationControllerCmd;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.log4j.Logger;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.automation.version.AutomationVersionService;
import com.cloud.utils.component.ManagerBase;
import com.cloud.user.AccountService;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.template.TemplateApiService;


import com.cloud.dc.DataCenterVO;

public class AutomationControllerManagerImpl extends ManagerBase implements AutomationControllerService {
    public static final Logger LOGGER = Logger.getLogger(AutomationControllerManagerImpl.class.getName());

    @Inject
    private AutomationControllerVersionDao automationControllerVersionDao;
    @Inject
    private AutomationControllerDao automationControllerDao;
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

    private AutomationControllerResponse createAutomationControllerResponse(final AutomationController automationController) {
//        AutomationControllerVO automationcontroller = automationControllerDao.findById(automationController);
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
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
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

    @Override
//    @ActionEvent(eventType = AutomationVersionEventTypes.EVENT_AUTOMATION_CONTROLLER_VERSION_ADD, eventDescription = "Adding automation controller template version")
    public AutomationControllerResponse addAutomationController(final AddAutomationControllerCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }

        final String vmName = cmd.getName();
        final String zoneId = cmd.getZoneId();
        final String description = cmd.getDescription();
        final String id = cmd.getId();
        final Long templateId =cmd.getAutomationTemplateId();

        final List<AutomationControllerVO> controllers = automationControllerDao.listAll();
        for (final AutomationControllerVO controller : controllers) {
            final String otherController = controller.getName();
        }

//        if (compareVersions(controllerVersion, MIN_AUTOMATION_CONTOLLER_VERSION) < 0) {
//            throw new InvalidParameterValueException(String.format("New automation controller version cannot be added as %s is minimum version supported by Automation Service", MIN_AUTOMATION_CONTOLLER_VERSION));
//        }
//        if (zoneId != null && dataCenterDao.findById(zoneId) == null) {
//            throw new InvalidParameterValueException("Invalid zone specified");
//        }
//        if ("url".equals(uploadType) && StringUtils.isEmpty(url)) {
//            throw new InvalidParameterValueException(String.format("Invalid URL for template specified, %s", url));
//        }
//
//        if (StringUtils.isEmpty(versionName)) {
//            throw new InvalidParameterValueException(String.format("Invalid VersionName for template specified, %s", versionName));
//        }

        Long zone = null;
        VMInstanceVO vm = null;
        AutomationControllerVO automationControllerVO = null;
        try {
//            if ("url".equals(uploadType)) {
                //vm_template 테이블에 automation 템플릿 추가
//                vmName = String.format("%s(Automation Controller)", versionName);
//                template = templateDao.findById(vmTemplate.getId());

//                //vm instance 테이블에 버전 추가
//                automationControllerVO = new AutomationControllerVO(vmName, description);
//                automationControllerVO = automationControllerDao.persist(automationControllerVO);

//                //템플릿에 세팅 추가
//                Map<String, String> details = new HashMap<String, String>();
//                details.put("rootDiskController", "virtio");
//                template = templateDao.createForUpdate(template.getId());
//                template.setDetails(details);
//                templateDao.saveDetails(template);
//                templateDao.update(template.getId(), template);
//            } else {
//                template = templateDao.findById(templateId);
//                List<VMTemplateZoneVO> templateZones = templateZoneDao.listByTemplateId(templateId);
//                if (templateZones != null) {
//                    for (VMTemplateZoneVO templateZone : templateZones) {
//                        zone = templateZone.getZoneId();
//                    }
//                }

                //automation_controller 테이블에 버전 추가
                automationControllerVO = new AutomationControllerVO(vmName, description);
                automationControllerVO = automationControllerDao.persist(automationControllerVO);
//            }
        } catch (IllegalArgumentException ex) {
            LOGGER.error(String.format("Unable to register template for automation controller version, %s, with url: %s", vmName), ex);
            throw new CloudRuntimeException(String.format("Unable to register template for automation controller version, %s, with url: %s", vmName));
        }
        return createAutomationControllerResponse(automationControllerVO);
    }

    private ListResponse<AutomationControllerResponse> createAutomationControllerListResponse(List<AutomationControllerVO> controllers) {
        List<AutomationControllerResponse> responseList = new ArrayList<>();
        for (AutomationControllerVO name : controllers) {
            responseList.add(createAutomationControllerResponse(name));
        }
        ListResponse<AutomationControllerResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            return cmdList;
        }
        cmdList.add(ListAutomationControllerCmd.class);
        cmdList.add(AddAutomationControllerCmd.class);
        return cmdList;
    }


    // @Override
    // public String getConfigComponentName() {
    //     return AutomationControllerService.class.getSimpleName();
    // }

    // @Override
    // public ConfigKey<?>[] getConfigKeys() {
    //     return new ConfigKey<?>[] {
    //             AutomationServiceEnabled
    //     };
    // }
}