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

package com.cloud.automation.resource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Inject;
import java.util.Date;

import org.apache.cloudstack.api.command.user.automation.resource.ListAutomationDeployedResourceCmd;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiConstants.VMDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.user.automation.resource.AddDeployedResourceGroupCmd;
import org.apache.cloudstack.api.command.user.automation.resource.AddDeployedUnitResourceCmd;
import org.apache.cloudstack.api.command.user.automation.resource.DeleteDeployedResourceGroupCmd;
import org.apache.cloudstack.api.command.user.automation.resource.DeleteDeployedUnitResourceCmd;
import org.apache.cloudstack.api.command.user.automation.resource.UpdateDeployedResourceGroupCmd;
import org.apache.cloudstack.api.response.AutomationDeployedResourceResponse;
import org.apache.cloudstack.api.response.AutomationDeployedUnitResourceResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.automation.resource.dao.AutomationDeployedResourceDao;
import com.cloud.automation.resource.dao.AutomationDeployedUnitResourceDao;
import com.cloud.automation.version.AutomationVersionService;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.user.AccountService;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.event.ActionEvent;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.projects.Project;

public class AutomationResourceManagerImpl extends ManagerBase implements AutomationResourceService {

    @Inject
    private AutomationDeployedResourceDao automationDeployedResourceDao;
    @Inject
    protected AccountService accountService;
    @Inject
    private AccountManager accountManager;
    @Inject
    private AccountDao accountDao;
    @Inject
    private AutomationDeployedUnitResourceDao automationDeployedUnitResourceDao;
    @Inject
    protected UserVmJoinDao userVmJoinDao;
    @Inject
    private DataCenterDao dataCenterDao;

    private AutomationDeployedResourceResponse createAutomationDeployedResourceResponse(final AutomationDeployedResource automationDeployedResource) {
        AutomationDeployedResourceResponse response = new AutomationDeployedResourceResponse();
        response.setObjectName("automationdeployedresource");
        response.setId(automationDeployedResource.getUuid());
        response.setName(automationDeployedResource.getName());
        response.setDescription(automationDeployedResource.getDescription());
        response.setAccessInfo(automationDeployedResource.getAccessInfo());
        response.setCreated(automationDeployedResource.getCreated());
        response.setLastUpdated(automationDeployedResource.getLastUpdated());
        AccountVO account = accountDao.findById(automationDeployedResource.getAccountId());
        response.setAccountName(account.getName());

        if (automationDeployedResource.getState() != null) {
            response.setState(automationDeployedResource.getState().toString());
        }

        List<AutomationDeployedUnitResourceResponse> deployedUnitResourceResponses = new ArrayList<AutomationDeployedUnitResourceResponse>();
        List<AutomationDeployedUnitResourceVO> deployedUnitResourceList = automationDeployedUnitResourceDao.listAllByDeployedResourceUnit(automationDeployedResource.getId());
        List<UserVmResponse> deployedVmResponses = new ArrayList<UserVmResponse>();

        ResponseView respView = ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountService.isRootAdmin(caller.getId())) {
            respView = ResponseView.Full;
        }
        String responseName = "controlvmlist";

        if (deployedUnitResourceList != null && !deployedUnitResourceList.isEmpty()) {
            for (AutomationDeployedUnitResourceVO unitResourceMapVO : deployedUnitResourceList) {
                AutomationDeployedUnitResourceVO unitResource = automationDeployedUnitResourceDao.findById(unitResourceMapVO.getId());
                if (unitResource != null) {
                    AutomationDeployedUnitResourceResponse unitResourceResponse = new AutomationDeployedUnitResourceResponse();
                    UserVmJoinVO userVM = userVmJoinDao.findById(unitResource.getDeployedVmId());

                    unitResourceResponse.setServiceUnitName(unitResource.getServiceUnitName());
                    unitResourceResponse.setState(unitResource.getState().toString());
                    unitResourceResponse.setCreated(unitResource.getCreated());
                    deployedUnitResourceResponses.add(unitResourceResponse);

                    if (userVM != null){
                        unitResourceResponse.setVmName(userVM.getName());
                        UserVmResponse dvmResponse = ApiDBUtils.newUserVmResponse(respView, responseName, userVM, EnumSet.of(VMDetails.nics), caller);

                        //Exclude duplicate virtual machine list
                        if(deployedVmResponses.size() > 0){
                            boolean uniqueCheckFlag = true;
                            for (UserVmResponse dvr : deployedVmResponses) {
                                if(dvr.getId().equals(dvmResponse.getId())){
                                    uniqueCheckFlag = false;
                                }
                            }
                            if(uniqueCheckFlag){
                                deployedVmResponses.add(dvmResponse);
                            }
                        } else {
                            deployedVmResponses.add(dvmResponse);
                        }
                    }
                }
            }
        }

        DataCenterVO zone = dataCenterDao.findById(automationDeployedResource.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }

        response.setDeployedUnitServices(deployedUnitResourceResponses);
        response.setDeployedVms(deployedVmResponses);

        return response;
    }

    @Override
    public ListResponse<AutomationDeployedResourceResponse> listAutomationDeployedResource(final ListAutomationDeployedResourceCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long id = cmd.getId();
        final String name = cmd.getName();
        final Long zoneId = cmd.getZoneId();
        String keyword = cmd.getKeyword();
        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<Long>();
        Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, Project.ListProjectResourcesCriteria>(null, false, null);
        accountManager.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(AutomationDeployedResourceVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<AutomationDeployedResourceVO> sb = automationDeployedResourceDao.createSearchBuilder();

        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("accountId", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getState(), SearchCriteria.Op.LIKE);
        SearchCriteria<AutomationDeployedResourceVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }
        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }
        if(keyword != null){
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <AutomationDeployedResourceVO> resources = automationDeployedResourceDao.search(sc, searchFilter);

        return createAutomationDeployedResourceListResponse(resources);
    }

    private ListResponse<AutomationDeployedResourceResponse> createAutomationDeployedResourceListResponse(List<AutomationDeployedResourceVO> resources) {
        List<AutomationDeployedResourceResponse> responseList = new ArrayList<>();
        for (AutomationDeployedResourceVO resource : resources) {
            responseList.add(createAutomationDeployedResourceResponse(resource));
        }
        ListResponse<AutomationDeployedResourceResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    private AutomationDeployedResourceResponse createAutomationDeployedResourceGroupResponse(final AutomationDeployedResource automationDeployedResource) {
        AutomationDeployedResourceResponse response = new AutomationDeployedResourceResponse();
        response.setObjectName("automationdeployedresource");
        response.setId(automationDeployedResource.getUuid());
        response.setName(automationDeployedResource.getName());
        response.setAccountId(automationDeployedResource.getAccountId());
        response.setDescription(automationDeployedResource.getDescription());
        response.setAccessInfo(automationDeployedResource.getAccessInfo());
        response.setCreated(automationDeployedResource.getCreated());
        response.setLastUpdated(automationDeployedResource.getLastUpdated());
        if (automationDeployedResource.getState() != null) {
            response.setState(automationDeployedResource.getState().toString());
        }

        return response;
    }

    private AutomationDeployedUnitResourceResponse createAutomationDeployedUnitResourceResponse(final AutomationDeployedUnitResource automationDeployedUnitResource) {
        AutomationDeployedUnitResourceResponse response = new AutomationDeployedUnitResourceResponse();
        response.setObjectName("automationdeployedunitresource");
        response.setId(automationDeployedUnitResource.getUuid());
        response.setServiceUnitName(automationDeployedUnitResource.getServiceUnitName());
        response.setCreated(automationDeployedUnitResource.getCreated());
        if (automationDeployedUnitResource.getState() != null) {
            response.setState(automationDeployedUnitResource.getState().toString());
        }
        return response;
    }

    @Override
    // @ActionEvent(eventType = AutomationResourceEventTypes.EVENT_AUTOMATION_DEPLOYED_RESOURCE_ADD, eventDescription = "Adding automation deployed resource")
    public AutomationDeployedResourceResponse addDeployedResourceGroup(final AddDeployedResourceGroupCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        final Long accountId = caller.getAccountId();
        final Long domainId = caller.getDomainId();
        final String name = cmd.getName();
        final String description = cmd.getDescription();
        final String accessInfo = cmd.getAccessInfo();
        final Long zoneId = cmd.getZoneId();
        final Long controllerId = cmd.getControllerId();

        final List<AutomationDeployedResourceVO> serviceGroupList = automationDeployedResourceDao.listAll();
        for (final AutomationDeployedResourceVO serviceGroup : serviceGroupList) {
            if(serviceGroup.getName().equals(name) && serviceGroup.getAccountId() == accountId && serviceGroup.getDomainId() == domainId){
                throw new InvalidParameterValueException("Automation Service '" + name + "' already exists.");
            }
        }

        AutomationDeployedResourceVO automationDeployedResourceVO = null;
        automationDeployedResourceVO = new AutomationDeployedResourceVO(accountId, domainId, zoneId, controllerId, name, description, accessInfo);
        automationDeployedResourceVO = automationDeployedResourceDao.persist(automationDeployedResourceVO);

        return createAutomationDeployedResourceGroupResponse(automationDeployedResourceVO);
    }

    @Override
    // @ActionEvent(eventType = AutomationResourceEventTypes.EVENT_AUTOMATION_DEPLOYED_UNIT_RESOURCE_ADD, eventDescription = "Adding automation deployed unit resource")
    public AutomationDeployedUnitResourceResponse addDeployedUnitResource(final AddDeployedUnitResourceCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long deployedGroupId = cmd.getDeployedGroupId();
        final Long deployedVmId = cmd.getDeployedVmId();
        final String serviceUnitName = cmd.getServiceUnitName();
        final String state = cmd.getState();

        AutomationDeployedUnitResourceVO automationDeployedUnitResourceVO = null;
        automationDeployedUnitResourceVO = new AutomationDeployedUnitResourceVO(deployedGroupId, deployedVmId, serviceUnitName, state);
        automationDeployedUnitResourceVO = automationDeployedUnitResourceDao.persist(automationDeployedUnitResourceVO);

        return createAutomationDeployedUnitResourceResponse(automationDeployedUnitResourceVO);
    }

    @Override
    @ActionEvent(eventType = AutomationResourceEventTypes.EVENT_AUTOMATION_DEPLOYED_RESOURCE_DELETE, eventDescription = "Deleting automation deployed resource")
    public void deleteDeployedResource(final DeleteDeployedResourceGroupCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }

        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        final Long accountId = caller.getAccountId();
        final Long domainId = caller.getDomainId();
        final String name = cmd.getName();
        final Long zoneId = cmd.getZoneId();

        List<AutomationDeployedResourceVO> resourceList = automationDeployedResourceDao.listAll();
        for (AutomationDeployedResourceVO resource : resourceList) {
            if(resource.getAccountId() == accountId && resource.getDomainId() == domainId && resource.getZoneId() == zoneId && resource.getName().equals(name)){
                //그룹 id정보를 가져와야 함
                final Long deployedGroupId = resource.getId();

                List<AutomationDeployedUnitResourceVO> deployedUnitGroupList = automationDeployedUnitResourceDao.listAllByDeployedResourceUnit(deployedGroupId);
                if (!deployedUnitGroupList.isEmpty()) {
                    SearchBuilder<AutomationDeployedUnitResourceVO> listByDeployedUnitGroup = automationDeployedUnitResourceDao.createSearchBuilder();
                    listByDeployedUnitGroup.and("deployedGroupId", listByDeployedUnitGroup.entity().getDeployedGroupId(), SearchCriteria.Op.EQ);
                    listByDeployedUnitGroup.done();
                    SearchCriteria<AutomationDeployedUnitResourceVO> sc = listByDeployedUnitGroup.create();
                    sc.setParameters("deployedGroupId", deployedGroupId);

                    automationDeployedUnitResourceDao.lockRows(sc, null, true);

                    automationDeployedUnitResourceDao.remove(sc);
                }

                //그룹 삭제
                automationDeployedResourceDao.remove(deployedGroupId);
            }
        }
    }

    @Override
    // @ActionEvent(eventType = AutomationResourceEventTypes.EVENT_AUTOMATION_DEPLOYED_UNIT_RESOURCE_DELETE, eventDescription = "Deleting automation deployed unit resource")
    public void deleteDeployedUnitResource(final DeleteDeployedUnitResourceCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }

        final Long deployedGroupId = cmd.getDeployedGroupId();

        SearchBuilder<AutomationDeployedUnitResourceVO> listByDeployedUnitGroup = automationDeployedUnitResourceDao.createSearchBuilder();
        listByDeployedUnitGroup.and("deployedGroupId", listByDeployedUnitGroup.entity().getDeployedGroupId(), SearchCriteria.Op.EQ);
        listByDeployedUnitGroup.done();
        SearchCriteria<AutomationDeployedUnitResourceVO> sc = listByDeployedUnitGroup.create();
        sc.setParameters("deployedGroupId", deployedGroupId);

        automationDeployedUnitResourceDao.lockRows(sc, null, true);

        automationDeployedUnitResourceDao.remove(sc);
    }

    @Override
    // @ActionEvent(eventType = AutomationResourceEventTypes.EVENT_AUTOMATION_DEPLOYED_RESOURCE_UPDATE, eventDescription = "Updating automation deployed resource")
    public AutomationDeployedResourceResponse updateDeployedResourceGroup(final UpdateDeployedResourceGroupCmd cmd) {
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            throw new CloudRuntimeException("Automation Service plugin is disabled");
        }
        final Long deployedGroupId = cmd.getId();
        final String accessInfo = cmd.getAccessInfo();
        AutomationDeployedResource.State state = null;
        AutomationDeployedResourceVO resourceGroup = automationDeployedResourceDao.findById(deployedGroupId);
        if (resourceGroup == null) {
            throw new InvalidParameterValueException("Invalid automation deployed resource id specified");
        }
        try {
            state = AutomationDeployedResource.State.valueOf(cmd.getState());
        } catch (IllegalArgumentException iae) {
            throw new InvalidParameterValueException(String.format("Invalid value for %s parameter", ApiConstants.STATE));
        }
        resourceGroup = automationDeployedResourceDao.createForUpdate(resourceGroup.getId());
        resourceGroup.setAccessInfo(accessInfo);
        resourceGroup.setState(state);
        resourceGroup.setLastUpdated(new Date());
        if (!automationDeployedResourceDao.update(resourceGroup.getId(), resourceGroup)) {
            throw new CloudRuntimeException(String.format("Failed to update desktop master version ID: %s", resourceGroup.getUuid()));
        }
        resourceGroup = automationDeployedResourceDao.findById(deployedGroupId);
        return createAutomationDeployedResourceGroupResponse(resourceGroup);
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!AutomationVersionService.AutomationServiceEnabled.value()) {
            return cmdList;
        }
        cmdList.add(ListAutomationDeployedResourceCmd.class);
        cmdList.add(AddDeployedResourceGroupCmd.class);
        cmdList.add(AddDeployedUnitResourceCmd.class);
        cmdList.add(DeleteDeployedResourceGroupCmd.class);
        cmdList.add(DeleteDeployedUnitResourceCmd.class);
        cmdList.add(UpdateDeployedResourceGroupCmd.class);
        return cmdList;
    }
}