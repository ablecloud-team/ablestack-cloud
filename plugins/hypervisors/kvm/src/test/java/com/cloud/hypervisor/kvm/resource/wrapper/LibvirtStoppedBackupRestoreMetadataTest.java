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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.backup.AblestackSharedRawBackup;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertThrows;

public class LibvirtStoppedBackupRestoreMetadataTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ThirdPartyBackupManifest manifest(String provider) {
        var manifest = ThirdPartyBackupManifest.create(provider, "backup-1", "original-vm", "point", "FULL", "host-1",
                "/staging/point", "QCOW2", List.of(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT,
                        1073741824L, 0L, null, null, null)), null, null);
        var configuration = new ThirdPartyBackupManifest.SourceConfiguration();
        configuration.vmUuid = "original-uuid";
        manifest.setSourceConfiguration(configuration);
        return manifest;
    }

    @Test public void originalInventoryDescriptorIsAcceptedForEveryProviderWithoutDummyHardware() throws Exception {
        for (String provider : List.of("ablestack-netbackup", "ablestack-commvault", "ablestack-veeam")) {
            Path directory = temporary.newFolder().toPath();
            Files.writeString(directory.resolve("domain-config.xml"), "<domain type='kvm'><name>original-vm</name><uuid>original-uuid</uuid>"
                    + "<metadata><ablestack-source-configuration version='1' configuration='mold' bootable='false'>{}</ablestack-source-configuration></metadata></domain>");
            LibvirtAblestackVolumeRestoreHelper.validateDomainMetadata(directory, manifest(provider));
        }
    }

    @Test public void aDummyNameOrUuidCannotBeUsedAsTheRestoredSource() throws Exception {
        for (String provider : List.of("ablestack-netbackup", "ablestack-commvault", "ablestack-veeam")) {
            for (boolean wrongName : List.of(true, false)) {
                Path directory = temporary.newFolder().toPath();
                Files.writeString(directory.resolve("domain-config.xml"), "<domain><name>" + (wrongName ? "DUMMY-VOLUME-backup-1" : "original-vm")
                        + "</name><uuid>" + (wrongName ? "original-uuid" : "dummy-uuid") + "</uuid></domain>");
                assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackVolumeRestoreHelper.validateDomainMetadata(directory, manifest(provider)));
            }
        }
    }

    @Test public void missingDomainMetadataFailsBeforeAnyPreparedVolumeCanBeApplied() throws Exception {
        Path directory = temporary.newFolder().toPath();
        assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackVolumeRestoreHelper.validateDomainMetadata(directory, manifest("ablestack-netbackup")));
    }

    @Test public void legacyOfflineRawUsesItsRecordedMoldUuid() throws Exception {
        var manifest = manifest("ablestack-netbackup");
        manifest.setSourceConfiguration(null);
        var offline = new AblestackSharedRawBackup.Plan();
        offline.vmUuid = "original-uuid";
        manifest.setOfflineGuard(offline);
        Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("domain-config.xml"), "<domain><name>original-vm</name><uuid>original-uuid</uuid></domain>");
        LibvirtAblestackVolumeRestoreHelper.validateDomainMetadata(directory, manifest);
        Files.writeString(directory.resolve("domain-config.xml"), "<domain><name>original-vm</name><uuid>dummy-uuid</uuid></domain>");
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackVolumeRestoreHelper.validateDomainMetadata(directory, manifest));
    }

    private ThirdPartyBackupManifest.Volume volume(String engine, String... paths) {
        var volume = new ThirdPartyBackupManifest.Volume();
        volume.engine = engine;
        volume.chain = new ArrayList<>();
        for (String path : paths) {
            var artifact = new ThirdPartyBackupManifest.Artifact();
            artifact.path = path;
            volume.chain.add(artifact);
        }
        return volume;
    }

    @Test public void artifactKindsDistinguishFullDiffAndOfflineFiles() {
        for (var volume : List.of(volume("QCOW2", "root.qcow2"), volume("QCOW2", "full.qcow2", "incremental.qcow2"),
                volume("RBD_DIFF", "root.raw"), volume("RBD_DIFF", "root.raw", "disk.rbdiff"),
                volume(AblestackSharedRawBackup.ENGINE, "root.raw"), volume(AblestackSharedRawBackup.ENGINE, "root.qcow2"))) {
            LibvirtAblestackVolumeRestoreHelper.validateArtifactKinds(volume);
        }
        for (var volume : List.of(volume("QCOW2", "root.raw"), volume("RBD_DIFF", "root.rbdiff"),
                volume("RBD_DIFF", "full.raw", "diff.qcow2"), volume(AblestackSharedRawBackup.ENGINE, "full.raw", "incremental.raw"))) {
            assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackVolumeRestoreHelper.validateArtifactKinds(volume));
        }
    }
}
