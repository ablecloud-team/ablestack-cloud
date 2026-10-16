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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.utils.Pair;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import org.apache.cloudstack.backup.AblestackVeeamTakeBackupCommand;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LibvirtAblestackAsyncBackupRunnerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void restorePhasesRetainVolumeIdentityThroughCleanup() {
        Properties properties = new Properties();
        properties.setProperty("state", "RUNNING");
        assertTrue(LibvirtAblestackAsyncBackupRunner.applyRestoreStep(properties, "RESTORE_CONVERT", "DATA · -S 4k", 2, 2));
        assertTrue(LibvirtAblestackAsyncBackupRunner.applyRestoreStep(properties, "RESTORE_COMPARE", "DATA", 2, 2));
        assertEquals("RESTORE_COMPARE", properties.getProperty("step"));
        assertTrue(LibvirtAblestackAsyncBackupRunner.applyRestoreStep(properties, "CLEANUP_SOURCE", "", null, null));
        assertEquals("2", properties.getProperty("volumeIndex"));
        assertEquals("2", properties.getProperty("volumeCount"));
    }

    @Test
    public void cleanupCannotReviveAFailedOrCompletedRestore() {
        for (String state : Arrays.asList("FAILED", "COMPLETED", "CANCELED", "INTERRUPTED")) {
            Properties properties = new Properties();
            properties.setProperty("state", state);
            properties.setProperty("step", state);
            properties.setProperty("details", "original outcome");
            assertFalse(LibvirtAblestackAsyncBackupRunner.applyRestoreStep(properties, "CLEANUP_SOURCE", "cleanup", 2, 2));
            assertEquals(state, properties.getProperty("state"));
            assertEquals(state, properties.getProperty("step"));
            assertEquals("original outcome", properties.getProperty("details"));
        }
    }

    @Test
    public void qcow2SourceCleanupRequiresNewReceiptForTheExactParentAndDiskPaths() throws Exception {
        Path directory = temporaryFolder.newFolder().toPath();
        var plan = new Gson().fromJson("{\"rbd\":false,\"manifest\":{\"backupUuid\":\"job-1\"},"
                + "\"checkpointName\":\"current\",\"parentCheckpointName\":\"parent\",\"diskPaths\":[\"/primary/root\"]}",
                com.google.gson.JsonObject.class);
        Path receipt = directory.resolve("source-cleanup.json");
        Files.writeString(receipt, "{\"backupUuid\":\"job-1\",\"state\":\"COMPLETED\"}");
        assertFalse(LibvirtAblestackAsyncBackupRunner.sourceCleanupCompleted(directory, "job-1", plan));
        var completed = plan.deepCopy();
        completed.remove("rbd");
        completed.remove("manifest");
        completed.addProperty("version", 2);
        completed.addProperty("engine", "QCOW2");
        completed.addProperty("backupUuid", "job-1");
        completed.addProperty("state", "COMPLETED");
        completed.add("errors", new com.google.gson.JsonArray());
        Files.writeString(receipt, completed.toString());
        assertTrue(LibvirtAblestackAsyncBackupRunner.sourceCleanupCompleted(directory, "job-1", plan));
        completed.addProperty("parentCheckpointName", "another-parent");
        Files.writeString(receipt, completed.toString());
        assertFalse(LibvirtAblestackAsyncBackupRunner.sourceCleanupCompleted(directory, "job-1", plan));
        completed.addProperty("parentCheckpointName", "parent");
        completed.add("diskPaths", new Gson().toJsonTree(new String[]{"/primary/another-disk"}));
        Files.writeString(receipt, completed.toString());
        assertFalse(LibvirtAblestackAsyncBackupRunner.sourceCleanupCompleted(directory, "job-1", plan));
    }

    @Test
    public void missingJobIdDoesNotFailProgressCapabilityChecks() {
        Properties properties = volumeJob("ablestack-commvault", "QCOW2_BACKUP");
        properties.remove("jobId");
        assertTrue(LibvirtAblestackAsyncBackupRunner.resolveCapabilities(properties).contains("progress"));
    }

    @Test
    public void statusReadersRetainCompleteRecordDuringInterruptedWrite() throws Exception {
        Path path = temporaryFolder.getRoot().toPath().resolve("job.properties");
        Properties initial = volumeJob("ablestack-commvault", "QCOW2_BACKUP");
        initial.setProperty("state", "RUNNING");
        LibvirtAblestackAsyncBackupRunner.writeJobProperties(path, initial);
        String previous = Files.readString(path);
        Properties interrupted = new Properties() {
            @Override
            public void store(OutputStream output, String comments) throws IOException {
                output.write("state=FAILED\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                assertEquals(previous, Files.readString(path));
                throw new IOException("Interrupted status write");
            }
        };
        try {
            LibvirtAblestackAsyncBackupRunner.writeJobProperties(path, interrupted);
            fail("An interrupted write must not replace the status record");
        } catch (IOException expected) {
            assertEquals("Interrupted status write", expected.getMessage());
        }
        assertEquals(previous, Files.readString(path));
        assertEquals(1, temporaryFolder.getRoot().list().length);
        initial.setProperty("state", "COMPLETED");
        LibvirtAblestackAsyncBackupRunner.writeJobProperties(path, initial);
        Properties completed = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            completed.load(input);
        }
        assertEquals(initial.getProperty("jobId"), completed.getProperty("jobId"));
        assertEquals("COMPLETED", completed.getProperty("state"));
    }

    @Test
    public void qcow2VolumeControlsAreCommonToAllProviders() {
        for (String provider : Arrays.asList("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            Properties properties = volumeJob(provider, "QCOW2_BACKUP");
            Set<String> capabilities = Set.copyOf(Arrays.asList(LibvirtAblestackAsyncBackupRunner.resolveCapabilities(properties).split(",")));
            assertTrue(capabilities.contains("live-bandwidth"));
            assertTrue(capabilities.contains("cancel"));
        }
    }

    @Test
    public void externalTransferAndFinalizationDoNotExposeSourceControls() {
        for (String step : Arrays.asList("COMMVAULT_TRANSFER", "NETBACKUP_TRANSFER", "VEEAM_TRANSFER", "METADATA_TRANSFER", "FINALIZING")) {
            Properties properties = volumeJob("ablestack-commvault", step);
            properties.setProperty("liveBandwidthActive", "false");
            Set<String> capabilities = Set.copyOf(Arrays.asList(LibvirtAblestackAsyncBackupRunner.resolveCapabilities(properties).split(",")));
            assertFalse(capabilities.contains("live-bandwidth"));
            assertFalse(capabilities.contains("cancel"));
        }
    }

    @Test
    public void rbdExportsSupportCancelWithoutLiveBandwidth() {
        for (String step : Arrays.asList("RBD_EXPORT", "RBD_EXPORT_DIFF")) {
            Properties properties = volumeJob("ablestack-netbackup", step);
            properties.setProperty("liveBandwidthSupported", "false");
            properties.setProperty("liveBandwidthActive", "false");
            Set<String> capabilities = Set.copyOf(Arrays.asList(LibvirtAblestackAsyncBackupRunner.resolveCapabilities(properties).split(",")));
            assertFalse(capabilities.contains("live-bandwidth"));
            assertTrue(capabilities.contains("cancel"));
        }
    }

    @Test
    public void veeamBandwidthSurvivesCommandSerializationAndDefaultsToUnlimited() {
        Gson gson = new Gson();
        AblestackVeeamTakeBackupCommand command = new AblestackVeeamTakeBackupCommand("vm", "/staging");
        command.setBandwidthLimitMbps(128);
        assertEquals(Integer.valueOf(128), gson.fromJson(gson.toJson(command), AblestackVeeamTakeBackupCommand.class).getBandwidthLimitMbps());
        assertEquals(Integer.valueOf(0), gson.fromJson("{}", AblestackVeeamTakeBackupCommand.class).getBandwidthLimitMbps());
    }

    private Properties volumeJob(String provider, String step) {
        Properties properties = new Properties();
        properties.setProperty("jobId", "qcow2-control-test");
        properties.setProperty("provider", provider);
        properties.setProperty("operation", "BACKUP");
        properties.setProperty("volumePipeline", "true");
        properties.setProperty("step", step);
        properties.setProperty("liveBandwidthSupported", "true");
        properties.setProperty("liveBandwidthActive", "true");
        return properties;
    }

    @Test
    public void hungControlCommandReturnsUnconfirmed() {
        long started = System.nanoTime();
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(150, "/bin/sh", "-c", "sleep 5");
        assertEquals(Integer.valueOf(124), result.first());
        assertTrue(result.second().contains("unconfirmed"));
        assertTrue("Control call must not wait for the data-operation budget", System.nanoTime() - started < 3000000000L);
    }

    @Test
    public void exitedCommandDoesNotWaitForChildToCloseOutput() {
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(2000, "/bin/sh", "-c", "sleep 1 & printf accepted");
        assertEquals(Integer.valueOf(0), result.first());
        assertEquals("accepted", result.second());
    }

    @Test
    public void controlErrorsRetainExitCodeAndDiagnostics() {
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(2000, "/bin/sh", "-c", "printf unavailable >&2; exit 7");
        assertEquals(Integer.valueOf(7), result.first());
        assertEquals("unavailable", result.second());
    }

    @Test
    public void successfulAbortIsNotProofThatDomainJobStopped() {
        assertFalse(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(0, "Job type: Unbounded\nOperation: Backup")));
        assertFalse(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(124, "Job type: None")));
        assertFalse(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(0, "")));
        assertTrue(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(0, "Job type: None\n")));
    }
}
