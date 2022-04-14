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

package com.cloud.desktop.version;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javax.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.user.desktop.version.ListDesktopControllerVersionsCmd;
import org.apache.cloudstack.api.command.user.desktop.version.ListDesktopMasterVersionsCmd;
import org.apache.cloudstack.api.command.admin.desktop.AddDesktopControllerVersionCmd;
import org.apache.cloudstack.api.command.admin.desktop.DeleteDesktopControllerVersionCmd;
import org.apache.cloudstack.api.command.admin.desktop.UpdateDesktopControllerVersionCmd;
import org.apache.cloudstack.api.command.admin.desktop.AddDesktopMasterVersionCmd;
import org.apache.cloudstack.api.command.admin.desktop.DeleteDesktopMasterVersionCmd;
import org.apache.cloudstack.api.command.admin.desktop.UpdateDesktopMasterVersionCmd;
import org.apache.cloudstack.api.response.DesktopControllerVersionResponse;
import org.apache.cloudstack.api.response.DesktopMasterVersionResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.template.DeleteTemplateCmd;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.desktop.cluster.DesktopClusterService;
import com.cloud.desktop.cluster.DesktopClusterVO;
import com.cloud.desktop.cluster.dao.DesktopClusterDao;
import com.cloud.desktop.version.dao.DesktopControllerVersionDao;
import com.cloud.desktop.version.dao.DesktopMasterVersionDao;
import com.cloud.desktop.version.dao.DesktopTemplateMapDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.AccountManager;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.component.ComponentContext;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.event.ActionEvent;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.template.TemplateApiService;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.InvalidParameterValueException;

public class DesktopVersionManagerImpl extends ManagerBase implements DesktopVersionService {
    public static final Logger LOGGER = Logger.getLogger(DesktopVersionManagerImpl.class.getName());

    @Inject
    private DesktopControllerVersionDao desktopControllerVersionDao;
    @Inject
    private DesktopMasterVersionDao desktopMasterVersionDao;
    @Inject
    private TemplateJoinDao templateJoinDao;
    @Inject
    private DesktopTemplateMapDao desktopTemplateMapDao;
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
    private DesktopClusterDao desktopClusterDao;

    private DesktopControllerVersionResponse createDesktopControllerVersionResponse(final DesktopControllerVersion desktopControllerVersion) {
        DesktopControllerVersionResponse response = new DesktopControllerVersionResponse();
        response.setObjectName("desktopcontrollerversion");
        response.setId(desktopControllerVersion.getUuid());
        response.setName(desktopControllerVersion.getName());
        response.setDescription(desktopControllerVersion.getDescription());
        response.setVersion(desktopControllerVersion.getVersion());
        response.setCreated(desktopControllerVersion.getCreated());
        response.setUploadType(desktopControllerVersion.getUploadType());
        if (desktopControllerVersion.getState() != null) {
            response.setState(desktopControllerVersion.getState().toString());
        }
        DataCenterVO zone = dataCenterDao.findById(desktopControllerVersion.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        List<TemplateResponse> dcTempResp = new ArrayList<TemplateResponse>();
        List<TemplateResponse> worksTempResp = new ArrayList<TemplateResponse>();
        List<TemplateResponse> templateResponses = new ArrayList<TemplateResponse>();
        List<DesktopTemplateMapVO> templateList = desktopTemplateMapDao.listByVersionId(desktopControllerVersion.getId());
        ResponseView respView = ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountService.isRootAdmin(caller.getId())) {
            respView = ResponseView.Full;
        }
        final String responseName = "template";
        if (templateList != null && !templateList.isEmpty()) {
            for (DesktopTemplateMapVO templateMapVO : templateList) {
                String tempMapType = templateMapVO.getType();
                TemplateJoinVO userTemplate = templateJoinDao.findById(templateMapVO.getTemplateId());
                if (userTemplate != null) {
                    TemplateResponse templateResponse = ApiDBUtils.newTemplateResponse(EnumSet.of(DomainDetails.resource), respView, userTemplate);
                    templateResponses.add(templateResponse);

                    if("dc".equals(tempMapType)){
                        dcTempResp.add(templateResponse);
                        response.setDcTemplate(dcTempResp);
                    }else{
                        worksTempResp.add(templateResponse);
                        response.setWorksTemplate(worksTempResp);
                    }
                }
            }
        }
        response.setTemplates(templateResponses);
        return response;
    }

    private ListResponse<DesktopControllerVersionResponse> createDesktopControllerVersionListResponse(List<DesktopControllerVersionVO> versions) {
        List<DesktopControllerVersionResponse> responseList = new ArrayList<>();
        for (DesktopControllerVersionVO version : versions) {
            responseList.add(createDesktopControllerVersionResponse(version));
        }
        ListResponse<DesktopControllerVersionResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    @Override
    @ActionEvent(eventType = DesktopVersionEventTypes.EVENT_DESKTOP_MASTER_VERSION_ADD, eventDescription = "Adding desktop master template version")
    public DesktopMasterVersionResponse addDesktopMasterVersion(final AddDesktopMasterVersionCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final String format = cmd.getFormat();
        final String hypervisor = cmd.getHypervisor();
        final String versionName = cmd.getMasterVersionName();
        final String description = cmd.getDescription();
        final String masterVersion = cmd.getMasterVersion();
        Long zoneId = cmd.getZoneId();
        final String masterUrl = cmd.getMasterUrl();
        final Long masterOsTypeId = cmd.getMasterOsType();
        final String masterUploadType = cmd.getMasterUploadType();
        final Long templateId =cmd.getTemplateId();
        final String masterTemplateType = cmd.getMasterTemplateType();
        String templateName = "";

        final List<DesktopMasterVersionVO> versions = desktopMasterVersionDao.listAll();
        for (final DesktopMasterVersionVO version : versions) {
            final String otherVersion = version.getVersion();
            final String otherTemplateType = version.getType();
            if (otherTemplateType.equals(masterTemplateType) && otherVersion.equals(masterVersion)) {
                throw new InvalidParameterValueException("version '" + masterVersion + "' already exists.");
            }
        }

        if (compareVersions(masterVersion, MIN_DESKTOP_MASTER_VERSION) < 0) {
            throw new InvalidParameterValueException(String.format("New desktop master version cannot be added as %s is minimum version supported by Desktop Service", MIN_DESKTOP_CONTOLLER_VERSION));
        }
        if (zoneId != null && dataCenterDao.findById(zoneId) == null) {
            throw new InvalidParameterValueException("Invalid zone specified");
        }
        if ("url".equals(masterUploadType) && StringUtils.isEmpty(masterUrl)) {
            throw new InvalidParameterValueException(String.format("Invalid master URL for template specified, %s", masterUrl));
        }
        if (StringUtils.isEmpty(versionName)) {
            throw new InvalidParameterValueException(String.format("Invalid Version Name for template specified, %s", versionName));
        }
        DesktopMasterVersionVO desktopMasterVersionVO = null;
        VirtualMachineTemplate vmTemplate = null;
        VMTemplateVO template = null;
        try {
            if("url".equals(masterUploadType)){
                //vm_template 테이블에 master 템플릿 추가
                templateName = String.format("%s(Desktop Master Template)", versionName);
                vmTemplate = registerDesktopTemplateVersion(zoneId, templateName, masterUrl, hypervisor, masterOsTypeId, format);
                template = templateDao.findById(vmTemplate.getId());
            }else{
                template = templateDao.findById(templateId);
                List<VMTemplateZoneVO> templateZones = templateZoneDao.listByTemplateId(templateId);
                if (templateZones != null) {
                    for (VMTemplateZoneVO templateZone : templateZones) {
                        zoneId = templateZone.getZoneId();
                    }
                }
            }
            //desktop_master_version 테이블에 버전 추가
            desktopMasterVersionVO = new DesktopMasterVersionVO(versionName, masterVersion, description, template.getId() ,zoneId, masterUploadType, masterTemplateType);
            desktopMasterVersionVO = desktopMasterVersionDao.persist(desktopMasterVersionVO);
        } catch (URISyntaxException | IllegalAccessException | NoSuchFieldException | IllegalArgumentException | ResourceAllocationException ex) {
            LOGGER.error(String.format("Unable to register template for desktop master version, %s, with url: %s", templateName, masterUrl), ex);
            throw new CloudRuntimeException(String.format("Unable to register template for desktop master version, %s, with url: %s", templateName, masterUrl));
        }
        return createDesktopMasterVersionResponse(desktopMasterVersionVO);
    }

    @Override
    @ActionEvent(eventType = DesktopVersionEventTypes.EVENT_DESKTOP_CONTROLLER_VERSION_ADD, eventDescription = "Adding desktop controller template version")
    public DesktopControllerVersionResponse addDesktopControllerVersion(final AddDesktopControllerVersionCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final String format = cmd.getFormat();
        final String hypervisor = cmd.getHypervisor();
        final String versionName = cmd.getControllerVersionName();
        final String description = cmd.getDescription();
        final String controllerVersion = cmd.getControllerVersion();
        final Long zoneId = cmd.getZoneId();
        final String uploadType = cmd.getUploadType();
        final String dcUrl = cmd.getDcUrl();
        final String worksUrl = cmd.getWorksUrl();
        final Long dcOsTypeId = cmd.getDcOsType();
        final Long worksOsTypeId = cmd.getWorksOsType();
        final Long dcTemplateId =cmd.getDcTemplateId();
        final Long worksTemplateId =cmd.getWorksTemplateId();
        String templateName = "";

        final List<DesktopControllerVersionVO> versions = desktopControllerVersionDao.listAll();
        for (final DesktopControllerVersionVO version : versions) {
            final String otherVersion = version.getVersion();
            if (otherVersion.equals(controllerVersion)) {
                throw new InvalidParameterValueException("version '" + controllerVersion + "' already exists.");
            }
        }

        if (compareVersions(controllerVersion, MIN_DESKTOP_CONTOLLER_VERSION) < 0) {
            throw new InvalidParameterValueException(String.format("New desktop controller version cannot be added as %s is minimum version supported by Desktop Service", MIN_DESKTOP_CONTOLLER_VERSION));
        }
        if (zoneId != null && dataCenterDao.findById(zoneId) == null) {
            throw new InvalidParameterValueException("Invalid zone specified");
        }
        if ("url".equals(uploadType) && StringUtils.isEmpty(dcUrl)) {
            throw new InvalidParameterValueException(String.format("Invalid DC URL for template specified, %s", dcUrl));
        }
        if ("url".equals(uploadType) && StringUtils.isEmpty(worksUrl)) {
            throw new InvalidParameterValueException(String.format("Invalid Works URL for template specified, %s", worksUrl));
        }

        if (StringUtils.isEmpty(versionName)) {
            throw new InvalidParameterValueException(String.format("Invalid VersionName for template specified, %s", versionName));
        }

        Long dcZone = null;
        Long worksZone = null;
        VMTemplateVO template = null;
        VMTemplateVO dcTemplate = null;
        VMTemplateVO worksTemplate = null;
        DesktopControllerVersionVO desktopControllerVersionVO = null;
        VirtualMachineTemplate vmTemplate = null;
        try {
            if ("url".equals(uploadType)) {
                //desktop_controller_version 테이블에 버전 추가
                desktopControllerVersionVO = new DesktopControllerVersionVO(versionName, controllerVersion, description, zoneId, uploadType);
                desktopControllerVersionVO = desktopControllerVersionDao.persist(desktopControllerVersionVO);

                //vm_template 테이블에 dc 템플릿 추가
                templateName = String.format("%s(Desktop Controller DC-Template)", versionName);
                vmTemplate = registerDesktopTemplateVersion(zoneId, templateName, dcUrl, hypervisor, dcOsTypeId, format);
                template = templateDao.findById(vmTemplate.getId());

                //desktop_template_map 테이블에 DC template 매핑 데이터 추가
                desktopTemplateMapDao.persist(new DesktopTemplateMapVO(desktopControllerVersionVO.getId(), template.getId(), "dc"));

                //dc 템플릿에 세팅 추가
                Map<String, String> details = new HashMap<String, String>();
                details.put("rootDiskController", "virtio");
                template = templateDao.createForUpdate(template.getId());
                template.setDetails(details);
                templateDao.saveDetails(template);
                templateDao.update(template.getId(), template);

                //vm_template 테이블에 works 템플릿 추가
                templateName = String.format("%s(Desktop Controller Works-Template)", versionName);
                vmTemplate = registerDesktopTemplateVersion(zoneId, templateName, worksUrl, hypervisor ,worksOsTypeId ,format);
                template = templateDao.findById(vmTemplate.getId());

                //desktop_template_map 테이블에 works template 매핑 데이터 추가
                desktopTemplateMapDao.persist(new DesktopTemplateMapVO(desktopControllerVersionVO.getId(), template.getId(), "works"));
            } else {
                dcTemplate = templateDao.findById(dcTemplateId);
                List<VMTemplateZoneVO> dcTemplateZones = templateZoneDao.listByTemplateId(dcTemplateId);
                if (dcTemplateZones != null) {
                    for (VMTemplateZoneVO dcTemplateZone : dcTemplateZones) {
                        dcZone = dcTemplateZone.getZoneId();
                    }
                }
                worksTemplate = templateDao.findById(worksTemplateId);
                List<VMTemplateZoneVO> worksTemplateZones = templateZoneDao.listByTemplateId(worksTemplateId);
                if (worksTemplateZones != null) {
                    for (VMTemplateZoneVO worksTemplateZone : worksTemplateZones) {
                        worksZone = worksTemplateZone.getZoneId();
                    }
                }

                if (dcZone != worksZone) {
                    throw new InvalidParameterValueException("The template zone of dc and works does not match.");
                }
                //desktop_controller_version 테이블에 버전 추가
                desktopControllerVersionVO = new DesktopControllerVersionVO(versionName, controllerVersion, description, dcZone, uploadType);
                desktopControllerVersionVO = desktopControllerVersionDao.persist(desktopControllerVersionVO);

                //desktop_template_map 테이블에 DC template 매핑 데이터 추가
                desktopTemplateMapDao.persist(new DesktopTemplateMapVO(desktopControllerVersionVO.getId(), dcTemplate.getId(), "dc"));

                //desktop_template_map 테이블에 Works template 매핑 데이터 추가
                desktopTemplateMapDao.persist(new DesktopTemplateMapVO(desktopControllerVersionVO.getId(), worksTemplate.getId(), "works"));
            }
        } catch (URISyntaxException | IllegalAccessException | NoSuchFieldException | IllegalArgumentException | ResourceAllocationException ex) {
            LOGGER.error(String.format("Unable to register template for desktop controller version, %s, with url: %s", templateName, dcUrl), ex);
            throw new CloudRuntimeException(String.format("Unable to register template for desktop controller version, %s, with url: %s", templateName, dcUrl));
        }
        return createDesktopControllerVersionResponse(desktopControllerVersionVO);
    }

    private VirtualMachineTemplate registerDesktopTemplateVersion(final Long zoneId, final String templateName, final String url, final String hypervisor, final Long osTypeId, final String format)throws IllegalAccessException, NoSuchFieldException,
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

    @Override
    public ListResponse<DesktopControllerVersionResponse> listDesktopControllerVersions(final ListDesktopControllerVersionsCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        final Long zoneId = cmd.getZoneId();
        Filter searchFilter = new Filter(DesktopControllerVersionVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<DesktopControllerVersionVO> sb = desktopControllerVersionDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        SearchCriteria<DesktopControllerVersionVO> sc = sb.create();
        String keyword = cmd.getKeyword();
        if (versionId != null) {
            sc.setParameters("id", versionId);
        }
        if (zoneId != null) {
            SearchCriteria<DesktopControllerVersionVO> scc = desktopControllerVersionDao.createSearchCriteria();
            scc.addOr("zoneId", SearchCriteria.Op.EQ, zoneId);
            scc.addOr("zoneId", SearchCriteria.Op.NULL);
            sc.addAnd("zoneId", SearchCriteria.Op.SC, scc);
        }
        if(keyword != null){
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <DesktopControllerVersionVO> versions = desktopControllerVersionDao.search(sc, searchFilter);

        return createDesktopControllerVersionListResponse(versions);
    }

    private DesktopMasterVersionResponse createDesktopMasterVersionResponse(final DesktopMasterVersion desktopMasterVersion) {
        DesktopMasterVersionResponse response = new DesktopMasterVersionResponse();
        response.setObjectName("desktopmasterversion");
        response.setId(desktopMasterVersion.getUuid());
        response.setName(desktopMasterVersion.getName());
        response.setDescription(desktopMasterVersion.getDescription());
        response.setVersion(desktopMasterVersion.getVersion());
        response.setUploadType(desktopMasterVersion.getUploadType());
        response.setType(desktopMasterVersion.getType());
        response.setCreated(desktopMasterVersion.getCreated());
        if (desktopMasterVersion.getState() != null) {
            response.setState(desktopMasterVersion.getState().toString());
        }
        DataCenterVO zone = dataCenterDao.findById(desktopMasterVersion.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        TemplateJoinVO template = templateJoinDao.findById(desktopMasterVersion.getTemplateId());
        if (template != null) {
            response.setTemplateId(template.getUuid());
            response.setTemplateName(template.getName());
            response.setTemplateState(template.getState().toString());
            response.setTemplateOSType(template.getGuestOSName());
        }
        return response;
    }

    private ListResponse<DesktopMasterVersionResponse> createDesktopMasterVersionListResponse(List<DesktopMasterVersionVO> versions) {
        List<DesktopMasterVersionResponse> responseList = new ArrayList<>();
        for (DesktopMasterVersionVO version : versions) {
            responseList.add(createDesktopMasterVersionResponse(version));
        }
        ListResponse<DesktopMasterVersionResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    @Override
    public ListResponse<DesktopMasterVersionResponse> listDesktopMasterVersions(final ListDesktopMasterVersionsCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        final Long zoneId = cmd.getZoneId();
        Filter searchFilter = new Filter(DesktopMasterVersionVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<DesktopMasterVersionVO> sb = desktopMasterVersionDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        SearchCriteria<DesktopMasterVersionVO> sc = sb.create();
        String keyword = cmd.getKeyword();
        if (versionId != null) {
            sc.setParameters("id", versionId);
        }
        if (zoneId != null) {
            SearchCriteria<DesktopMasterVersionVO> scc = desktopMasterVersionDao.createSearchCriteria();
            scc.addOr("zoneId", SearchCriteria.Op.EQ, zoneId);
            scc.addOr("zoneId", SearchCriteria.Op.NULL);
            sc.addAnd("zoneId", SearchCriteria.Op.SC, scc);
        }
        if(keyword != null){
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <DesktopMasterVersionVO> versions = desktopMasterVersionDao.search(sc, searchFilter);

        return createDesktopMasterVersionListResponse(versions);
    }

    @Override
    @ActionEvent(eventType = DesktopVersionEventTypes.EVENT_DESKTOP_MASTER_VERSION_DELETE, eventDescription = "Deleting Desktop Master Version", async = true)
    public boolean deleteDesktopMasterVersion(final DeleteDesktopMasterVersionCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        DesktopMasterVersion version = desktopMasterVersionDao.findById(versionId);

        if (version == null) {
            throw new InvalidParameterValueException("Invalid desktop master version id specified");
        }
        // List<DesktopClusterVO> clusters = desktopClusterDao.listAllByDesktopVersion(versionId);
        // if (clusters.size() > 0) {
        //     throw new CloudRuntimeException(String.format("Unable to delete desktop master version ID: %s. Existing clusters currently using the version.", version.getUuid()));
        // }

        VMTemplateVO template = null;
        Long templateId = version.getTemplateId();
        String uploadType = version.getUploadType();

        template = templateDao.findByIdIncludingRemoved(templateId);
        if (template == null) {
            LOGGER.warn(String.format("Unable to find template associated with supported desktop master version ID: %s", version.getUuid()));
        }
        if ("url".equals(uploadType) && template != null && template.getRemoved() == null) {// upload type이 'url' 타입일 경우 템플릿까지 삭제, 'template' 타입 일 경우 템플릿은 삭제 안됨.
            try {
                deleteDesktopVersionTemplate(template.getId());
            } catch (IllegalAccessException | NoSuchFieldException | IllegalArgumentException ex) {
                LOGGER.error(String.format("Unable to delete ID: %s associated with supported desktop master version ID: %s", template.getUuid(), version.getUuid()), ex);
                throw new CloudRuntimeException(String.format("Unable to delete ID: %s associated with supported desktop master version ID: %s", template.getUuid(), version.getUuid()));
            }
        }
        return desktopMasterVersionDao.remove(version.getId());
    }

    @Override
    @ActionEvent(eventType = DesktopVersionEventTypes.EVENT_DESKTOP_CONTROLLER_VERSION_DELETE, eventDescription = "Deleting Desktop Controller Version", async = true)
    public boolean deleteDesktopContollerVersion(final DeleteDesktopControllerVersionCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        DesktopControllerVersion version = desktopControllerVersionDao.findById(versionId);
        String uploadType = version.getUploadType();
        if (version == null) {
            throw new InvalidParameterValueException("Invalid desktop controller version id specified");
        }
        List<DesktopClusterVO> clusters = desktopClusterDao.listAllByDesktopVersion(versionId);
        if (clusters.size() > 0) {
            throw new CloudRuntimeException(String.format("Unable to delete desktop controller version ID: %s. Existing clusters currently using the version.", version.getUuid()));
        }

        List<DesktopTemplateMapVO> templateList = desktopTemplateMapDao.listByVersionId(versionId);

        VMTemplateVO template = null;

        if (templateList != null && !templateList.isEmpty()) {
            for (DesktopTemplateMapVO templateMapVO : templateList) {
                template = templateDao.findByIdIncludingRemoved(templateMapVO.getTemplateId());
                if (template == null) {
                    LOGGER.warn(String.format("Unable to find template associated with supported desktop controller version ID: %s", version.getUuid()));
                }
                if ("url".equals(uploadType) && template != null && template.getRemoved() == null) {// upload type이 'url' 타입일 경우 템플릿까지 삭제, 'template' 타입 일 경우 템플릿은 삭제 안됨.
                    try {
                        deleteDesktopVersionTemplate(template.getId());
                    } catch (IllegalAccessException | NoSuchFieldException | IllegalArgumentException ex) {
                        LOGGER.error(String.format("Unable to delete ID: %s associated with supported desktop controller version ID: %s", template.getUuid(), version.getUuid()), ex);
                        throw new CloudRuntimeException(String.format("Unable to delete ID: %s associated with supported desktop controller version ID: %s", template.getUuid(), version.getUuid()));
                    }
                }
                desktopTemplateMapDao.remove(templateMapVO.getId());
            }
        }else{
            LOGGER.info("There are no registered templates for that desktop controller version.");
            //throw new CloudRuntimeException(String.format("There are no registered templates for that desktop controller version.", version.getUuid()));
        }
        return desktopControllerVersionDao.remove(version.getId());
    }

    private void deleteDesktopVersionTemplate(long templateId) throws IllegalAccessException, NoSuchFieldException, IllegalArgumentException {
        DeleteTemplateCmd deleteTemplateCmd = new DeleteTemplateCmd();
        deleteTemplateCmd = ComponentContext.inject(deleteTemplateCmd);
        deleteTemplateCmd.setId(templateId);
        deleteTemplateCmd.setIsDesktop(true);
        templateService.deleteTemplate(deleteTemplateCmd);
    }

    @Override
    @ActionEvent(eventType = DesktopVersionEventTypes.EVENT_DESKTOP_MASTER_VERSION_UPDATE, eventDescription = "Updating desktop master version")
    public DesktopMasterVersionResponse updateDesktopMasterVersion(final UpdateDesktopMasterVersionCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        DesktopMasterVersion.State state = null;
        DesktopMasterVersionVO version = desktopMasterVersionDao.findById(versionId);
        if (version == null) {
            throw new InvalidParameterValueException("Invalid desktop master version id specified");
        }
        try {
            state = DesktopMasterVersion.State.valueOf(cmd.getState());
        } catch (IllegalArgumentException iae) {
            throw new InvalidParameterValueException(String.format("Invalid value for %s parameter", ApiConstants.STATE));
        }
        if (!state.equals(version.getState())) {
            version = desktopMasterVersionDao.createForUpdate(version.getId());
            version.setState(state);
            if (!desktopMasterVersionDao.update(version.getId(), version)) {
                throw new CloudRuntimeException(String.format("Failed to update desktop master version ID: %s", version.getUuid()));
            }
            version = desktopMasterVersionDao.findById(versionId);
        }
        return  createDesktopMasterVersionResponse(version);
    }

    @Override
    @ActionEvent(eventType = DesktopVersionEventTypes.EVENT_DESKTOP_CONTROLLER_VERSION_UPDATE, eventDescription = "Updating desktop controller version")
    public DesktopControllerVersionResponse updateDesktopControllerVersion(final UpdateDesktopControllerVersionCmd cmd) {
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            throw new CloudRuntimeException("Desktop Service plugin is disabled");
        }
        final Long versionId = cmd.getId();
        DesktopControllerVersion.State state = null;
        DesktopControllerVersionVO version = desktopControllerVersionDao.findById(versionId);
        if (version == null) {
            throw new InvalidParameterValueException("Invalid desktop controller version id specified");
        }
        try {
            state = DesktopControllerVersion.State.valueOf(cmd.getState());
        } catch (IllegalArgumentException iae) {
            throw new InvalidParameterValueException(String.format("Invalid value for %s parameter", ApiConstants.STATE));
        }
        if (!state.equals(version.getState())) {
            version = desktopControllerVersionDao.createForUpdate(version.getId());
            version.setState(state);
            if (!desktopControllerVersionDao.update(version.getId(), version)) {
                throw new CloudRuntimeException(String.format("Failed to update desktop controller version ID: %s", version.getUuid()));
            }
            version = desktopControllerVersionDao.findById(versionId);
        }
        return  createDesktopControllerVersionResponse(version);
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!DesktopClusterService.DesktopServiceEnabled.value()) {
            return cmdList;
        }
        cmdList.add(ListDesktopControllerVersionsCmd.class);
        cmdList.add(ListDesktopMasterVersionsCmd.class);
        cmdList.add(AddDesktopControllerVersionCmd.class);
        cmdList.add(DeleteDesktopControllerVersionCmd.class);
        cmdList.add(UpdateDesktopControllerVersionCmd.class);
        cmdList.add(AddDesktopMasterVersionCmd.class);
        cmdList.add(DeleteDesktopMasterVersionCmd.class);
        cmdList.add(UpdateDesktopMasterVersionCmd.class);
        return cmdList;
    }
}