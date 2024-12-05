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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "disaster_recovery_cluster_vm_map")
public class DisasterRecoveryClusterVmMapVO implements DisasterRecoveryClusterVmMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    long id;

    @Column(name = "disaster_recovery_cluster_id")
    long disasterRecoveryClusterId;

    @Column(name = "vm_id")
    long vmId;

    @Column(name = "mirrored_vm_id")
    String mirroredVmId;

    @Column(name = "mirrored_vm_name")
    String mirroredVmName;

    @Column(name = "mirrored_vm_status")
    String mirroredVmStatus;

    @Column(name = "mirrored_vm_volume_type")
    String mirroredVmVolumeType;

    @Column(name = "mirrored_vm_volume_path")
    String mirroredVmVolumePath;

    @Column(name = "mirrored_vm_volume_status")
    String mirroredVmVolumeStatus;

    public DisasterRecoveryClusterVmMapVO() {
    }

    public DisasterRecoveryClusterVmMapVO(long disasterRecoveryClusterId, long vmId, String mirroredVmId, String mirroredVmName, String mirroredVmStatus, String mirroredVmVolumeType, String mirroredVmVolumePath, String mirroredVmVolumeStatus) {
        this.disasterRecoveryClusterId = disasterRecoveryClusterId;
        this.vmId = vmId;
        this.mirroredVmId = mirroredVmId;
        this.mirroredVmName = mirroredVmName;
        this.mirroredVmStatus = mirroredVmStatus;
        this.mirroredVmVolumeType = mirroredVmVolumeType;
        this.mirroredVmVolumePath = mirroredVmVolumePath;
        this.mirroredVmVolumeStatus = mirroredVmVolumeStatus;

    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public long getDisasterRecoveryClusterId() {
        return disasterRecoveryClusterId;
    }

    public void setDisasterRecoveryClusterId(long disasterRecoveryClusterId) {
        this.disasterRecoveryClusterId = disasterRecoveryClusterId;
    }

    @Override
    public long getVmId() {
        return vmId;
    }

    public void setVmId(long vmId) {
        this.vmId = vmId;
    }

    @Override
    public String getMirroredVmId() {
        return mirroredVmId;
    }

    @Override
    public String getMirroredVmName() {
        return mirroredVmName;
    }

    @Override
    public String getMirroredVmStatus() {
        return mirroredVmStatus;
    }

    public void setMirroredVmStatus(String mirroredVmStatus) {
        this.mirroredVmStatus = mirroredVmStatus;
    }

    @Override
    public String getMirroredVmVolumeType() {
        return mirroredVmVolumeType;
    }

    public void setMirroredVmVolumeType(String mirroredVmVolumeType) {
        this.mirroredVmVolumeType = mirroredVmVolumeType;
    }

    @Override
    public String getMirroredVmVolumePath() {
        return mirroredVmVolumePath;
    }

    public void setMirroredVmVolumePath(String mirroredVmVolumePath) {
        this.mirroredVmVolumePath = mirroredVmVolumePath;
    }

    @Override
    public String getMirroredVmVolumeStatus() {
        return mirroredVmVolumeStatus;
    }

    public void setMirroredVmVolumeStatus(String mirroredVmVolumeStatus) {
        this.mirroredVmVolumeStatus = mirroredVmVolumeStatus;
    }

}