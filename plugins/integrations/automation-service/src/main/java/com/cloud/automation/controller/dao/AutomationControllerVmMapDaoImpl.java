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
package com.cloud.automation.controller.dao;

import com.cloud.automation.controller.AutomationControllerVmMapVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class AutomationControllerVmMapDaoImpl extends GenericDaoBase<AutomationControllerVmMapVO, Long> implements AutomationControllerVmMapDao {

    private final SearchBuilder<AutomationControllerVmMapVO> automationIdSearch;
    private final SearchBuilder<AutomationControllerVmMapVO> automationIdAndVmType;
    private final SearchBuilder<AutomationControllerVmMapVO> automationIdAndNotVmType;

    public AutomationControllerVmMapDaoImpl() {
        automationIdSearch = createSearchBuilder();
        automationIdSearch.and("automationControllerId", automationIdSearch.entity().getAutomationControllerId(), SearchCriteria.Op.EQ);
        automationIdSearch.done();

        automationIdAndVmType = createSearchBuilder();
        automationIdAndVmType.and("automationControllerId", automationIdAndVmType.entity().getAutomationControllerId(), SearchCriteria.Op.EQ);
        automationIdAndVmType.and("type", automationIdAndVmType.entity().getType(), SearchCriteria.Op.EQ);
        automationIdAndVmType.done();

        automationIdAndNotVmType = createSearchBuilder();
        automationIdAndNotVmType.and("automationControllerId", automationIdAndNotVmType.entity().getAutomationControllerId(), SearchCriteria.Op.EQ);
        automationIdAndNotVmType.and("type", automationIdAndNotVmType.entity().getType(), SearchCriteria.Op.NEQ);
        automationIdAndNotVmType.done();

    }

    @Override
    public List<AutomationControllerVmMapVO> listByAutomationControllerId(long automationControllerId) {
        SearchCriteria<AutomationControllerVmMapVO> sc = automationIdSearch.create();
        sc.setParameters("automationControllerId", automationControllerId);
        return listBy(sc, null);
    }

    @Override
    public List<AutomationControllerVmMapVO> listByAutomationControllerIdAndVmType(long automationControllerId, String type) {
        SearchCriteria<AutomationControllerVmMapVO> sc = automationIdAndVmType.create();
        sc.setParameters("automationControllerId", automationControllerId);
        sc.setParameters("type", type);
        return listBy(sc);
    }

    @Override
    public List<AutomationControllerVmMapVO> listByAutomationControllerIdAndNotVmType(long automationControllerId, String type) {
        SearchCriteria<AutomationControllerVmMapVO> sc = automationIdAndNotVmType.create();
        sc.setParameters("automationControllerId", automationControllerId);
        sc.setParameters("type", type);
        return listBy(sc);
    }
}