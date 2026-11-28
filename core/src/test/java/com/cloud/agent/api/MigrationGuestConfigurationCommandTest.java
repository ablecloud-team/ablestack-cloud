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
package com.cloud.agent.api;

import com.google.gson.Gson;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class MigrationGuestConfigurationCommandTest {
    @Test public void planVersionControlsSelectedWorkerAndAnswer() {
        var cmd=new MigrationGuestConfigurationCommand("CAPABILITIES","vm","uuid","{\"version\":2,\"value\":null}",null,null);
        assertEquals(2,cmd.getContractVersion());
        var wire=new Gson().fromJson(new Gson().toJson(cmd),MigrationGuestConfigurationCommand.class);
        assertEquals(2,wire.getContractVersion());
        assertEquals(cmd.getPlan(),wire.getPlan());
        var answer=new MigrationGuestConfigurationAnswer(wire,Map.of("supported",true));
        assertEquals(2,answer.getContractVersion());
    }

    @Test public void missingWireVersionKeepsLegacyMeaning() {
        var cmd=new Gson().fromJson("{\"plan\":\"{\\\"version\\\":2}\"}",MigrationGuestConfigurationCommand.class);
        assertEquals(1,cmd.getContractVersion());
        var capabilities=new MigrationGuestConfigurationCommand("CAPABILITIES",null,null,null,null,null);
        assertEquals(1,capabilities.getContractVersion());
        capabilities.setContractVersion(2);
        assertEquals(2,capabilities.getContractVersion());
    }
}
