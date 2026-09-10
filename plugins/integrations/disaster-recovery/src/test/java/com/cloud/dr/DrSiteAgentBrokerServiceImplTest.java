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

package com.cloud.dr;

import org.apache.cloudstack.api.response.dr.DrSiteAgentCommandResponse;
import org.apache.cloudstack.api.response.ftctl.FtctlDrSiteAgentCommandResponse;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.ftctl.FtctlDrSiteAgentBrokerService;

import org.mockito.Mockito;

public class DrSiteAgentBrokerServiceImplTest {

    @Test
    public void delegatesLegacyApiToCanonicalFtctlSiteBroker() {
        FtctlDrSiteAgentBrokerService broker = Mockito.mock(FtctlDrSiteAgentBrokerService.class);
        FtctlDrSiteAgentCommandResponse source = new FtctlDrSiteAgentCommandResponse();
        source.setCommandType("CANCEL");
        source.setWorkerHostUuid("auto-selected-host");
        source.setResult(true);
        source.setDetails("canceled");
        source.setAnswerClass("CancelAnswer");
        source.setAnswerJson("{\"state\":\"CANCELED\"}");
        Mockito.when(broker.execute("CANCEL", "{}", "deprecated-hint")).thenReturn(source);
        DrSiteAgentBrokerServiceImpl service = new DrSiteAgentBrokerServiceImpl();
        ReflectionTestUtils.setField(service, "ftctlDrSiteAgentBrokerService", broker);

        DrSiteAgentCommandResponse response = service.execute("CANCEL", "{}", "deprecated-hint");

        Assert.assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(response, "result"));
        Assert.assertEquals("CANCEL", ReflectionTestUtils.getField(response, "commandType"));
        Assert.assertEquals("auto-selected-host", ReflectionTestUtils.getField(response, "workerHostUuid"));
        Mockito.verify(broker).execute("CANCEL", "{}", "deprecated-hint");
    }
}
