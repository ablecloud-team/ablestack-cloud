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

import java.util.List;

public class ListVhbaDevicesAnswer extends Answer {
    private boolean success;
    private List<ListVhbaDevicesCommand.VhbaDeviceInfo> vhbaDevices;

    public ListVhbaDevicesAnswer() {
        super();
    }

    public ListVhbaDevicesAnswer(boolean success, List<ListVhbaDevicesCommand.VhbaDeviceInfo> vhbaDevices) {
        super();
        this.success = success;
        this.vhbaDevices = vhbaDevices;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public List<ListVhbaDevicesCommand.VhbaDeviceInfo> getVhbaDevices() {
        return vhbaDevices;
    }

    public void setVhbaDevices(List<ListVhbaDevicesCommand.VhbaDeviceInfo> vhbaDevices) {
        this.vhbaDevices = vhbaDevices;
    }
} 