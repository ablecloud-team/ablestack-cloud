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


import org.apache.cloudstack.api.command.user.automation.version.ListAutomationControllerVersionCmd;
// import org.apache.cloudstack.api.command.admin.automation.version.DeleteAutomationControllerVersionCmd;
// import org.apache.cloudstack.api.command.admin.automation.version.AddAutomationControllerVersionCmd;
import org.apache.cloudstack.api.response.AutomationControllerVersionResponse;
import org.apache.cloudstack.api.response.ListResponse;

import com.cloud.utils.component.PluggableService;
// import com.cloud.utils.exception.CloudRuntimeException;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

public interface AutomationVersionService extends PluggableService, Configurable {

    static final ConfigKey<Boolean> AutomationServiceEnabled = new ConfigKey<Boolean>("Advanced", Boolean.class,
            "cloud.automation.service.enabled",
            "false",
            "Indicates whether Automation Service plugin is enabled or not. Management server restart needed on change",
            false);

    static final String MIN_AUTOMATION_CONTOLLER_VERSION = "1.0.0";
    static final String MIN_AUTOMATION_MASTER_VERSION = "1.0.0";
    ListResponse<AutomationControllerVersionResponse> listAutomationControllerVersion(ListAutomationControllerVersionCmd cmd);
    // boolean deleteAutomationContollerVersion(DeleteAutomationControllerVersionCmd cmd) throws CloudRuntimeException;
    // AutomationControllerVersionResponse addAutomationControllerVersion(AddAutomationControllerVersionCmd cmd);
}