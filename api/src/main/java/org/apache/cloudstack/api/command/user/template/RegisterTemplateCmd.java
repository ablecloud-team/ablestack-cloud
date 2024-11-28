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
package org.apache.cloudstack.api.command.user.template;

import com.cloud.hypervisor.Hypervisor;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.cloud.hypervisor.HypervisorGuru;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.GuestOSResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.template.VirtualMachineTemplate;

@APICommand(name = "registerTemplate", description = "Registers an existing template into the CloudStack cloud. ", responseObject = TemplateResponse.class, responseView = ResponseView.Restricted,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class RegisterTemplateCmd extends BaseCmd implements UserCmd {
    protected static Logger logger = LogManager.getLogger(RegisterTemplateCmd.class.getName());

    private static final String s_name = "registertemplateresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.BITS, type = CommandType.INTEGER, description = "32 or 64 bits support. 64 by default")
    private Integer bits;

    @Parameter(name = ApiConstants.DISPLAY_TEXT,
               type = CommandType.STRING,
               description = "템플릿의 표시 텍스트, 기본값은 '이름'입니다.",
               length = 4096)
    private String displayText;

    @Parameter(name = ApiConstants.FORMAT,
               type = CommandType.STRING,
               required = true,
               description = "템플릿의 포맷")
    private String format;

    @Parameter(name = ApiConstants.HYPERVISOR, type = CommandType.STRING, required = true, description = "템플릿의 대상 하이퍼바이저")
    protected String hypervisor;

    @Parameter(name = ApiConstants.IS_FEATURED, type = CommandType.BOOLEAN, description = "true if this template is a featured template, false otherwise")
    private Boolean featured;

    @Parameter(name = ApiConstants.IS_PUBLIC, type = CommandType.BOOLEAN, description = "true if the template is available to all accounts; default is true")
    private Boolean publicTemplate;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "템플릿의 이름")
    private String templateName;

    @Parameter(name = ApiConstants.OS_TYPE_ID,
               type = CommandType.UUID,
               entityType = GuestOSResponse.class,
               required = false,
               description = "이 템플릿의 운영체제 유형의 아이디")
    private Long osTypeId;

    @Parameter(name = ApiConstants.PASSWORD_ENABLED,
               type = CommandType.BOOLEAN,
               description = "true if the template supports the password reset feature; default is false")
    private Boolean passwordEnabled;

    @Parameter(name = ApiConstants.SSHKEY_ENABLED, type = CommandType.BOOLEAN, description = "true if the template supports the sshkey upload feature; default is false")
    private Boolean sshKeyEnabled;

    @Parameter(name = ApiConstants.IS_EXTRACTABLE, type = CommandType.BOOLEAN, description = "true if the template or its derivatives are extractable; default is false")
    private Boolean extractable;

    @Parameter(name = ApiConstants.REQUIRES_HVM, type = CommandType.BOOLEAN, description = "true if this template requires HVM")
    private Boolean requiresHvm;

    @Parameter(name = ApiConstants.URL,
               type = CommandType.STRING,
               required = true,
               length = 2048,
               description = "템플릿이 호스팅되는 URL입니다. http:// 및 https://가 포함된 URL 형식에 따릅니다.")
    private String url;

    @Parameter(name=ApiConstants.ZONE_ID, type=CommandType.UUID, entityType = ZoneResponse.class,
            required=false, description="템플릿이 호스팅될 Zone ID")
    protected Long zoneId;

    @Parameter(name = ApiConstants.DOMAIN_ID,
               type = CommandType.UUID,
               entityType = DomainResponse.class,
               description = "an optional domainId. If the account parameter is used, domainId must also be used.")
    private Long domainId;

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "an optional accountName. Must be used with domainId.")
    private String accountName;

    @Parameter(name = ApiConstants.CHECKSUM, type = CommandType.STRING, description = "the checksum value of this template. " + ApiConstants.CHECKSUM_PARAMETER_PREFIX_DESCRIPTION)
    private String checksum;

    @Parameter(name = ApiConstants.TEMPLATE_TAG, type = CommandType.STRING, description = "템플릿의 태그")
    private String templateTag;

    @Parameter(name = ApiConstants.PROJECT_ID, type = CommandType.UUID, entityType = ProjectResponse.class, description = "Register template for the project")
    private Long projectId;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP,
            description = "Template details in key/value pairs using format details[i].keyname=keyvalue. Example: details[0].hypervisortoolsversion=xenserver61")
    protected Map details;

    @Parameter(name = ApiConstants.IS_DYNAMICALLY_SCALABLE,
               type = CommandType.BOOLEAN,
               description = "true if template contains XS/VMWare tools inorder to support dynamic scaling of VM cpu/memory")
    protected Boolean isDynamicallyScalable;

    @Deprecated
    @Parameter(name = ApiConstants.ROUTING, type = CommandType.BOOLEAN, description = "true if the template type is routing i.e., if template is used to deploy router")
    protected Boolean isRoutingType;

    @Parameter(name=ApiConstants.ZONE_ID_LIST,
            type=CommandType.LIST,
            collectionType = CommandType.UUID,
            entityType = ZoneResponse.class,
            required=false,
            description="A list of zone ids where the template will be hosted. Use this parameter if the template needs " +
                    "to be registered to multiple zones in one go. Use zoneid if the template " +
                    "needs to be registered to only one zone." +
                    "Passing only -1 to this will cause the template to be registered as a cross " +
                    "zone template and will be copied to all zones. ")
    protected List<Long> zoneIds;

    @Parameter(name=ApiConstants.DIRECT_DOWNLOAD,
                type = CommandType.BOOLEAN,
                description = "true if template should bypass Secondary Storage and be downloaded to Primary Storage on deployment")
    private Boolean directDownload;

    @Parameter(name=ApiConstants.DEPLOY_AS_IS,
            type = CommandType.BOOLEAN,
            description = "(VMware only) true if VM deployments should preserve all the configurations defined for this template", since = "4.15.1")
    protected Boolean deployAsIs;

    @Parameter(name=ApiConstants.DESKTOP_CHECK, type = CommandType.BOOLEAN, required=false, description="Verify that it is a desktop template.")
    protected Boolean isDesktop;

    @Parameter(name = ApiConstants.TEMPLATE_TYPE, type = CommandType.STRING,
            description = "the type of the template. Valid options are: USER/VNF (for all users) and SYSTEM/ROUTING/BUILTIN (for admins only).",
            since = "4.19.0")
    private String templateType;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Integer getBits() {
        return bits;
    }

    public String getDisplayText() {
        return StringUtils.isEmpty(displayText) ? templateName : displayText;
    }

    public void setDisplayText(String displayText) {
        this.displayText = displayText;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getHypervisor() {
        return hypervisor;
    }

    public void setHypervisor(String hypervisor) {
        this.hypervisor = hypervisor;
    }

    public Boolean isFeatured() {
        return featured;
    }

    public Boolean isPublic() {
        return publicTemplate;
    }

    public void setPublic(Boolean publicTemplate) {
        this.publicTemplate = publicTemplate;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public Long getOsTypeId() {
        return osTypeId;
    }

    public void setOsTypeId(Long osTypeId) {
        this.osTypeId = osTypeId;
    }

    public Boolean isPasswordEnabled() {
        return passwordEnabled;
    }

    public Boolean isSshKeyEnabled() {
        return sshKeyEnabled;
    }

    public Boolean isExtractable() {
        return extractable;
    }

    public Boolean getRequiresHvm() {
        return requiresHvm;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<Long> getZoneIds() {
        // This function will return null when the zoneId
        //is -1 which means all zones.
        if (zoneIds != null && !(zoneIds.isEmpty())) {
            if ((zoneIds.size() == 1) && (zoneIds.get(0) == -1L))
                return null;
            else
                return zoneIds;
        }
        if (zoneId == null)
            return null;
        if (zoneId!= null && zoneId == -1)
            return null;
        List<Long> zones = new ArrayList<>();
        zones.add(zoneId);
        return zones;
    }

    public Long getDomainId() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getTemplateTag() {
        return templateTag;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public void setZoneId(Long zoneId) {
        this.zoneId = zoneId;
    }

    public Map getDetails() {
        if (details == null || details.isEmpty()) {
            return null;
        }

        Collection paramsCollection = details.values();
        Map params = (Map)(paramsCollection.toArray())[0];
        return params;
    }

    public Boolean isDynamicallyScalable() {
        return isDynamicallyScalable == null ? Boolean.FALSE : isDynamicallyScalable;
    }

    public Boolean isRoutingType() {
        return isRoutingType;
    }

    public boolean isDirectDownload() {
        return directDownload == null ? false : directDownload;
    }

    public boolean isDeployAsIs() {
        return Hypervisor.HypervisorType.VMware.toString().equalsIgnoreCase(hypervisor) &&
                Boolean.TRUE.equals(deployAsIs);
    }

    public Boolean isDesktop() {
        return isDesktop;
    }

    public void setIsDesktop(Boolean isDesktop) {
        this.isDesktop = isDesktop;
    }

    public String getTemplateType() {
        return templateType;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    public ApiCommandResourceType getInstanceType() {
        return ApiCommandResourceType.Template;
    }

    @Override
    public long getEntityOwnerId() {
        Long accountId = _accountService.finalyzeAccountId(accountName, domainId, projectId, true);
        if (accountId == null) {
            return CallContext.current().getCallingAccount().getId();
        }

        return accountId;
    }

    @Override
    public void execute() throws ResourceAllocationException {
        try {
            validateParameters();

            VirtualMachineTemplate template = _templateService.registerTemplate(this);
            if (template != null) {
                ListResponse<TemplateResponse> response = new ListResponse<>();
                List<TemplateResponse> templateResponses = _responseGenerator.createTemplateResponses(getResponseView(),
                        template, getZoneIds(), false);
                response.setResponses(templateResponses);
                response.setResponseName(getCommandName());
                setResponseObject(response);
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "템플릿 등록 실패");
            }
        } catch (URISyntaxException ex1) {
            logger.info(ex1);
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, ex1.getMessage());
        }
    }

    protected void validateParameters() {
        if ((zoneId != null) && (zoneIds != null && !zoneIds.isEmpty()))
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "zoneid와 zoneids를 동시에 지정할 수 없습니다.");

        if (zoneId == null && (zoneIds == null || zoneIds.isEmpty()))
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "zoneid 또는 zoneids가 필요합니다. 둘 다 null일 수 없습니다.");

        if (zoneIds != null && zoneIds.size() > 1 && zoneIds.contains(-1L))
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                    "매개변수 zoneids는 모든 zone(-1) 옵션을 다른 zone과 결합할 수 없습니다.");

        String customHypervisor = HypervisorGuru.HypervisorCustomDisplayName.value();
        if (isDirectDownload() && !(getHypervisor().equalsIgnoreCase(Hypervisor.HypervisorType.KVM.toString())
                || getHypervisor().equalsIgnoreCase(customHypervisor))) {
                    throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("매개변수 직접 다운로드는 KVM 또는 %s 템플릿에만 허용됩니다.", customHypervisor));
        }

        if (!isDeployAsIs() && osTypeId == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "게스트 OS 유형을 입력하세요.");
        }
    }
}
