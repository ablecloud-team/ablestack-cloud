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
package org.apache.cloudstack.storage.dataservice;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name="storage_service_template_upgrade")
public class StorageServiceTemplateUpgradeVO implements StorageServiceTemplateUpgrade {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name="id") private long id;
    @Column(name="uuid") private String uuid = java.util.UUID.randomUUID().toString();
    @Column(name="instance_id") private long instanceId;
    @Column(name="shared_filesystem_id") private long sharedFilesystemId;
    @Column(name="source_template_id") private long sourceTemplateId;
    @Column(name="target_template_id") private long targetTemplateId;
    @Column(name="previous_root_volume_id") private long previousRootVolumeId;
    @Column(name="target_root_volume_id") private Long targetRootVolumeId;
    @Column(name="previous_guest_os_id") private long previousGuestOsId;
    @Column(name="root_device_id") private long rootDeviceId;
    @Column(name="previous_vm_state") private String previousVmState;
    @Column(name="state") private String state = "PLANNED";
    @Column(name="phase") private String phase = "PREFLIGHT";
    @Column(name="progress") private int progress;
    @Column(name="revision") private long revision;
    @Column(name="request_key") private String requestKey;
    @Column(name="operation_id") private Long operationId;
    @Lob @Column(name="snapshot_json", length=16777215, columnDefinition="LONGTEXT") private String snapshotJson;
    @Lob @Column(name="preflight_json", length=16777215, columnDefinition="LONGTEXT") private String preflightJson;
    @Lob @Column(name="verification_json", length=16777215, columnDefinition="LONGTEXT") private String verificationJson;
    @Lob @Column(name="rollback_result_json", length=16777215, columnDefinition="LONGTEXT") private String rollbackResultJson;
    @Column(name="error_code") private String errorCode;
    @Column(name="error_message") private String errorMessage;
    @Column(name="created_by") private long createdBy;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name="started") private java.util.Date started;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name="heartbeat") private java.util.Date heartbeat = new java.util.Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name="completed") private java.util.Date completed;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name="rollback_retain_until") private java.util.Date rollbackRetainUntil;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name="created") private java.util.Date created = new java.util.Date();
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(long value) { instanceId=value; }
    public long getSharedFilesystemId() { return sharedFilesystemId; }
    public void setSharedFilesystemId(long value) { sharedFilesystemId=value; }
    public long getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(long value) { sourceTemplateId=value; }
    public long getTargetTemplateId() { return targetTemplateId; }
    public void setTargetTemplateId(long value) { targetTemplateId=value; }
    public long getPreviousRootVolumeId() { return previousRootVolumeId; }
    public void setPreviousRootVolumeId(long value) { previousRootVolumeId=value; }
    public Long getTargetRootVolumeId() { return targetRootVolumeId; }
    public void setTargetRootVolumeId(Long value) { targetRootVolumeId=value; }
    public long getPreviousGuestOsId() { return previousGuestOsId; }
    public void setPreviousGuestOsId(long value) { previousGuestOsId=value; }
    public long getRootDeviceId() { return rootDeviceId; }
    public void setRootDeviceId(long value) { rootDeviceId=value; }
    public String getPreviousVmState() { return previousVmState; }
    public void setPreviousVmState(String value) { previousVmState=value; }
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    public String getPhase() { return phase; }
    public void setPhase(String value) { phase=value; }
    public int getProgress() { return progress; }
    public void setProgress(int value) { progress=value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision=value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey=value; }
    public Long getOperationId() { return operationId; }
    public void setOperationId(Long value) { operationId=value; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String value) { snapshotJson=value; }
    public String getPreflightJson() { return preflightJson; }
    public void setPreflightJson(String value) { preflightJson=value; }
    public String getVerificationJson() { return verificationJson; }
    public void setVerificationJson(String value) { verificationJson=value; }
    public String getRollbackResultJson() { return rollbackResultJson; }
    public void setRollbackResultJson(String value) { rollbackResultJson=value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode=value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage=value; }
    public long getCreatedBy() { return createdBy; }
    public void setCreatedBy(long value) { createdBy=value; }
    public java.util.Date getStarted() { return started; }
    public void setStarted(java.util.Date value) { started=value; }
    public java.util.Date getHeartbeat() { return heartbeat; }
    public void setHeartbeat(java.util.Date value) { heartbeat=value; }
    public java.util.Date getCompleted() { return completed; }
    public void setCompleted(java.util.Date value) { completed=value; }
    public java.util.Date getRollbackRetainUntil() { return rollbackRetainUntil; }
    public void setRollbackRetainUntil(java.util.Date value) { rollbackRetainUntil=value; }
    public java.util.Date getCreated() { return created; }
    public void setCreated(java.util.Date value) { created=value; }
}
