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

// import java.util.Collections;
import java.util.List;

public class ListHostScsiDeviceAnswer extends Answer {
    private List<String> hostDevicesNames;
    private List<String> hostDevicesTexts;
    private List<Boolean> hasPartitions;

    public ListHostScsiDeviceAnswer(boolean success, List<String> hostDevicesNames, List<String> hostDevicesTexts, List<Boolean> hasPartitions) {
        super();
        this.hostDevicesNames = hostDevicesNames;
        this.hostDevicesTexts = hostDevicesTexts;
        this.hasPartitions = hasPartitions;
    }

    // Getters and setters
    public List<String> getHostDevicesNames() {
        return hostDevicesNames;
    }

    public void setHostDevicesNames(List<String> hostDevicesNames) {
        this.hostDevicesNames = hostDevicesNames;
    }

    public List<String> getHostDevicesTexts() {
        return hostDevicesTexts;
    }

    public void setHostDevicesTexts(List<String> hostDevicesTexts) {
        this.hostDevicesTexts = hostDevicesTexts;
    }

    public List<Boolean> getHasPartitions() {
        return hasPartitions;
    }

}
