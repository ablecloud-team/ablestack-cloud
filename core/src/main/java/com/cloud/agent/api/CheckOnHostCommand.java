//
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
//

package com.cloud.agent.api;

import com.google.gson.annotations.SerializedName;

import java.util.List;

import com.cloud.agent.api.to.HostTO;
import com.cloud.host.Host;
import com.cloud.storage.Volume;

public class CheckOnHostCommand extends Command {
    HostTO host;
    @SerializedName(value = "reportCheckFailureIfOneStorageIsDown", alternate = {"reportIfHeartBeatFailedForOneStoragePool"})
    boolean reportIfHeartBeatFailedForOneStoragePool;
    private String volumeList;

    protected CheckOnHostCommand() {
    }

    public CheckOnHostCommand(Host host) {
        this.host = new HostTO(host);
        setWait(20);
    }

    public CheckOnHostCommand(Host host, boolean reportIfHeartBeatFailedForOneStoragePool) {
        this(host);
        this.reportIfHeartBeatFailedForOneStoragePool = reportIfHeartBeatFailedForOneStoragePool;
    }

    public CheckOnHostCommand(Host host, boolean reportIfHeartBeatFailedForOneStoragePool, final List<Volume> volumeList) {
        this(host, reportIfHeartBeatFailedForOneStoragePool);
        final StringBuilder stringBuilder = new StringBuilder();
        for (final Volume v : volumeList) {
            stringBuilder.append(v.getPath()).append(",");
        }

        this.volumeList = stringBuilder.length() == 0 ? "" : stringBuilder.substring(0, stringBuilder.length() - 1);
    }

    public HostTO getHost() {
        return host;
    }

    public boolean shouldReportIfHeartBeatFailedForOneStoragePool() {
        return reportIfHeartBeatFailedForOneStoragePool;
    }

    public String getVolumeList() {
        return volumeList;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
