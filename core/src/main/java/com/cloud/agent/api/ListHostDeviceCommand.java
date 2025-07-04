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

public class ListHostDeviceCommand extends Command {

    private List<String> hostDevicesName;
    private List<String> hostDevicesText;
    private Long id;

    public ListHostDeviceCommand() {
    }

    public ListHostDeviceCommand(Long id) {
        super();
        this.id = id;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public List<String> getHostDevicesName() {
        return hostDevicesName;
    }

    public List<String> getHostDevicesText() {
        return hostDevicesText;
    }

    public Long getId() {
        return id;
    }

    public void setHostDevicesName(List<String> hostDevicesName) {
        this.hostDevicesName = hostDevicesName;
    }
}
