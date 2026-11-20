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
package org.apache.cloudstack.backup;

import com.cloud.agent.api.Command;

/** Keep the data execution budget independent of the agent response wait. */
public abstract class AblestackDataOperationCommand extends Command {
    // Retain the existing restore wire field and the legacy wait field for older agents.
    private Integer timeout;
    private Boolean waitForCompletion = true;

    public Integer getTimeout() {
        return timeout == null ? getWait() : timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
        setWait(timeout == null ? 0 : timeout);
    }

    public boolean isWaitForCompletion() {
        return !Boolean.FALSE.equals(waitForCompletion);
    }

    public void setWaitForCompletion(boolean waitForCompletion) {
        this.waitForCompletion = waitForCompletion;
    }

    @Override
    public int getResponseWait() {
        return isWaitForCompletion() ? getWait() : 0;
    }
}
