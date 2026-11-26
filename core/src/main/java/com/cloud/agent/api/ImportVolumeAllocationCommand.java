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

import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.agent.api.to.StorageFilerTO;

/** Explicit import/verification RPC. Old Agents cannot positively acknowledge this contract. */
public class ImportVolumeAllocationCommand extends Command {
    private int contractVersion = 1;
    private String action;
    private StorageFilerTO pool;
    private String sourcePath;
    private String finalPath;
    private String requestId;
    private long accountId;
    private long size;
    private boolean allowCopy;
    private Map<String, String> allocation;
    protected ImportVolumeAllocationCommand() { }
    public ImportVolumeAllocationCommand(String action, StorageFilerTO pool, String sourcePath, String finalPath,
            String requestId, long accountId, long size, boolean allowCopy, Map<String, String> allocation) {
        this.action = action; this.pool = pool; this.sourcePath = sourcePath; this.finalPath = finalPath;
        this.requestId = requestId; this.accountId = accountId; this.size = size; this.allowCopy = allowCopy;
        this.allocation = new LinkedHashMap<>(allocation); setWait("IMPORT".equals(action) ? 3600 : 300);
    }
    public int getContractVersion() { return contractVersion; }
    public String getAction() { return action; }
    public StorageFilerTO getPool() { return pool; }
    public String getSourcePath() { return sourcePath; }
    public String getFinalPath() { return finalPath; }
    public String getRequestId() { return requestId; }
    public long getAccountId() { return accountId; }
    public long getSize() { return size; }
    public boolean isAllowCopy() { return allowCopy; }
    public Map<String, String> getAllocation() { return allocation; }
    @Override public boolean executeInSequence() { return false; }
}
