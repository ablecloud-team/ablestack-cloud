/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.agent.api;

public class UpdateHostHbaDeviceAnswer extends Answer {
    private boolean successMessage;
    private String vmName;
    private String xmlConfig;
    private boolean isAttach;

    public UpdateHostHbaDeviceAnswer() {
        super();
    }

    public UpdateHostHbaDeviceAnswer(boolean successMessage, String vmName, String xmlConfig, boolean isAttach) {
        super();
        this.successMessage = successMessage;
        this.vmName = vmName;
        this.xmlConfig = xmlConfig;
        this.isAttach = isAttach;
    }

    public String getVmName() {
        return vmName;
    }

    public String getXmlConfig() {
        return xmlConfig;
    }

    public boolean getIsAttach() {
        return isAttach;
    }

    public boolean isSuccessMessage() {
        return successMessage;
    }
}
