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

import org.apache.cloudstack.api.command.user.automation.controller.ListAutomationControllerCmd;
// import org.apache.cloudstack.api.command.admin.automation.AddAutomationControllerVersionCmd;
// import org.apache.cloudstack.api.command.admin.automation.DeleteAutomationControllerVersionCmd;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.api.response.ListResponse;
// import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.log4j.Logger;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.automation.controller.dao.AutomationControllerDao;
import com.cloud.automation.version.AutomationVersionService;
import com.cloud.utils.component.ManagerBase;
import com.cloud.user.AccountService;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.template.TemplateApiService;


import com.cloud.dc.DataCenterVO;

public class AutomationControllerManagerImpl extends ManagerBase implements AutomationControllerService {
    public static final Logger LOGGER = Logger.getLogger(AutomationControllerManagerImpl.class.getName());

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

    private AutomationControllerResponse createAutomationControllerResponse(final AutomationController automationController) {
        AutomationControllerResponse response = new AutomationControllerResponse();
        response.setObjectName("automationcontroller");
        response.setId(automationController.getUuid());
        response.setName(automationController.getName());
        response.setDescription(automationController.getDescription());
        response.getServiceIp(automationController.getServiceIp());
        response.getDomainId(automationController.getDomainId());
        response.setCreated(automationController.getCreated());
        response.getRemoved(automationController.getRemoved());
        if (automationController.getState() != null) {
            response.setState(automationController.getState().toString());
        }
        DataCenterVO zone = dataCenterDao.findById(automationController.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
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