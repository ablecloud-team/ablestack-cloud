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

import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.user.automation.controller.ListAutomationControllerCmd;

import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.command.user.automation.controller.AddAutomationControllerCmd;

import com.cloud.utils.component.PluggableService;

public interface AutomationControllerService extends PluggableService{

    AutomationController findById(final Long id);

    AutomationControllerResponse addAutomationControllerResponse(AutomationController automationController);

    ListResponse<AutomationControllerResponse> listAutomationController(ListAutomationControllerCmd cmd);

    boolean startAutomationController(long automationControllerId, boolean onCreate) throws CloudRuntimeException;
    AutomationController addAutomationController(AddAutomationControllerCmd cmd) throws CloudRuntimeException;
    boolean stopAutomationController(long automationControllerId) throws CloudRuntimeException;
    boolean deleteAutomationController(Long automationControllerId) throws CloudRuntimeException;

}