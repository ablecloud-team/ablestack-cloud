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

import java.util.List;
import com.cloud.agent.api.Command;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

/** Read-only proof of the exact baseline; an unknown/older Agent must reject the request. */
public class CheckBackupIncrementalSourceCommand extends Command {
    private final String vmName;
    private final boolean stopped;
    private final String engine;
    private final List<PrimaryDataStoreTO> pools;
    private final List<String> paths;
    private final String checkpointName;
    private final String checkpointXml;

    public CheckBackupIncrementalSourceCommand(String vmName, boolean stopped, String engine,
            List<PrimaryDataStoreTO> pools, List<String> paths, String checkpointName, String checkpointXml) {
        this.vmName = vmName;
        this.stopped = stopped;
        this.engine = engine;
        this.pools = pools;
        this.paths = paths;
        this.checkpointName = checkpointName;
        this.checkpointXml = checkpointXml;
        setWait(120);
    }
    public String getVmName() { return vmName; }
    public boolean isStopped() { return stopped; }
    public String getEngine() { return engine; }
    public List<PrimaryDataStoreTO> getPools() { return pools; }
    public List<String> getPaths() { return paths; }
    public String getCheckpointName() { return checkpointName; }
    public String getCheckpointXml() { return checkpointXml; }
    @Override public boolean executeInSequence() { return true; }
}
