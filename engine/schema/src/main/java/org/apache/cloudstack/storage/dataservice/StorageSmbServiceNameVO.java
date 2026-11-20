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
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
@Entity @Table(name="storage_smb_service_name")
public class StorageSmbServiceNameVO implements StorageSmbServiceName {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="id") private long id;
    @Column(name="uuid") private String uuid = java.util.UUID.randomUUID().toString();
    @Column(name="instance_id") private long instanceId;
    @Column(name="name_kind") private String nameKind = "ALIAS";
    @Column(name="binding_type") private String bindingType = "EXPLICIT";
    @Column(name="endpoint_uuid") private String endpointUuid;
    @Column(name="fqdn") private String fqdn;
    @Column(name="canonical_hash") private String canonicalHash;
    @Column(name="state") @Enumerated(EnumType.STRING) private StorageServiceInstance.ResourceState state = StorageServiceInstance.ResourceState.Creating;
    @Column(name="revision") private long revision = 1;
    @Lob @Column(name="desired_json", length=16777215, columnDefinition="MEDIUMTEXT") private String desiredJson = "{}";
    @Lob @Column(name="ownership_json", length=16777215, columnDefinition="MEDIUMTEXT") private String ownershipJson = "{}";
    @Lob @Column(name="observed_json", length=16777215, columnDefinition="MEDIUMTEXT") private String observedJson = "{}";
    @Column(name="created") @Temporal(TemporalType.TIMESTAMP) private java.util.Date created = new java.util.Date();
    @Column(name="updated") @Temporal(TemporalType.TIMESTAMP) private java.util.Date updated;
    public long getId(){return id;}
    public String getUuid(){return uuid;}
    public void setUuid(String value){uuid=value;}
    public long getInstanceId(){return instanceId;}
    public void setInstanceId(long value){instanceId=value;}
    public String getNameKind(){return nameKind;}
    public void setNameKind(String value){nameKind=value;}
    public String getBindingType(){return bindingType;}
    public void setBindingType(String value){bindingType=value;}
    public String getEndpointUuid(){return endpointUuid;}
    public void setEndpointUuid(String value){endpointUuid=value;}
    public String getFqdn(){return fqdn;}
    public void setFqdn(String value){fqdn=value;}
    public String getCanonicalHash(){return canonicalHash;}
    public void setCanonicalHash(String value){canonicalHash=value;}
    public StorageServiceInstance.ResourceState getState(){return state;}
    public void setState(StorageServiceInstance.ResourceState value){state=value;}
    public long getRevision(){return revision;}
    public void setRevision(long value){revision=value;}
    public String getDesiredJson(){return desiredJson;}
    public void setDesiredJson(String value){desiredJson=value;}
    public String getOwnershipJson(){return ownershipJson;}
    public void setOwnershipJson(String value){ownershipJson=value;}
    public String getObservedJson(){return observedJson;}
    public void setObservedJson(String value){observedJson=value;}
    public java.util.Date getCreated(){return created;}
    public java.util.Date getUpdated(){return updated;}
    public void setUpdated(java.util.Date value){updated=value;}
}
