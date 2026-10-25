// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.command.user.snapshot;

import com.cloud.exception.InvalidParameterValueException;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;

public class SnapshotPolicyIntentTest {
    @Test
    public void createDefaultsToManualAndAcceptsPolicyEight() {
        CreateSnapshotCmd cmd = new CreateSnapshotCmd();
        assertEquals(Long.valueOf(0), cmd.getPolicyId());
        ReflectionTestUtils.setField(cmd, "policyId", 8L);
        assertEquals(Long.valueOf(8), cmd.getPolicyId());
    }

    @Test
    public void backupDefaultsToManualAndAcceptsPolicyEight() {
        CreateSnapshotBackupCmd cmd = new CreateSnapshotBackupCmd();
        assertEquals(Long.valueOf(0), cmd.getPolicyId());
        ReflectionTestUtils.setField(cmd, "policyId", 8L);
        assertEquals(Long.valueOf(8), cmd.getPolicyId());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void publicCreateCannotRequestInternalIntent() {
        CreateSnapshotCmd cmd = new CreateSnapshotCmd();
        ReflectionTestUtils.setField(cmd, "policyId", -1L);
        cmd.getPolicyId();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void publicBackupCannotRequestInternalIntent() {
        CreateSnapshotBackupCmd cmd = new CreateSnapshotBackupCmd();
        ReflectionTestUtils.setField(cmd, "policyId", -1L);
        cmd.getPolicyId();
    }
}
