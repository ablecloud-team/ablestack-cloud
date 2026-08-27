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

package com.cloud.automation.controller;

import com.cloud.utils.fsm.NoTransitionException;
import org.junit.Assert;
import org.junit.Test;

public class AutomationControllerStateTest {

    @Test
    public void deleteCanBeRetriedFromTransientStates() throws NoTransitionException {
        AutomationController.State[] states = {
                AutomationController.State.Created,
                AutomationController.State.Starting,
                AutomationController.State.Stopping,
                AutomationController.State.Scaling,
                AutomationController.State.Upgrading,
                AutomationController.State.Recovering,
                AutomationController.State.Destroying
        };

        for (AutomationController.State state : states) {
            Assert.assertEquals(AutomationController.State.Destroying,
                    AutomationController.State.getStateMachine().getNextState(
                            state, AutomationController.Event.DestroyRequested));
        }
    }

    @Test
    public void newControllerDoesNotReuseTemplateIdAsPrimaryKey() {
        AutomationControllerVO controller = new AutomationControllerVO("genie", "controller", 7L, 1L,
                2L, 3L, "genie-network", 4L, 5L, AutomationController.State.Created, null);

        Assert.assertEquals(0L, controller.getId());
        Assert.assertEquals(7L, controller.getAutomationTemplateId());
    }
}
