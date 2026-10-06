// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;
import org.junit.Assert;
import org.junit.Test;
public class StorageNfsIdMappingPolicyTest {
    private final StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
    @Test public void oldRecordsKeepNameDomainUntilAnExplicitPolicyChange() {
        Assert.assertEquals("NAME_DOMAIN",manager.normalizeNfsIdMappingMode(null));
        Assert.assertEquals("NAME_DOMAIN",manager.normalizeNfsIdMappingMode(""));
    }
    @Test public void acceptsExplicitNumericAndNamePolicies() {
        Assert.assertEquals("NUMERIC",manager.normalizeNfsIdMappingMode(" numeric "));
        Assert.assertEquals("NAME_DOMAIN",manager.normalizeNfsIdMappingMode("name_domain"));
    }
    @Test public void rejectsUnknownMappingPolicyRatherThanSilentlyChangingIt() {
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class,()->manager.normalizeNfsIdMappingMode("automatic"));
    }
}
