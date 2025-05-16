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
import com.cloud.agent.api.to.DataStoreTO;

public class CreateRbdObjectsCommand extends StorageCommand {

    private DataStoreTO store;

    private String names;

    private long sizes;

    private String poolType;

    private String poolPath;

    private String keyword;

    private Long poolId;

    public CreateRbdObjectsCommand(DataStoreTO store, String names, long sizes) {
        super();
        this.store = store;
        this.names = names;
        this.sizes = sizes;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public DataStoreTO getStore() {
        return store;
    }

    public String getNames() {
        return names;
    }

    public long getSizes() {
        return sizes;
    }

    public String getPoolPath() {
        return poolPath;
    }

    public String getPoolType() {
        return poolType;
    }

    public Long getPoolId() {
        return poolId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setPoolType(String poolType) {
        this.poolType = poolType;
    }

    public void setPoolId(Long poolId) {
        this.poolId = poolId;
    }

    public void setPoolPath(String poolPath) {
        this.poolPath = poolPath;
    }
}
