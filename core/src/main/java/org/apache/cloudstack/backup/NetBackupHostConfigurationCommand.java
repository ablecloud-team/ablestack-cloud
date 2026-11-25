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

import com.cloud.agent.api.Command;
import com.cloud.agent.api.LogLevel;

/** Private configuration payload is never included in Agent transport logs. */
public class NetBackupHostConfigurationCommand extends Command {
    private String action;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String payload;

    protected NetBackupHostConfigurationCommand() { }
    public NetBackupHostConfigurationCommand(String action, String payload) {
        this.action = action;
        this.payload = payload;
        setWait(45);
    }
    public String getAction() { return action; }
    public String getPayload() { return payload; }
    @Override public boolean executeInSequence() { return false; }
}
