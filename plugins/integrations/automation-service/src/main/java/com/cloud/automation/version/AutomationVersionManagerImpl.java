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

package com.cloud.automation.version;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javax.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.automation.version.AddAutomationControllerVersionCmd;
import org.apache.cloudstack.api.command.admin.automation.version.UpdateAutomationControllerVersionCmd;
import org.apache.cloudstack.api.command.admin.automation.version.DeleteAutomationControllerVersionCmd;
import org.apache.cloudstack.api.command.admin.automation.version.ListAutomationControllerVersionCmd;
import org.apache.cloudstack.api.response.AutomationControllerVersionResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.commons.lang3.StringUtils;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.template.DeleteTemplateCmd;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.automation.controller.AutomationControllerVO;
import com.cloud.automation.controller.dao.AutomationControllerDao;
import com.cloud.automation.version.dao.AutomationControllerVersionDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.user.AccountService;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.component.ComponentContext;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.template.TemplateApiService;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.event.ActionEvent;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.dc.DataCenterVO;

public class AutomationVersionManagerImpl extends ManagerBase implements AutomationVersionService {

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

    @Override
    public ListResponse<AutomationControllerVersionResponse> listAutomationControllerVersion(final ListAutomationControllerVersionCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        final Long zoneId = cmd.getZoneId();
        Filter searchFilter = new Filter(AutomationControllerVersionVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<AutomationControllerVersionVO> sb = automationControllerVersionDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        SearchCriteria<AutomationControllerVersionVO> sc = sb.create();
        String keyword = cmd.getKeyword();
        if (versionId != null) {
            sc.setParameters("id", versionId);
        }
        if (zoneId != null) {
            SearchCriteria<AutomationControllerVersionVO> scc = automationControllerVersionDao.createSearchCriteria();
            scc.addOr("zoneId", SearchCriteria.Op.EQ, zoneId);
            scc.addOr("zoneId", SearchCriteria.Op.NULL);
            sc.addAnd("zoneId", SearchCriteria.Op.SC, scc);
        }
        if(keyword != null){
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <AutomationControllerVersionVO> versions = automationControllerVersionDao.search(sc, searchFilter);

        return createAutomationControllerVersionListResponse(versions);
    }

    private ListResponse<AutomationControllerVersionResponse> createAutomationControllerVersionListResponse(List<AutomationControllerVersionVO> versions) {
        List<AutomationControllerVersionResponse> responseList = new ArrayList<>();
        for (AutomationControllerVersionVO version : versions) {
            responseList.add(createAutomationControllerVersionResponse(version));
        }
        ListResponse<AutomationControllerVersionResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    private AutomationControllerVersionResponse createAutomationControllerVersionResponse(final AutomationControllerVersion automationControllerVersion) {
        AutomationControllerVersionResponse response = new AutomationControllerVersionResponse();
        response.setObjectName("automationcontrollerversion");
        response.setId(automationControllerVersion.getUuid());
        response.setName(automationControllerVersion.getName());
        response.setDescription(automationControllerVersion.getDescription());
        response.setVersion(automationControllerVersion.getVersion());
        response.setCreated(automationControllerVersion.getCreated());
        // response.setUploadType(automationControllerVersion.getUploadType());
        response.setTemplateId(automationControllerVersion.getTemplateId());
        if (automationControllerVersion.getState() != null) {
            response.setState(automationControllerVersion.getState().toString());
        }
        DataCenterVO zone = dataCenterDao.findById(automationControllerVersion.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        TemplateJoinVO template = templateJoinDao.findById(automationControllerVersion.getTemplateId());
        if (template != null) {
            response.setTemplateName(template.getName());
            response.setTemplateState(template.getState().toString());
        }
        return response;
    }

    @Override
    @ActionEvent(eventType = AutomationVersionEventTypes.EVENT_AUTOMATION_CONTROLLER_VERSION_ADD, eventDescription = "Adding automation controller template version")
    public AutomationControllerVersionResponse addAutomationControllerVersion(final AddAutomationControllerVersionCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final String format = cmd.getFormat();
        final String hypervisor = cmd.getHypervisor();
        final String versionName = cmd.getControllerVersionName();
        final String description = cmd.getDescription();
        final String controllerVersion = cmd.getAutomationControllerVersion();
        final Long zoneId = cmd.getZoneId();
        final String uploadType = cmd.getUploadType();
        final String url = cmd.getUrl();
        final Long osTypeId = cmd.getOsType();
        final Long templateId =cmd.getTemplateId();
        String templateName = "";

        final List<AutomationControllerVersionVO> versions = automationControllerVersionDao.listAll();
        for (final AutomationControllerVersionVO version : versions) {
            final String otherVersion = version.getVersion();
            if (otherVersion.equals(controllerVersion)) {
                throw new InvalidParameterValueException("version '" + controllerVersion + "' already exists.");
            }
        }

        if (compareVersions(controllerVersion, MIN_AUTOMATION_CONTOLLER_VERSION) < 0) {
            throw new InvalidParameterValueException(String.format("New automation controller version cannot be added as %s is minimum version supported by Automation Service", MIN_AUTOMATION_CONTOLLER_VERSION));
        }
        if (zoneId != null && dataCenterDao.findById(zoneId) == null) {
            throw new InvalidParameterValueException("Invalid zone specified");
        }
        if ("url".equals(uploadType) && StringUtils.isEmpty(url)) {
            throw new InvalidParameterValueException(String.format("Invalid URL for template specified, %s", url));
        }

        if (StringUtils.isEmpty(versionName)) {
            throw new InvalidParameterValueException(String.format("Invalid VersionName for template specified, %s", versionName));
        }

        Long zone = null;
        VMTemplateVO template = null;
        AutomationControllerVersionVO automationControllerVersionVO = null;
        VirtualMachineTemplate vmTemplate = null;
        try {
            if ("url".equals(uploadType)) {
                //vm_template 테이블에 automation 템플릿 추가
                templateName = String.format("%s(Automation Controller Template)", versionName);
                vmTemplate = registerAutomationTemplateVersion(zoneId, templateName, url, hypervisor, osTypeId, format);
                template = templateDao.findById(vmTemplate.getId());

                //automation_controller_version 테이블에 버전 추가
                automationControllerVersionVO = new AutomationControllerVersionVO(versionName, controllerVersion, description, zoneId, template.getId(), uploadType);
                automationControllerVersionVO = automationControllerVersionDao.persist(automationControllerVersionVO);

                //템플릿에 세팅 추가
                Map<String, String> details = new HashMap<String, String>();
                details.put("rootDiskController", "virtio");
                template = templateDao.createForUpdate(template.getId());
                template.setDetails(details);
                templateDao.saveDetails(template);
                templateDao.update(template.getId(), template);
            } else {
                template = templateDao.findById(templateId);
                List<VMTemplateZoneVO> templateZones = templateZoneDao.listByTemplateId(templateId);
                if (templateZones != null) {
                    for (VMTemplateZoneVO templateZone : templateZones) {
                        zone = templateZone.getZoneId();
                    }
                }

                //automation_controller_version 테이블에 버전 추가
                automationControllerVersionVO = new AutomationControllerVersionVO(versionName, controllerVersion, description, zone, template.getId(), uploadType);
                automationControllerVersionVO = automationControllerVersionDao.persist(automationControllerVersionVO);
            }
        } catch (URISyntaxException | IllegalAccessException | NoSuchFieldException | IllegalArgumentException | ResourceAllocationException ex) {
            logger.error(String.format("Unable to register template for automation controller version, %s, with url: %s", templateName, url), ex);
            throw new CloudRuntimeException(String.format("Unable to register template for automation controller version, %s, with url: %s", templateName, url));
        }
        return createAutomationControllerVersionResponse(automationControllerVersionVO);
    }

    public static int compareVersions(String v1, String v2) throws IllegalArgumentException {
        if (StringUtils.isEmpty(v1) || StringUtils.isEmpty(v2)) {
            throw new IllegalArgumentException(String.format("Invalid version comparision with versions %s, %s", v1, v2));
        }
        if(!isSemanticVersion(v1)) {
            throw new IllegalArgumentException(String.format("Invalid version format, %s. version should be specified in MAJOR.MINOR.PATCH format. PATCH accepts only numbers and lowercase letters.", v1));
        }
        if(!isSemanticVersion(v2)) {
            throw new IllegalArgumentException(String.format("Invalid version format, %s. version should be specified in MAJOR.MINOR.PATCH format", v2));
        }
        if (v1.matches("[0-9]+(\\.[0-9]+)*")) {
            String[] thisParts = v1.split("\\.");
            String[] thatParts = v2.split("\\.");
            int length = Math.max(thisParts.length, thatParts.length);
            for(int i = 0; i < length; i++) {
                int thisPart = i < thisParts.length ?
                        Integer.parseInt(thisParts[i]) : 0;
                int thatPart = i < thatParts.length ?
                        Integer.parseInt(thatParts[i]) : 0;
                if(thisPart < thatPart)
                    return -1;
                if(thisPart > thatPart)
                    return 1;
            }
        } else {
            return 1;
        }
        return 0;
    }

    private static boolean isSemanticVersion(final String version) {
        if(!version.matches("[0-9]+(\\.[0-9]+)*") && !version.matches("[0-9]+\\.[0-9]+\\.[a-z]|[0.9]")) {
            return false;
        }
        String[] parts = version.split("\\.");
        if (parts.length < 3) {
            return false;
        }
        return true;
    }

    private VirtualMachineTemplate registerAutomationTemplateVersion(final Long zoneId, final String templateName, final String url, final String hypervisor, final Long osTypeId, final String format)throws IllegalAccessException, NoSuchFieldException,
            IllegalArgumentException, ResourceAllocationException, URISyntaxException {
        RegisterTemplateCmd registerTemplateCmd = new RegisterTemplateCmd();
        registerTemplateCmd = ComponentContext.inject(registerTemplateCmd);
        registerTemplateCmd.setTemplateName(templateName);
        registerTemplateCmd.setHypervisor(hypervisor);
        registerTemplateCmd.setFormat(format);
        registerTemplateCmd.setPublic(true);
        registerTemplateCmd.setOsTypeId(osTypeId);
        if (zoneId != null) {
            registerTemplateCmd.setZoneId(zoneId);
        }
        registerTemplateCmd.setDisplayText(templateName);
        registerTemplateCmd.setUrl(url);
        registerTemplateCmd.setAccountName(accountManager.getSystemAccount().getAccountName());
        registerTemplateCmd.setDomainId(accountManager.getSystemAccount().getDomainId());
        registerTemplateCmd.setIsDesktop(true);
        return templateService.registerTemplate(registerTemplateCmd);
    }

    @Override
    @ActionEvent(eventType = AutomationVersionEventTypes.EVENT_AUTOMATION_CONTROLLER_VERSION_UPDATE, eventDescription = "Updating automation controller version")
    public AutomationControllerVersionResponse updateAutomationControllerVersion(final UpdateAutomationControllerVersionCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        AutomationControllerVersion.State state = null;
        AutomationControllerVersionVO version = automationControllerVersionDao.findById(versionId);
        if (version == null) {
            throw new InvalidParameterValueException("Invalid automation controller version id specified");
        }
        try {
            state = AutomationControllerVersion.State.valueOf(cmd.getState());
        } catch (IllegalArgumentException iae) {
            throw new InvalidParameterValueException(String.format("Invalid value for %s parameter", ApiConstants.STATE));
        }
        if (!state.equals(version.getState())) {
            version = automationControllerVersionDao.createForUpdate(version.getId());
            version.setState(state);
            if (!automationControllerVersionDao.update(version.getId(), version)) {
                throw new CloudRuntimeException(String.format("Failed to update automation controller version ID: %s", version.getUuid()));
            }
            version = automationControllerVersionDao.findById(versionId);
        }
        return  createAutomationControllerVersionResponse(version);
    }

    @Override
    @ActionEvent(eventType = AutomationVersionEventTypes.EVENT_AUTOMATION_CONTROLLER_VERSION_DELETE, eventDescription = "Deleting Automation Controller Version", async = true)
    public boolean deleteAutomationContollerVersion(final DeleteAutomationControllerVersionCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        AutomationControllerVersion version = automationControllerVersionDao.findById(versionId);
        String uploadType = version.getUploadType();
        Long templateId = version.getTemplateId();
        if (version == null) {
            throw new InvalidParameterValueException("Invalid automation controller version id specified");
        }

        List<AutomationControllerVO> controllers = automationControllerDao.listAllByAutomationVersion(versionId);
        if (controllers.size() > 0) {
            throw new CloudRuntimeException(String.format("Unable to delete automation controller version ID: %s. Existing controller currently using the version.", version.getUuid()));
        }

        if (templateId == null) {
            logger.warn(String.format("Unable to find template associated with supported automation controller version ID: %s", version.getUuid()));
        }
        if ("url".equals(uploadType) && templateId != null) {// upload type이 'url' 타입일 경우 템플릿까지 삭제, 'template' 타입 일 경우 템플릿은 삭제 안됨.
            try {
                deleteAutomationVersionTemplate(templateId);
            } catch (IllegalAccessException | NoSuchFieldException | IllegalArgumentException ex) {
                logger.error(String.format("Unable to delete ID: %s associated with supported automation controller version ID: %s", templateId, version.getUuid()), ex);
                throw new CloudRuntimeException(String.format("Unable to delete ID: %s associated with supported automation controller version ID: %s", templateId, version.getUuid()));
            }
        }

        return automationControllerVersionDao.remove(version.getId());
    }

    private void deleteAutomationVersionTemplate(long templateId) throws IllegalAccessException, NoSuchFieldException, IllegalArgumentException {
        DeleteTemplateCmd deleteTemplateCmd = new DeleteTemplateCmd();
        deleteTemplateCmd = ComponentContext.inject(deleteTemplateCmd);
        deleteTemplateCmd.setId(templateId);
        deleteTemplateCmd.setIsDesktop(true);
        templateService.deleteTemplate(deleteTemplateCmd);
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            return cmdList;
        }
        cmdList.add(ListAutomationControllerVersionCmd.class);
        cmdList.add(AddAutomationControllerVersionCmd.class);
        cmdList.add(UpdateAutomationControllerVersionCmd.class);
        cmdList.add(DeleteAutomationControllerVersionCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return AutomationVersionService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                AutomationServiceEnabled
        };
    }
}