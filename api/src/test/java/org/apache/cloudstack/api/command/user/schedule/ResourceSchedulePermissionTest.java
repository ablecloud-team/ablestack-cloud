/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.api.command.user.schedule;

import java.util.List;
import java.util.Map;
import com.cloud.exception.PermissionDeniedException;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.schedule.ResourceScheduleManager;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class ResourceSchedulePermissionTest {
    @Test
    public void allGenericApisCheckLegacyVmPermissionBeforeExecuting() throws Exception {
        List<BaseCmd> commands = List.of(new CreateResourceScheduleCmd(), new ListResourceScheduleCmd(),
                new UpdateResourceScheduleCmd(), new DeleteResourceScheduleCmd());
        String[] legacyApis = {"createVMSchedule", "listVMSchedule", "updateVMSchedule", "deleteVMSchedule"};
        for (int i = 0; i < commands.size(); i++) {
            BaseCmd cmd = commands.get(i);
            ResourceScheduleManager manager = Mockito.mock(ResourceScheduleManager.class);
            ReflectionTestUtils.setField(cmd, "resourceScheduleManager", manager);
            cmd.setFullUrlParams(Map.of("apikey", "fixture-key"));
            ApiCommandResourceType type = ApiCommandResourceType.VirtualMachine;
            Long id = null;
            if (cmd instanceof UpdateResourceScheduleCmd) {
                type = null;
                id = 42L;
                ReflectionTestUtils.setField(cmd, "id", id);
            } else {
                ReflectionTestUtils.setField(cmd, "resourceType", "VirtualMachine");
            }
            Mockito.doThrow(new PermissionDeniedException("legacy denied")).when(manager)
                    .checkVmScheduleApiAccess(legacyApis[i], type, id, "fixture-key");
            try {
                cmd.execute();
                Assert.fail("A generic API must not bypass a legacy VM permission denial");
            } catch (PermissionDeniedException expected) {
                Mockito.verify(manager).checkVmScheduleApiAccess(legacyApis[i], type, id, "fixture-key");
                Mockito.verifyNoMoreInteractions(manager);
            }
        }
    }
}
