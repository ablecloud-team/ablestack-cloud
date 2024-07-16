/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.storage.command.browser;

import com.cloud.agent.api.storage.StorageCommand;

public class ListVMPciCommand extends StorageCommand {

    private String pciName;

    private String pciText;

    private Long id;

    public ListVMPciCommand() {
    }

    public ListVMPciCommand( String vmUuid, String pciName, String pciText, Long id) {
        super();
        this.pciName = pciName;
        this.pciText = pciText;
        this.id = id;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public String getPciName() {
        return pciName;
    }

    public String getPciText() {
        return pciText;
    }

    public Long getId() {
        return id;
    }
}
