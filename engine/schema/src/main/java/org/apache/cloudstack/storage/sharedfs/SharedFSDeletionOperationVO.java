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
package org.apache.cloudstack.storage.sharedfs;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;
import java.util.UUID;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;
/** Durable, non-cascading lifecycle outcome; no credentials are stored. */
@Entity @Table(name="sharedfs_deletion_operation")
public class SharedFSDeletionOperationVO implements Identity,InternalIdentity,org.apache.cloudstack.acl.ControlledEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="id") private long id;
    @Column(name="uuid") private String uuid=UUID.randomUUID().toString();
    @Column(name="sharedfs_id") private long sharedFsId;
    @Column(name="sharedfs_uuid") private String sharedFsUuid;
    @Column(name="instance_uuid") private String instanceUuid;
    @Column(name="vm_uuid") private String vmUuid;
    @Column(name="account_id") private long accountId;
    @Column(name="domain_id") private long domainId;
    @Column(name="request_id") private String requestId;
    @Column(name="request_hash") private String requestHash;
    @Column(name="plan_fingerprint") private String planFingerprint;
    @Column(name="state") private String state;
    @Column(name="phase") private String phase;
    @Lob @Column(name="plan_json",length=16777215,columnDefinition="MEDIUMTEXT") private String planJson;
    @Lob @Column(name="result_json",length=16777215,columnDefinition="MEDIUMTEXT") private String resultJson;
    @Lob @Column(name="original_failure_json",length=65535,columnDefinition="TEXT") private String originalFailureJson;
    @Column(name="job_id") private Long jobId;
    @Temporal(TemporalType.TIMESTAMP) @Column(name="created") private Date created=new Date();
    @Temporal(TemporalType.TIMESTAMP) @Column(name="updated") private Date updated=new Date();
    @Temporal(TemporalType.TIMESTAMP) @Column(name="completed") private Date completed;
    public Class<?> getEntityType(){return SharedFS.class;}
    public String getName(){return sharedFsUuid;}
    public long getId(){return id;}
    public String getUuid(){return uuid;}
    public long getSharedFsId(){return sharedFsId;}
    public void setSharedFsId(long value){sharedFsId=value;}
    public String getSharedFsUuid(){return sharedFsUuid;}
    public void setSharedFsUuid(String value){sharedFsUuid=value;}
    public String getInstanceUuid(){return instanceUuid;}
    public void setInstanceUuid(String value){instanceUuid=value;}
    public String getVmUuid(){return vmUuid;}
    public void setVmUuid(String value){vmUuid=value;}
    public long getAccountId(){return accountId;}
    public void setAccountId(long value){accountId=value;}
    public long getDomainId(){return domainId;}
    public void setDomainId(long value){domainId=value;}
    public String getRequestId(){return requestId;}
    public void setRequestId(String value){requestId=value;}
    public String getRequestHash(){return requestHash;}
    public void setRequestHash(String value){requestHash=value;}
    public String getPlanFingerprint(){return planFingerprint;}
    public void setPlanFingerprint(String value){planFingerprint=value;}
    public String getState(){return state;}
    public void setState(String value){state=value;}
    public String getPhase(){return phase;}
    public void setPhase(String value){phase=value;}
    public String getPlanJson(){return planJson;}
    public void setPlanJson(String value){planJson=value;}
    public String getResultJson(){return resultJson;}
    public void setResultJson(String value){resultJson=value;}
    public String getOriginalFailureJson(){return originalFailureJson;}
    public void setOriginalFailureJson(String value){if(originalFailureJson!=null)return;originalFailureJson=value;}
    public Long getJobId(){return jobId;}
    public void setJobId(Long value){jobId=value;}
    public Date getCreated(){return created;}
    public void setCreated(Date value){created=value;}
    public Date getUpdated(){return updated;}
    public void setUpdated(Date value){updated=value;}
    public Date getCompleted(){return completed;}
    public void setCompleted(Date value){completed=value;}
}
