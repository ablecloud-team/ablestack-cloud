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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.Collections;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackCommvaultRestoreBackupCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

public class LibvirtAblestackCommvaultRestoreFailureTest {
    @Test
    public void messageLessFailureReplacesLaunchDetailsWithExceptionType() {
        assertFailureDetails(new NullPointerException(), "java.lang.NullPointerException");
    }

    @Test
    public void descriptiveFailureRetainsItsReason() {
        assertFailureDetails(new IllegalStateException("Primary storage is unavailable"), "Primary storage is unavailable");
    }

    private void assertFailureDetails(Exception failure, String expected) {
        AblestackCommvaultRestoreBackupCommand command = new Gson().fromJson("{}", AblestackCommvaultRestoreBackupCommand.class);
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        command.setVolumeRestorePlan(plan);
        command.setRestoreJobId("restore-failure-test");
        command.setVmName("i-2-220-VM");
        command.setBackupPath("/staging/restore/restore-failure-test");
        command.setRestoreVolumePools(Collections.emptyList());
        command.setRestoreVolumePaths(Collections.emptyList());
        command.setWaitForCompletion(true);
        LibvirtComputingResource resource = Mockito.mock(LibvirtComputingResource.class);
        try (MockedStatic<LibvirtAblestackVolumeRestoreHelper> helper = Mockito.mockStatic(LibvirtAblestackVolumeRestoreHelper.class);
                MockedStatic<LibvirtAblestackAsyncBackupRunner> runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class)) {
            helper.when(() -> LibvirtAblestackVolumeRestoreHelper.restore(eq(resource), any(), eq(plan), eq(command.getVmName()),
                    eq(command.getRestoreVolumePools()), eq(command.getRestoreVolumePaths()))).thenThrow(failure);
            Answer answer = new LibvirtAblestackCommvaultRestoreBackupCommandWrapper().execute(command, resource);
            assertFalse(answer.getResult());
            assertEquals(expected, answer.getDetails());
            runner.verify(() -> LibvirtAblestackAsyncBackupRunner.markRestoreJobFailed(any(), eq("commvault"), eq(command.getRestoreJobId()),
                    eq(command.getVmName()), eq(command.getBackupPath()), eq(expected)));
        }
    }
}
