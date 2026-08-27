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

public class ListHostLunDeviceCommand extends Command {

    public static final String MODE_SINGLE = "single";
    public static final String MODE_MULTIPATH = "multipath";

    private final Long id;
    private String lunPathMode = MODE_SINGLE;
    private List<String> hostDevicesName;
    private List<String> hostDevicesText;

    public ListHostLunDeviceCommand(Long id) {
        this(id, MODE_SINGLE);
    }

    public ListHostLunDeviceCommand(Long id, String lunPathMode) {
        this.id = id;
        if (lunPathMode != null && !lunPathMode.trim().isEmpty()) {
            this.lunPathMode = lunPathMode.trim().toLowerCase();
        }
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public Long getId() {
        return id;
    }

    public String getLunPathMode() {
        return lunPathMode;
    }

    public void setLunPathMode(String lunPathMode) {
        if (lunPathMode != null && !lunPathMode.trim().isEmpty()) {
            this.lunPathMode = lunPathMode.trim().toLowerCase();
        }
    }

    public List<String> getHostDevicesName() {
        return hostDevicesName;
    }

    public void setHostDevicesName(List<String> hostDevicesName) {
        this.hostDevicesName = hostDevicesName;
    }

    public List<String> getHostDevicesText() {
        return hostDevicesText;
    }

    public void setHostDevicesText(List<String> hostDevicesText) {
        this.hostDevicesText = hostDevicesText;
    }
}
