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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.AblestackN2KConvertInstanceCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@ResourceWrapper(handles = AblestackN2KConvertInstanceCommand.class)
public class LibvirtAblestackN2KConvertInstanceCommandWrapper extends CommandWrapper<AblestackN2KConvertInstanceCommand, Answer, LibvirtComputingResource> {

    private static final String DEFAULT_TARGET_PROVIDER = "libvirt";
    private static final String CLOUD_TARGET_PROVIDER = "ablestack-cloud";
    private static final String DEFAULT_CUTOVER_SHUTDOWN_POLICY = "guest";

    @Override
    public Answer execute(AblestackN2KConvertInstanceCommand cmd, LibvirtComputingResource serverResource) {
        List<String> missingParams = new ArrayList<>();
        if (StringUtils.isBlank(cmd.getVmName())) {
            missingParams.add("vmName");
        }
        if (StringUtils.isBlank(cmd.getPrismEndpoint())) {
            missingParams.add("prismEndpoint");
        }
        if (StringUtils.isBlank(cmd.getUsername())) {
            missingParams.add("username");
        }
        if (StringUtils.isBlank(cmd.getPassword())) {
            missingParams.add("password");
        }
        if (StringUtils.isBlank(cmd.getWorkdir())) {
            missingParams.add("workdir");
        }
        if (cmd.getTargetStorageLocation() == null) {
            missingParams.add("targetStorageLocation");
        }
        if (StringUtils.isBlank(cmd.getTargetFormat())) {
            missingParams.add("targetFormat");
        }
        if (StringUtils.isBlank(cmd.getTargetStorage())) {
            missingParams.add("targetStorage");
        }
        if ((StringUtils.equals(cmd.getTargetStorage(), "rbd") || StringUtils.equals(cmd.getTargetStorage(), "block"))
                && StringUtils.isBlank(cmd.getTargetMapJson())) {
            missingParams.add("targetMapJson");
        }
        String targetProvider = StringUtils.defaultIfBlank(cmd.getTargetProvider(), DEFAULT_TARGET_PROVIDER);
        if (StringUtils.equals(targetProvider, CLOUD_TARGET_PROVIDER)) {
            if (StringUtils.isBlank(cmd.getCloudEndpoint())) {
                missingParams.add("cloudEndpoint");
            }
            if (StringUtils.isBlank(cmd.getCloudApiKey())) {
                missingParams.add("cloudApiKey");
            }
            if (StringUtils.isBlank(cmd.getCloudSecretKey())) {
                missingParams.add("cloudSecretKey");
            }
            if (StringUtils.isBlank(cmd.getCloudZoneId())) {
                missingParams.add("cloudZoneId");
            }
            if (StringUtils.isBlank(cmd.getCloudServiceOfferingId())) {
                missingParams.add("cloudServiceOfferingId");
            }
            if (StringUtils.isBlank(cmd.getCloudNetworkIds())) {
                missingParams.add("cloudNetworkIds");
            }
            if (StringUtils.isBlank(cmd.getCloudStorageId())) {
                missingParams.add("cloudStorageId");
            }
        }
        if (!missingParams.isEmpty()) {
            return new Answer(cmd, false, "Missing required parameter(s) for ablestack_n2k command: " + String.join(", ", missingParams));
        }
        if (!StringUtils.equals(StringUtils.defaultIfBlank(cmd.getSourceApi(), "v3"), "v3")) {
            return new Answer(cmd, false, "ablestack_n2k run currently supports sourceApi=v3 for Cloud-managed execution");
        }

        if (cmd.isResume() && !Files.isRegularFile(Path.of(cmd.getWorkdir(), "manifest.json"))) {
            return new Answer(cmd, false, "Cannot resume ablestack_n2k without its existing migration manifest");
        }

        final long timeout = (long) cmd.getWait() * 1000;
        final KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();
        final KVMStoragePool targetStoragePool = getTargetStoragePool(cmd.getTargetStorageLocation(), storagePoolMgr);
        final String targetStoragePath = StringUtils.defaultIfBlank(cmd.getTargetDestinationPath(), getTargetStoragePath(cmd, targetStoragePool));
        final boolean background = StringUtils.equalsIgnoreCase(cmd.getSplitMode(), "phase1") || StringUtils.equalsIgnoreCase(cmd.getSplitMode(), "phase2");

        Path credentialFile = null;
        try {
            credentialFile = createN2KCredentialFile(cmd);
            Path cloudCredentialFile = createN2KCloudCredentialFile(cmd, targetProvider);
            Script script = new Script("ablestack_n2k", timeout, logger);
            script.add("--workdir", cmd.getWorkdir());
            // The CLI's global --resume only prints a plan. Run resumes completed
            // steps from the existing manifest when given the same work directory.
            script.add("run");
            if (background) {
                script.add("--background");
            }
            script.add("--vm", cmd.getVmName());
            script.add("--pc", cmd.getPrismEndpoint());
            script.add("--cred-file", credentialFile.toString());
            script.add("--insecure", cmd.isInsecure() ? "1" : "0");
            script.add("--split", StringUtils.defaultIfBlank(cmd.getSplitMode(), "phase1"));
            script.add("--shutdown", DEFAULT_CUTOVER_SHUTDOWN_POLICY);
            script.add("--source-api", "v3");
            if (cmd.getRetentionSeconds() != null && cmd.getRetentionSeconds() > 0) {
                script.add("--retention-seconds", String.valueOf(cmd.getRetentionSeconds()));
            }
            addIfNotBlank(script, "--nfs-host", cmd.getNfsHost());
            script.add("--source-map-from-v3-nfs");
            script.add("--target-provider", targetProvider);
            script.add("--target-format", cmd.getTargetFormat());
            script.add("--target-storage", cmd.getTargetStorage());
            script.add("--dst", targetStoragePath);
            script.add("--cleanup-source-points");
            if (StringUtils.isNotBlank(cmd.getTargetMapJson())) {
                script.add("--target-map-json", cmd.getTargetMapJson());
            }
            if (StringUtils.equals(targetProvider, CLOUD_TARGET_PROVIDER)) {
                script.add(cmd.isStartTargetVm() ? "--start" : "--apply");
                script.add("--cloud-cred-file", cloudCredentialFile.toString());
                addIfNotBlank(script, "--cloud-zone-id", cmd.getCloudZoneId());
                addIfNotBlank(script, "--cloud-service-offering-id", cmd.getCloudServiceOfferingId());
                addIfNotBlank(script, "--cloud-network-ids", cmd.getCloudNetworkIds());
                addIfNotBlank(script, "--cloud-storage-id", cmd.getCloudStorageId());
                addIfNotBlank(script, "--cloud-disk-offering-id", cmd.getCloudDiskOfferingId());
                addIfNotBlank(script, "--cloud-host-id", cmd.getCloudHostId());
                addIfNotBlank(script, "--cloud-account", cmd.getCloudAccount());
                addIfNotBlank(script, "--cloud-domain-id", cmd.getCloudDomainId());
                addIfNotBlank(script, "--cloud-project-id", cmd.getCloudProjectId());
                addIfNotBlank(script, "--cloud-name", cmd.getCloudName());
                addIfNotBlank(script, "--cloud-display-name", cmd.getCloudDisplayName());
                addIfNotBlank(script, "--cloud-cpu-speed", cmd.getCloudCpuSpeed());
            }

            String logPrefix = String.format("(%s) ablestack_n2k run progress", cmd.getVmName());
            OutputInterpreter outputLogger = new CapturingLineByLineOutputLogger(logPrefix);
            String result = script.execute(outputLogger);
            int exitValue = script.getExitValue();
            if (exitValue != 0) {
                return new Answer(cmd, false, StringUtils.defaultIfBlank(result,
                        String.format("ablestack_n2k command failed with exit code %d", exitValue)));
            }
        } catch (IOException e) {
            return new Answer(cmd, false, "Unable to create protected ablestack_n2k credential file: " + e.getMessage());
        } finally {
            if (!background) {
                deleteN2KCredentialFile(credentialFile);
            }
        }
        return new Answer(cmd, true, "ablestack_n2k command completed successfully");
    }

    private KVMStoragePool getTargetStoragePool(DataStoreTO targetStorageLocation, KVMStoragePoolManager storagePoolMgr) {
        if (targetStorageLocation instanceof NfsTO) {
            NfsTO nfsTO = (NfsTO) targetStorageLocation;
            return storagePoolMgr.getStoragePoolByURI(nfsTO.getUrl());
        }
        PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO) targetStorageLocation;
        return storagePoolMgr.getStoragePool(primaryDataStoreTO.getPoolType(), primaryDataStoreTO.getUuid());
    }

    protected String getTargetStoragePath(AblestackN2KConvertInstanceCommand cmd, KVMStoragePool targetStoragePool) {
        if (StringUtils.equals(cmd.getTargetStorage(), "rbd")) {
            return "/var/lib/libvirt/images" + File.separator + cmd.getVmName();
        }
        return targetStoragePool.getLocalPath();
    }

    private Path createN2KCredentialFile(AblestackN2KConvertInstanceCommand cmd) throws IOException {
        Path workdir = Path.of(cmd.getWorkdir());
        Files.createDirectories(workdir);
        Path credentialFile = workdir.resolve("nutanix.env");
        Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        String content = String.format("NUTANIX_USERNAME=%s%nNUTANIX_PASSWORD=%s%nN2K_PC_USERNAME=%s%nN2K_PC_PASSWORD=%s%n",
                shellQuote(cmd.getUsername()), shellQuote(cmd.getPassword()),
                shellQuote(cmd.getUsername()), shellQuote(cmd.getPassword()));
        Files.write(credentialFile, content.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(credentialFile, permissions);
        return credentialFile;
    }

    private Path createN2KCloudCredentialFile(AblestackN2KConvertInstanceCommand cmd, String targetProvider) throws IOException {
        if (!StringUtils.equals(targetProvider, CLOUD_TARGET_PROVIDER)) {
            return null;
        }
        Path workdir = Path.of(cmd.getWorkdir());
        Files.createDirectories(workdir);
        Path credentialFile = workdir.resolve("cloud.env");
        Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        String content = String.format("N2K_CLOUD_ENDPOINT=%s%nN2K_CLOUD_API_KEY=%s%nN2K_CLOUD_SECRET_KEY=%s%n",
                shellQuote(cmd.getCloudEndpoint()), shellQuote(cmd.getCloudApiKey()), shellQuote(cmd.getCloudSecretKey()));
        Files.write(credentialFile, content.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(credentialFile, permissions);
        return credentialFile;
    }

    private void deleteN2KCredentialFile(Path credentialFile) {
        if (credentialFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(credentialFile);
        } catch (IOException e) {
            logger.warn("Unable to delete temporary ablestack_n2k credential file {}", credentialFile, e);
        }
    }

    private String shellQuote(String value) {
        return "'" + StringUtils.defaultString(value).replace("'", "'\"'\"'") + "'";
    }

    private void addIfNotBlank(Script script, String option, String value) {
        if (StringUtils.isNotBlank(value)) {
            script.add(option, value);
        }
    }

    private class CapturingLineByLineOutputLogger extends OutputInterpreter {
        private final String logPrefix;
        private final StringBuilder output = new StringBuilder();

        private CapturingLineByLineOutputLogger(String logPrefix) {
            this.logPrefix = logPrefix;
        }

        @Override
        public boolean drain() {
            return true;
        }

        @Override
        public String interpret(java.io.BufferedReader reader) throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(StringUtils.isNotBlank(logPrefix) ? String.format("(%s) %s", logPrefix, line) : line);
                synchronized (output) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            return null;
        }

        @Override
        public String processError(java.io.BufferedReader reader) {
            synchronized (output) {
                return output.toString();
            }
        }
    }

}
