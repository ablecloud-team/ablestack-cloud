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
package org.apache.cloudstack.backup.ablestackveeam;

import java.util.List;
import org.junit.Test;
import com.cloud.utils.Pair;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VeeamStagingPathSyncTest {
    private static class Client extends AblestackVeeamSshClient {
        String script;
        boolean changed;
        Client(boolean changed) { super("veeam-test", "test", "test"); this.changed = changed; }
        @Override protected Pair<Boolean, String> executePowerShellCommands(List<String> commands) {
            script = String.join("\n", commands);
            return new Pair<>(true, "ABLESTACK_JSON:{\"changed\":" + changed + "}");
        }
    }

    @Test public void selectedFilesUpdatePreservesJobScopeScheduleRetentionAndCredentials() {
        Client client = new Client(true);
        assertTrue(client.synchronizeStagingPath("Different Job", "host-four", "/old", "/new", "APPLY"));
        assertTrue(client.script.contains("$_.Name -ceq $name"));
        assertTrue(client.script.contains("Set-VBRComputerBackupJob -Job $latest[0] -SelectedFilesOptions $files"));
        assertFalse(client.script.contains("-BackupObject"));
        assertFalse(client.script.contains("-EnableSchedule"));
        assertFalse(client.script.contains("-RetentionPolicy"));
        assertTrue(client.script.contains("$before -cne $after"));
        assertTrue(client.script.indexOf("$current -ceq $target") < client.script.indexOf("Set-VBRComputerBackupJob -Job"));
    }

    @Test public void matchingPathsReturnWithoutClaimingAChange() {
        Client client = new Client(false);
        assertFalse(client.synchronizeStagingPath("Different Job", "host-four", "/new", "/new", "CHECK"));
        assertTrue(client.script.contains("$action -eq 'CHECK'"));
        assertTrue(client.script.contains("$selected.Count -ne 1"));
    }
}
