//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.UpdateHaStateCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles =  UpdateHaStateCommand.class)
public final class LibvirtUpdateHaStateCommandWrapper extends CommandWrapper<UpdateHaStateCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(UpdateHaStateCommand command, LibvirtComputingResource serverResource) {
        logger.debug(String.format("HA state change : [%s]", command.getHostHAState()));
        final String listStonith = Script.runSimpleBashScript("pcs stonith status | awk '{print $2}' | xargs");
        if (!"".equals(listStonith) && !"stonith".equals(listStonith)) {
            final String[] list = listStonith.split(" ");
            for (String ls : list) {
                Script.runSimpleBashScript("pcs stonith " + command.getHostHAState() + " " + ls);
            }
            logger.debug(String.format("Update PCS Stonith State : [%s]", command.getHostHAState()));
        }
        return new Answer(command, true, "success");
    }
}
