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
package org.apache.cloudstack.api.command.admin.outofbandmanagement;

import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;

public class HostDevicePartitionApiTest {
    @Test public void omittedAcknowledgementDefaultsToFalseForEveryStorageApi() {
        assertFalse(new UpdateHostLunDevicesCmd().isPartitionRiskAcknowledged());
        assertFalse(new UpdateHostScsiDevicesCmd().isPartitionRiskAcknowledged());
        assertFalse(new UpdateHostHbaDevicesCmd().isPartitionRiskAcknowledged());
        assertFalse(new UpdateHostVhbaDevicesCmd().isPartitionRiskAcknowledged());
    }

    @Test public void vhbaAcceptsHostAndVirtualMachineUuidsAsDistinctEntityTypes() throws Exception {
        assertArrayEquals(new Class<?>[] {HostResponse.class},
                UpdateHostVhbaDevicesCmd.class.getDeclaredField("hostId").getAnnotation(Parameter.class).entityType());
        assertArrayEquals(new Class<?>[] {UserVmResponse.class},
                UpdateHostVhbaDevicesCmd.class.getDeclaredField("vmId").getAnnotation(Parameter.class).entityType());
    }
}
