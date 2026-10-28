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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.junit.Test;

import com.cloud.utils.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AblestackVeeamJobNameTest {
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";
    private static final String POINT = "33333333-3333-4333-8333-333333333333";

    private static class Client extends AblestackVeeamSshClient {
        private String script;

        Client() { super("test", "test", "test"); }

        @Override protected Pair<Boolean, String> executePowerShellCommands(List<String> commands) {
            script = String.join("\n", commands);
            return new Pair<>(true, "ABLESTACK_JSON:{\"jobId\":\"" + SESSION + "\",\"completed\":true,"
                    + "\"externalId\":\"" + POINT + "\",\"retired\":true,\"deleted\":true,\"exists\":true}");
        }
    }

    private ThirdPartyBackupManifest.Artifact artifact(String backup, String path) {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = backup;
        artifact.path = path;
        return artifact;
    }

    private String name(Client client, ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
        client.backupVolumeArtifact("parent", "192.0.2.1", artifact, metadata);
        Matcher matcher = Pattern.compile("(?m)^\\$name='([^']+)'$").matcher(client.script);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    @Test public void longAndUnicodePathsUseShortStableDistinctNamesWithinTheVeeamLimit() {
        Client client = new Client();
        Set<String> names = new HashSet<>();
        for (String backup : List.of("79052427-e010-4dda-b30d-0565456ed4b2", "11111111-1111-4111-8111-111111111111")) {
            for (String path : List.of("/mnt/root.raw", "/mnt/datadisk.raw", "/mnt/" + "긴경로😀".repeat(100) + "/root.raw")) {
                String name = name(client, artifact(backup, path), false);
                assertTrue(name.matches("ABLESTACK-V-[0-9a-f]{32}"));
                assertTrue("Veeam Linux Agent job name must fit 50 characters", name.length() <= 50);
                assertEquals(name, name(client, artifact(backup, path), false));
                assertTrue("Distinct backup and volume identities must not alias", names.add(name));
            }
            String name = name(client, artifact(backup, "/mnt/metadata"), true);
            assertTrue(name.matches("ABLESTACK-M-[0-9a-f]{32}"));
            assertTrue(name.length() <= 50);
            assertTrue(names.add(name));
        }
    }

    @Test public void recoveryCatalogRetirementAndDeletionShareTheSubmittedNameAndLegacyLookup() {
        for (boolean metadata : List.of(false, true)) {
            Client client = new Client();
            ThirdPartyBackupManifest.Artifact artifact = artifact("79052427-e010-4dda-b30d-0565456ed4b2", "/mnt/root.raw");
            String name = name(client, artifact, metadata);
            assertTrue(client.script.contains("$names -ccontains $_.Name"));
            client.recoverVolumeArtifact(artifact, metadata);
            assertLookup(client, name, artifact.backupUuid);
            assertFalse(client.script.contains("Copy-VBRComputerBackupJob"));
            assertFalse(client.script.contains("Start-VBRComputerBackupJob"));
            client.volumeArtifactExists(artifact);
            assertLookup(client, name, artifact.backupUuid);
            client.retireVolumeArtifactJob(artifact, metadata);
            assertLookup(client, name, artifact.backupUuid);
            client.deleteVolumeArtifact(artifact, metadata);
            assertLookup(client, name, artifact.backupUuid);
        }
    }

    private void assertLookup(Client client, String name, String backup) {
        assertTrue(client.script.contains("'" + name + "'"));
        assertTrue(client.script.contains("'ABLESTACK-" + backup + "-"));
    }
}
