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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import com.google.gson.Gson;

public class AblestackThirdPartyStagingCommandTest {
    @Test
    public void legacyCommandWithoutLocalModeKeepsMountValidation() {
        var command = new Gson().fromJson("{\"storageType\":\"LOCAL\",\"rootPath\":\"/stage\"}", AblestackThirdPartyStagingCommand.class);
        assertEquals("MOUNT", command.getLocalMode());
    }

    @Test
    public void explicitDirectoryModeSurvivesTheAgentWireFormat() {
        var command = new AblestackThirdPartyStagingCommand("LOCAL", "/stage", "/stage", "", "", "/stage/provider", 15, 120L);
        command.setLocalMode("DIRECTORY");
        var recovered = new Gson().fromJson(new Gson().toJson(command), AblestackThirdPartyStagingCommand.class);
        assertEquals("DIRECTORY", recovered.getLocalMode());
        assertEquals(120L, recovered.getRequiredBytes());
    }
}
