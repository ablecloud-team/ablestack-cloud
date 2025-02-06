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
package com.cloud.dr.cluster;

import org.apache.cloudstack.api.command.admin.dr.ClearDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.ConnectivityTestsDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.GetDisasterRecoveryClusterListCmd;
import org.apache.cloudstack.api.command.admin.dr.UpdateDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.UpdateDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.CreateDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.DeleteDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.DeleteDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.EnableDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.DisableDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.PromoteDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.PromoteDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.ResyncDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.StartDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.StopDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.TakeSnapshotDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.DemoteDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.DemoteDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.CreateDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.glue.ListScvmIpAddressCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ScvmIpAddressResponse;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterListResponse;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterVmListResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import com.cloud.utils.component.PluggableService;
import com.cloud.utils.exception.CloudRuntimeException;

public interface DisasterRecoveryClusterService extends PluggableService, Configurable {

    static final ConfigKey<Boolean> DisasterRecoveryServiceEnabled = new ConfigKey<Boolean>("Advanced", Boolean.class,
            "cloud.dr.service.enabled",
            "false",
            "Indicates whether Disaster Recovery Service plugin is enabled or not. Management server restart needed on change",
            false);

    ListResponse<ScvmIpAddressResponse> listScvmIpAddressResponse(ListScvmIpAddressCmd cmd);

    ListResponse<GetDisasterRecoveryClusterListResponse> listDisasterRecoveryClusterResponse(GetDisasterRecoveryClusterListCmd cmd);

    GetDisasterRecoveryClusterListResponse updateDisasterRecoveryCluster(UpdateDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    GetDisasterRecoveryClusterVmListResponse updateDisasterRecoveryClusterVm(UpdateDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    GetDisasterRecoveryClusterListResponse createDisasterRecoveryClusterResponse(long clusterId);

    DisasterRecoveryCluster createDisasterRecoveryCluster(CreateDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean connectivityTestsDisasterRecovery(ConnectivityTestsDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean setupDisasterRecoveryCluster(long clusterId) throws CloudRuntimeException;

    boolean deleteDisasterRecoveryCluster(DeleteDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean deleteDisasterRecoveryClusterVm(DeleteDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    boolean enableDisasterRecoveryCluster(EnableDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean disableDisasterRecoveryCluster(DisableDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean promoteDisasterRecoveryCluster(PromoteDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean demoteDisasterRecoveryCluster(DemoteDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean resyncDisasterRecoveryCluster(ResyncDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean clearDisasterRecoveryCluster(ClearDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException;

    boolean setupDisasterRecoveryClusterVm(CreateDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    boolean startDisasterRecoveryClusterVm(StartDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    boolean stopDisasterRecoveryClusterVm(StopDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    boolean promoteDisasterRecoveryClusterVm(PromoteDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    boolean demoteDisasterRecoveryClusterVm(DemoteDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

    boolean takeSnapshotDisasterRecoveryClusterVm(TakeSnapshotDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException;

}
