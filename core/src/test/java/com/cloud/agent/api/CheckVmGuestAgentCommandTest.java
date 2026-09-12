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
import org.junit.Assert;
import org.junit.Test;
public class CheckVmGuestAgentCommandTest {
    @Test public void boundsTimeoutAndCorrelatesAnswer() {
        CheckVmGuestAgentCommand command = new CheckVmGuestAgentCommand("uuid","name","token",999);
        Assert.assertEquals(5,command.getTimeoutSeconds()); Assert.assertFalse(command.executeInSequence());
        CheckVmGuestAgentAnswer answer = new CheckVmGuestAgentAnswer(command,"RESPONDED",null);
        Assert.assertEquals("uuid",answer.getVmUuid()); Assert.assertEquals("token",answer.getToken()); Assert.assertTrue(answer.getResult());
        Assert.assertEquals(1,new CheckVmGuestAgentCommand("u","n","t",0).getTimeoutSeconds());
        Assert.assertFalse(new CheckVmGuestAgentAnswer(command,"NOT_READY",null).getResult());
    }
}
