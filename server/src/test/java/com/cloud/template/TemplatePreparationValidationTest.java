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
package com.cloud.template;

import org.junit.Test;
import static org.junit.Assert.*;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePoolStatus;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.template.PrepareTemplateCmd;

public class TemplatePreparationValidationTest {
    private StoragePoolVO pool() { StoragePoolVO p = new StoragePoolVO(); p.setDataCenterId(13); p.setStatus(StoragePoolStatus.Up); p.setPoolType(StoragePoolType.SharedMountPoint); p.setHypervisor(HypervisorType.KVM); return p; }
    @Test public void acceptsEligibleFilePools() { for (StoragePoolType t : new StoragePoolType[] {StoragePoolType.NetworkFilesystem, StoragePoolType.SharedMountPoint}) { StoragePoolVO p=pool(); p.setPoolType(t); TemplatePreparationValidation.validate(p,13,HypervisorType.KVM); } }
    @Test(expected=InvalidParameterValueException.class) public void missingPoolIsRejected() { TemplatePreparationValidation.validate(null,13,HypervisorType.KVM); }
    @Test(expected=InvalidParameterValueException.class) public void otherZoneIsRejected() { TemplatePreparationValidation.validate(pool(),14,HypervisorType.KVM); }
    @Test(expected=InvalidParameterValueException.class) public void disabledPoolIsRejected() { StoragePoolVO p=pool();p.setStatus(StoragePoolStatus.Disabled);TemplatePreparationValidation.validate(p,13,HypervisorType.KVM); }
    @Test(expected=InvalidParameterValueException.class) public void managedPoolIsRejected() { StoragePoolVO p=pool();p.setManaged(true);TemplatePreparationValidation.validate(p,13,HypervisorType.KVM); }
    @Test public void unsupportedTypesAndAggregatePoolsAreRejected() { for (StoragePoolType t:new StoragePoolType[] {StoragePoolType.RBD,StoragePoolType.ISO,StoragePoolType.DatastoreCluster}) { StoragePoolVO p=pool();p.setPoolType(t);try { TemplatePreparationValidation.validate(p,13,HypervisorType.KVM);fail(t.toString()); } catch(InvalidParameterValueException expected) { } } }
    @Test(expected=InvalidParameterValueException.class) public void mismatchedHypervisorIsRejected() { TemplatePreparationValidation.validate(pool(),13,HypervisorType.VMware); }
    @Test public void synchronousCommandAndOptionalLegacyStorageContractStayIntact() throws Exception { assertFalse(BaseAsyncCmd.class.isAssignableFrom(PrepareTemplateCmd.class));assertFalse(PrepareTemplateCmd.class.getDeclaredField("storageId").getAnnotation(Parameter.class).required()); }
}
