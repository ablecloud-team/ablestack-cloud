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

package org.apache.cloudstack.api.response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;

/** Sanitized Host receipt and current shared settings; never returns API credentials. */
public class NetBackupHostConfigurationResponse extends BaseResponse {
    @SerializedName("hostid") @Param(description = "Mold Host UUID") public String hostid;
    @SerializedName("hostname") @Param(description = "Mold Host name") public String hostname;
    @SerializedName("hoststate") @Param(description = "Mold Host connection state") public String hoststate;
    @SerializedName("state") @Param(description = "NOT_CONFIGURED, RUNNING, CHECKED, COMPLETED, FAILED, UNCONFIRMED or RECONFIGURATION_REQUIRED") public String state;
    @SerializedName("operationid") @Param(description = "Durable Host configuration operation UUID") public String operationid;
    @SerializedName("action") @Param(description = "CHECK or APPLY") public String action;
    @SerializedName("step") @Param(description = "Current configuration stage") public String step;
    @SerializedName("message") @Param(description = "Sanitized configuration outcome") public String message;
    @SerializedName("checkedat") @Param(description = "Host inspection time in epoch milliseconds") public Long checkedat;
    @SerializedName("policyname") @Param(description = "Existing NetBackup Host policy") public String policyname;
    @SerializedName("clientname") @Param(description = "Verified NetBackup Client") public String clientname;
    @SerializedName("moldurl") @Param(description = "Mold API endpoint reachable from this Host") public String moldurl;
    @SerializedName("netbackupurl") @Param(description = "Effective Zone NetBackup API endpoint, inheriting Global when no Zone override exists") public String netbackupurl;
    @SerializedName("credentialsconfigured") @Param(description = "Effective NetBackup API key is configured") public boolean credentialsconfigured;
    @SerializedName("moldcredentialsavailable") @Param(description = "Current administrator has an existing API key pair for Host integration") public boolean moldcredentialsavailable;
    @SerializedName("frameworkenabled") @Param(description = "Backup framework is enabled globally and for this Zone") public boolean frameworkenabled;
    @SerializedName("frameworkglobalenabled") @Param(description = "Global backup framework master switch") public boolean frameworkglobalenabled;
    @SerializedName("frameworkzoneenabled") @Param(description = "Effective Zone backup framework setting, inheriting Global when unset") public boolean frameworkzoneenabled;
    @SerializedName("settingsource") @Param(description = "Source scope of each effective setting; contains setting names and scopes, never credentials") public Map<String, String> settingsource = new HashMap<>();
    @SerializedName("providerenabled") @Param(description = "NetBackup provider is registered for this Zone") public boolean providerenabled;
    @SerializedName("stagingenabled") @Param(description = "Common staging is enabled") public boolean stagingenabled;
    @SerializedName("stagingpath") @Param(description = "Effective common staging provider directory") public String stagingpath;
    @SerializedName("bootstrappath") @Param(description = "Required policy Backup Selections directory") public String bootstrappath;
    @SerializedName("vmschedulebackup") @Param(description = "Global NetBackup VM schedule mode; true requires VM schedules after offering assignment") public boolean vmschedulebackup;
    @SerializedName("incrementalbackup") @Param(description = "Effective Cluster incremental backup setting") public boolean incrementalbackup;
    @SerializedName("backupchainsize") @Param(description = "Global KVM incremental backup chain size") public int backupchainsize;
    @SerializedName("configured") @Param(description = "Host policy configuration file exists") public boolean configured;
    @SerializedName("clientinstalled") @Param(description = "NetBackup Client is installed") public boolean clientinstalled;
    @SerializedName("hooksready") @Param(description = "Policy Hook files exist") public boolean hooksready;
    @SerializedName("timeractive") @Param(description = "Restore watcher timer is active") public boolean timeractive;
    @SerializedName("check") @Param(description = "Completed preflight checks") public List<Check> check = new ArrayList<>();
    @SerializedName("virtualmachine") @Param(description = "Assigned VMs currently owned by this Host; hostless placement is determined at execution") public List<Target> virtualmachine = new ArrayList<>();
    @SerializedName("configuredbootstrappath") @Param(description = "Bootstrap path currently recorded in the Host policy configuration") public String configuredbootstrappath;
    @SerializedName("inspectednetbackupurl") @Param(description = "Zone NetBackup endpoint used by the last live preflight") public String inspectednetbackupurl;
    @SerializedName("backupofferingid") @Param(description = "Shared NetBackup offering registered in this Zone") public String backupofferingid;
    @SerializedName("policyselections") @Param(description = "Backup Selections from the latest live policy inspection") public List<String> policyselections = new ArrayList<>();
    @SerializedName("policypathmatched") @Param(description = "Policy selects exactly the expected bootstrap directory") public boolean policypathmatched;
    @SerializedName("bootstrappresent") @Param(description = "Expected bootstrap directory existed at the latest inspection") public boolean bootstrappresent;
    @SerializedName("linkready") @Param(description = "Host components and the last verified policy path match the active staging settings") public boolean linkready;
    public NetBackupHostConfigurationResponse() { setObjectName("netbackuphostconfiguration"); }
    public static class Check {
        @SerializedName("name") @Param(description = "Check name") public String name;
        @SerializedName("state") @Param(description = "Check result") public String state;
    }
    public static class Target {
        @SerializedName("id") @Param(description = "VM UUID") public String id;
        @SerializedName("name") @Param(description = "VM display name") public String name;
        @SerializedName("instancename") @Param(description = "VM instance name") public String instancename;
        @SerializedName("state") @Param(description = "VM state") public String state;
        @SerializedName("hasschedule") @Param(description = "VM has a Mold backup schedule") public boolean hasschedule;
    }
}
