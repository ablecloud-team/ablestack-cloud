// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.apache.cloudstack.api.command.user.vm;

import java.util.Map;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.vm.UserVmService;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CloneVMCmdTest {
    @Test public void minimalRequestUsesOneStoppedFullClone() throws Exception {
        CloneVMCmd cmd = new CloneVMCmd();
        assertEquals(Integer.valueOf(1), cmd.getCount());
        assertFalse(cmd.getStartVm());
        assertEquals("full", cmd.getType());
        for (String field : new String[]{"count", "startVm", "type"}) {
            assertFalse(CloneVMCmd.class.getDeclaredField(field).getAnnotation(Parameter.class).required());
        }
    }

    @Test public void explicitOptionsSurviveDefaults() {
        CloneVMCmd cmd = new CloneVMCmd();
        ReflectionTestUtils.setField(cmd, "count", 50);
        ReflectionTestUtils.setField(cmd, "startVm", true);
        ReflectionTestUtils.setField(cmd, "type", "linked");
        assertEquals(Integer.valueOf(50), cmd.getCount());
        assertTrue(cmd.getStartVm());
        assertEquals("linked", cmd.getType());
    }

    @Test public void invalidInputUses431BeforeJobCreation() throws Exception {
        CloneVMCmd cmd = new CloneVMCmd();
        UserVmService service = mock(UserVmService.class);
        ReflectionTestUtils.setField(cmd, "_userVmService", service);
        doThrow(new InvalidParameterValueException("invalid request")).when(service).validateCloneCondition(cmd);
        ServerApiException error = assertThrows(ServerApiException.class, cmd::create);
        assertEquals(ApiErrorCode.PARAM_ERROR, error.getErrorCode());
    }

    @Test public void malformedBooleanDoesNotSilentlyBecomeFalse() {
        for (String value : new String[]{"", "yes", "1", " true "}) {
            CloneVMCmd cmd = new CloneVMCmd();
            cmd.setFullUrlParams(Map.of("startvm", value));
            ServerApiException error = assertThrows(ServerApiException.class, cmd::create);
            assertEquals(ApiErrorCode.PARAM_ERROR, error.getErrorCode());
            assertTrue(error.getDescription().contains("startvm"));
        }
    }

    @Test public void emptyCountDoesNotSilentlySelectDefault() {
        CloneVMCmd cmd = new CloneVMCmd();
        cmd.setFullUrlParams(Map.of("count", ""));
        assertEquals(ApiErrorCode.PARAM_ERROR, assertThrows(ServerApiException.class, cmd::create).getErrorCode());
    }

    @Test public void trueAndFalseUseExistingBooleanParsingContract() throws Exception {
        for (String value : new String[]{"true", "false", "TRUE", "False"}) {
            CloneVMCmd cmd = new CloneVMCmd();
            cmd.setFullUrlParams(Map.of("startvm", value));
            UserVmService service = mock(UserVmService.class);
            ReflectionTestUtils.setField(cmd, "_userVmService", service);
            cmd.create();
            verify(service).validateCloneCondition(cmd);
        }
    }
}
