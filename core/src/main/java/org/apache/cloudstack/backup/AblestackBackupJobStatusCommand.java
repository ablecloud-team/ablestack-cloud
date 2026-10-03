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

public class AblestackBackupJobStatusCommand extends Command {
    private String backupJobId;
    private Long eventsOffset;
    private Integer eventsLimit;

    protected AblestackBackupJobStatusCommand() {
        super();
    }

    public AblestackBackupJobStatusCommand(final String backupJobId) {
        this.backupJobId = backupJobId;
    }

    public AblestackBackupJobStatusCommand(final String backupJobId, final Long eventsOffset, final Integer eventsLimit) {
        this.backupJobId = backupJobId;
        this.eventsOffset = eventsOffset;
        this.eventsLimit = eventsLimit;
    }

    public String getBackupJobId() {
        return backupJobId;
    }

    public Long getEventsOffset() {
        return eventsOffset;
    }

    public void setEventsOffset(final Long eventsOffset) {
        this.eventsOffset = eventsOffset;
    }

    public Integer getEventsLimit() {
        return eventsLimit;
    }

    public void setEventsLimit(final Integer eventsLimit) {
        this.eventsLimit = eventsLimit;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
