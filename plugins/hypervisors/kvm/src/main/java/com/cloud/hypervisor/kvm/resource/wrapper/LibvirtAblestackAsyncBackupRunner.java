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

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.utils.Pair;
import com.google.gson.Gson;

import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.StopBackupAnswer;
import org.apache.cloudstack.backup.StopBackupCommand;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class LibvirtAblestackAsyncBackupRunner {
    static final String STATE_STARTED = "STARTED";
    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_COMPLETED = "COMPLETED";
    static final String STATE_FAILED = "FAILED";
    static final String STATE_CANCELED = "CANCELED";
    static final String STATE_INTERRUPTED = "INTERRUPTED";
    private static final Path JOB_ROOT = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT);
    private static final String EXIT_CODE_FILE = "exit-code";
    private static final String LOG_FILE = "job.log";
    private static final String EVENTS_FILE = "events.jsonl";
    private static final String SCRIPT_FILE = "run.sh";
    private static final String BACKUP_COMMAND_FILE = "backup-command.json";
    private static final String RESTORE_COMMAND_FILE = "restore-command.json";
    private static final String RBD_PROGRESS_FILE = "rbd-progress.properties";
    private static final int DEFAULT_EVENTS_LIMIT = 50;
    private static final int MAX_EVENTS_LIMIT = 200;
    private static final int LIVE_PROGRESS_MINIMUM = 10;
    private static final int LIVE_PROGRESS_MAXIMUM = 95;
    private static final Set<String> ACTIVE_JOBS = ConcurrentHashMap.newKeySet();

    private LibvirtAblestackAsyncBackupRunner() {
    }

    static BackupAnswer startDetached(final Command command, final Logger logger, final String trace, final String provider, final String jobId,
            final String vmName, final String backupPath, final String backupType, final String[] scriptCommand) {
        return startDetached(command, logger, trace, provider, jobId, vmName, backupPath, backupType, scriptCommand, false);
    }

    static BackupAnswer startDetached(final Command command, final Logger logger, final String trace, final String provider, final String jobId,
            final String vmName, final String backupPath, final String backupType, final String[] scriptCommand,
            final boolean liveBandwidthSupported) {
        final String effectiveJobId = safeValue(jobId);
        if (effectiveJobId.isBlank()) {
            return new BackupAnswer(command, false, "backup job id is required for detached execution");
        }
        if (scriptCommand == null || scriptCommand.length == 0) {
            return new BackupAnswer(command, false, "backup script command is required for detached execution");
        }

        final String unitName = getSystemdUnitName(effectiveJobId);
        try {
            Files.createDirectories(getJobDirectory(effectiveJobId));
            writeDetachedScript(effectiveJobId, provider, vmName, backupPath, backupType, scriptCommand);
            writeJobState(logger, effectiveJobId, provider, vmName, backupPath, backupType, STATE_STARTED,
                    "Detached backup job prepared");
            Properties properties = readJob(effectiveJobId, logger);
            if (properties != null) {
                properties.setProperty("unitName", unitName);
                properties.setProperty("launcher", "systemd-run");
                properties.setProperty("script", getDetachedScriptPath(effectiveJobId).toString());
                properties.setProperty("log", getJobDirectory(effectiveJobId).resolve(LOG_FILE).toString());
                properties.setProperty("liveBandwidthSupported", String.valueOf(liveBandwidthSupported));
                properties.setProperty("capabilities", resolveCapabilities(properties));
                storeJobProperties(logger, effectiveJobId, properties);
            }
            writeJobState(logger, effectiveJobId, provider, vmName, backupPath, backupType, STATE_RUNNING,
                    "Detached backup job is being started by systemd-run");

            Pair<Integer, String> launchResult = launchDetached(effectiveJobId, unitName);
            if (launchResult.first() != 0) {
                writeJobState(logger, effectiveJobId, provider, vmName, backupPath, backupType, STATE_FAILED, launchResult.second());
                logger.warn("{} phase=[AGENT_DETACHED_START_FAILED], provider=[{}], jobId=[{}], vm=[{}], backupPath=[{}], "
                                + "backupType=[{}], unit=[{}], reason=[{}]",
                        trace, provider, effectiveJobId, vmName, backupPath, backupType, unitName, launchResult.second());
                return new BackupAnswer(command, false, launchResult.second());
            }

            logger.info("{} phase=[AGENT_DETACHED_STARTED], provider=[{}], jobId=[{}], vm=[{}], backupPath=[{}], "
                            + "backupType=[{}], unit=[{}], command=[{}]",
                    trace, provider, effectiveJobId, vmName, backupPath, backupType, unitName, formatCommand(scriptCommand));
            return new BackupAnswer(command, true, "started");
        } catch (IOException e) {
            writeJobState(logger, effectiveJobId, provider, vmName, backupPath, backupType, STATE_FAILED, e.getMessage());
            logger.warn("{} phase=[AGENT_DETACHED_START_FAILED], provider=[{}], jobId=[{}], vm=[{}], backupPath=[{}], "
                            + "backupType=[{}], unit=[{}], reason=[{}]",
                    trace, provider, effectiveJobId, vmName, backupPath, backupType, unitName, e.getMessage(), e);
            return new BackupAnswer(command, false, e.getMessage());
        }
    }

    static BackupAnswer startDetachedRestore(final Command command, final Logger logger, final String trace, final String provider,
            final String jobId, final String vmName, final String backupPath) {
        return startDetachedJavaJob(command, logger, trace, provider, jobId, vmName, backupPath, "RESTORE", RESTORE_COMMAND_FILE);
    }

    static BackupAnswer startDetachedBackup(final Command command, final Logger logger, final String trace, final String provider,
            final String jobId, final String vmName, final String backupPath, final String backupType) {
        return startDetachedJavaJob(command, logger, trace, provider, jobId, vmName, backupPath, backupType, BACKUP_COMMAND_FILE);
    }

    private static BackupAnswer startDetachedJavaJob(final Command command, final Logger logger, final String trace, final String provider,
            final String jobId, final String vmName, final String backupPath, final String backupType, final String commandFile) {
        if (command == null || safeValue(jobId).isBlank()) {
            return new BackupAnswer(command, false, "backup command and job id are required for detached execution");
        }
        try {
            final Path jobDirectory = getJobDirectory(jobId);
            Files.createDirectories(jobDirectory);
            final Path commandPath = jobDirectory.resolve(commandFile);
            Files.writeString(commandPath, new Gson().toJson(command), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(commandPath, PosixFilePermissions.fromString("rw-------"));
            final String runnerCommand = ". /etc/default/cloudstack-agent; exec /usr/bin/java $JAVA_OPTS -cp \"$CLASSPATH\" "
                    + LibvirtAblestackJobMain.class.getName() + " " + shellQuote(command.getClass().getName()) + " "
                    + shellQuote(commandPath.toString());
            return startDetached(command, logger, trace, provider, jobId, vmName, backupPath, backupType,
                    new String[]{"/bin/bash", "-lc", runnerCommand});
        } catch (IOException e) {
            writeJobState(logger, jobId, provider, vmName, backupPath, backupType, STATE_FAILED, e.getMessage());
            return new BackupAnswer(command, false, "Failed to prepare detached backup job: " + e.getMessage());
        }
    }

    static String getJobState(final String jobId, final Logger logger) {
        Properties properties = readJob(jobId, logger);
        if (properties == null) {
            return "UNKNOWN";
        }
        String state = properties.getProperty("state", "UNKNOWN");
        if ((STATE_STARTED.equals(state) || STATE_RUNNING.equals(state)) && !ACTIVE_JOBS.contains(jobId)) {
            String detachedState = resolveDetachedState(jobId, properties, logger);
            if (!STATE_INTERRUPTED.equals(detachedState)) {
                return detachedState;
            }
            writeJobState(logger, jobId, properties.getProperty("provider"), properties.getProperty("vmName"),
                    properties.getProperty("backupPath"), properties.getProperty("backupType"), STATE_INTERRUPTED,
                    "Agent restarted or async worker is no longer active");
            logger.warn("{} phase=[JOB_INTERRUPTED], jobId=[{}], state=[{}], vm=[{}], backupPath=[{}], previousState=[{}]",
                    getTracePrefix(properties), jobId, STATE_INTERRUPTED, properties.getProperty("vmName"),
                    properties.getProperty("backupPath"), state);
            return STATE_INTERRUPTED;
        }
        logger.info("{} phase=[JOB_STATUS_QUERIED], jobId=[{}], vm=[{}], backupPath=[{}], state=[{}]",
                getTracePrefix(properties), jobId, properties.getProperty("vmName"), properties.getProperty("backupPath"), state);
        return state;
    }

    static BackupAnswer getJobStatus(final Command command, final String jobId, final Long eventsOffset, final Integer eventsLimit,
            final Logger logger) {
        final String state = getJobState(jobId, logger);
        final Properties properties = ensureCommonJobMetadata(jobId, refreshLiveProgress(jobId, readJob(jobId, logger), state, logger), logger);
        final String details = properties != null ? properties.getProperty("details", state) : state;
        final BackupAnswer answer = new BackupAnswer(command, true, details);
        answer.setState(state);
        answer.setStep(properties != null ? properties.getProperty("step", state) : state);
        answer.setProgress(properties != null && Boolean.parseBoolean(properties.getProperty("progressUnavailable")) ? null : resolveProgress(state, properties));
        answer.setOperation(properties != null ? properties.getProperty("operation") : null);
        answer.setCapabilities(properties != null ? properties.getProperty("capabilities") : null);
        answer.setBandwidthLimitMbps(parseNullableInteger(properties != null ? properties.getProperty("bandwidthLimitMbps") : null));
        answer.setBandwidthStatus(properties != null ? properties.getProperty("bandwidthStatus") : null);
        answer.setEventsJson(readJobEventsJson(jobId, eventsOffset, eventsLimit, answer));
        if (jobId != null && !jobId.isBlank()) {
            final String operation = properties != null ? properties.getProperty("operation") : null;
            answer.setLogPath(AblestackBackupFrameworkUtils.OPERATION_RESTORE.equals(operation) ? AblestackBackupFrameworkUtils.getAsyncRestoreJobLogPath(jobId) :
                    AblestackBackupFrameworkUtils.getAsyncBackupJobLogPath(jobId));
        }
        answer.setExitCode(readExitCode(jobId, logger));
        return answer;
    }

    static void markRestoreJobRunning(final Logger logger, final String provider, final String jobId,
            final String vmName, final String backupPath, final String details) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        ACTIVE_JOBS.add(jobId);
        writeJobState(logger, jobId, provider, vmName, backupPath, "RESTORE", STATE_RUNNING, details);
    }

    static void markRestoreJobStep(final Logger logger, final String provider, final String jobId,
            final String vmName, final String backupPath, final String step, final String details) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        Properties properties = readJob(jobId, logger);
        if (properties == null) {
            writeJobState(logger, jobId, provider, vmName, backupPath, "RESTORE", STATE_RUNNING, details);
            properties = readJob(jobId, logger);
        }
        if (properties == null) {
            return;
        }
        properties.setProperty("jobId", jobId);
        properties.setProperty("provider", safeValue(provider));
        properties.setProperty("vmName", safeValue(vmName));
        properties.setProperty("backupPath", safeValue(backupPath));
        properties.setProperty("backupType", "RESTORE");
        properties.setProperty("operation", AblestackBackupFrameworkUtils.OPERATION_RESTORE);
        properties.setProperty("state", STATE_RUNNING);
        properties.setProperty("step", safeValue(step));
        properties.setProperty("progress", String.valueOf(resolveRestoreStepProgress(step)));
        properties.setProperty("updated", String.valueOf(System.currentTimeMillis()));
        if (details != null) {
            properties.setProperty("details", details);
        }
        storeJobProperties(logger, jobId, properties);
        appendJobEvent(logger, jobId, provider, vmName, backupPath, "RESTORE", STATE_RUNNING, details, properties);
    }

    static void markRestoreJobCompleted(final Logger logger, final String provider, final String jobId,
            final String vmName, final String backupPath, final String details) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        writeJobState(logger, jobId, provider, vmName, backupPath, "RESTORE", STATE_COMPLETED, details);
        ACTIVE_JOBS.remove(jobId);
    }

    static void markRestoreJobFailed(final Logger logger, final String provider, final String jobId,
            final String vmName, final String backupPath, final String details) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        writeJobState(logger, jobId, provider, vmName, backupPath, "RESTORE", STATE_FAILED, details);
        ACTIVE_JOBS.remove(jobId);
    }

    static StopBackupAnswer cancelJob(final StopBackupCommand command, final String jobId, final Logger logger) {
        final Properties properties = readJob(jobId, logger);
        final String vmName = properties != null ? properties.getProperty("vmName") : command.getVmName();
        StringBuilder details = new StringBuilder();
        if (properties == null) {
            if (safeValue(vmName).isBlank()) {
                return new StopBackupAnswer(command, false, "Backup job state was not found");
            }
            Pair<Integer, String> virshResult = executeAndCapture("virsh", "-c", "qemu:///system", "domjobabort", "--domain", vmName);
            details.append("domjobabort rc=").append(virshResult.first()).append(" ");
            if (!virshResult.second().isBlank()) {
                details.append(virshResult.second()).append(" ");
            }
            return new StopBackupAnswer(command, virshResult.first() == 0,
                    details.length() > 0 ? details.toString().trim() : "Backup job cancel requested");
        }
        final String provider = properties.getProperty("provider");
        final String backupPath = properties.getProperty("backupPath");
        final String backupType = properties.getProperty("backupType");
        final String operation = properties.getProperty("operation", AblestackBackupFrameworkUtils.resolveJobOperation(backupType));
        final String unitName = properties.getProperty("unitName");
        final String state = getJobState(jobId, logger);
        if (STATE_COMPLETED.equals(state) || STATE_FAILED.equals(state) || STATE_CANCELED.equals(state)) {
            return new StopBackupAnswer(command, false, operation + " job is already " + state);
        }
        properties.setProperty("cancelRequested", Boolean.TRUE.toString());
        storeJobProperties(logger, jobId, properties);
        if (!safeValue(unitName).isBlank()) {
            Pair<Integer, String> unitResult = executeAndCapture("systemctl", "kill", "--signal=TERM", unitName);
            details.append("systemd unit cancel rc=").append(unitResult.first()).append(" ");
            if (!unitResult.second().isBlank()) {
                details.append(unitResult.second()).append(" ");
            }
        }
        if (!safeValue(vmName).isBlank()) {
            Pair<Integer, String> virshResult = executeAndCapture("virsh", "-c", "qemu:///system", "domjobabort", "--domain", vmName);
            details.append("domjobabort rc=").append(virshResult.first()).append(" ");
            if (!virshResult.second().isBlank()) {
                details.append(virshResult.second()).append(" ");
            }
        }
        ACTIVE_JOBS.remove(jobId);
        final String message = operation + " job cancel requested";
        writeJobState(logger, jobId, provider, vmName, backupPath, backupType, STATE_CANCELED, message);
        return new StopBackupAnswer(command, true, details.length() > 0 ? details.toString().trim() : message);
    }

    static BackupAnswer updateBandwidthLimit(final Command command, final String jobId, final Integer bandwidthLimitMbps, final Logger logger) {
        final int effectiveLimitMbps = bandwidthLimitMbps != null ? bandwidthLimitMbps : 0;
        if (effectiveLimitMbps < 0) {
            return new BackupAnswer(command, false, "Bandwidth limit must be zero or greater");
        }

        final Properties properties = readJob(jobId, logger);
        if (properties == null) {
            return new BackupAnswer(command, false, "Backup job state was not found");
        }
        final String state = getJobState(jobId, logger);
        if (!STATE_RUNNING.equals(state)) {
            return new BackupAnswer(command, false, "Backup job is not running");
        }

        if (isRbdBackup(properties)) {
            return new BackupAnswer(command, false, "RBD backup jobs do not support live bandwidth changes on this host");
        }

        final String vmName = properties.getProperty("vmName");
        if (safeValue(vmName).isBlank()) {
            return new BackupAnswer(command, false, "Backup job VM name was not found");
        }

        properties.setProperty("bandwidthLimitMbps", String.valueOf(effectiveLimitMbps));
        properties.setProperty("bandwidthStatus", "applying");
        properties.setProperty("bandwidthLimitUpdated", String.valueOf(System.currentTimeMillis()));
        storeJobProperties(logger, jobId, properties);

        final int virshLimitMiBps = effectiveLimitMbps <= 0 ? 0 : Math.max(1, (effectiveLimitMbps + 7) / 8);
        final Pair<Integer, String> result = executeAndCapture("bash", "-lc", buildBlockJobBandwidthCommand(vmName, virshLimitMiBps));
        final boolean applied = result.first() == 0;
        properties.setProperty("bandwidthStatus", applied ? "applied" : "failed");
        properties.setProperty("bandwidthLimitUpdated", String.valueOf(System.currentTimeMillis()));
        storeJobProperties(logger, jobId, properties);

        final String details = applied ? "Updated backup bandwidth limit to " + effectiveLimitMbps + " Mbps" :
                "Backup bandwidth update failed; backup job will continue";
        appendJobEvent(logger, jobId, properties.getProperty("provider"), vmName, properties.getProperty("backupPath"),
                properties.getProperty("backupType"), STATE_RUNNING, details, properties);
        return new BackupAnswer(command, applied, applied ? details : "Failed to update backup bandwidth: " + result.second());
    }

    static Answer cleanupJob(final Command command, final String jobId, final Logger logger) {
        if (jobId == null || jobId.isBlank()) {
            return new Answer(command, false, "backup job id is required");
        }
        final Path jobDirectory = getJobDirectory(jobId);
        final Path jobProperties = getJobPath(jobId);
        try {
            if (Files.exists(jobDirectory)) {
                try (Stream<Path> paths = Files.walk(jobDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            }
            Files.deleteIfExists(jobProperties);
            ACTIVE_JOBS.remove(jobId);
            logger.info("Cleaned ABLESTACK backup job files. jobId=[{}], jobDir=[{}], properties=[{}]",
                    jobId, jobDirectory, jobProperties);
            return new Answer(command, true, "Backup job files cleaned");
        } catch (IOException | UncheckedIOException e) {
            logger.warn("Failed to clean ABLESTACK backup job files. jobId=[{}], jobDir=[{}], properties=[{}]",
                    jobId, jobDirectory, jobProperties, e);
            return new Answer(command, false, "Failed to clean backup job files: " + e.getMessage());
        }
    }

    private static String resolveDetachedState(final String jobId, final Properties properties, final Logger logger) {
        final Path exitCodePath = getJobDirectory(jobId).resolve(EXIT_CODE_FILE);
        if (Files.exists(exitCodePath)) {
            try {
                final String exitCode = Files.readString(exitCodePath).trim();
                final String resolvedState = isCancelRequested(properties) ? STATE_CANCELED : "0".equals(exitCode) ? STATE_COMPLETED : STATE_FAILED;
                writeJobState(logger, jobId, properties.getProperty("provider"), properties.getProperty("vmName"),
                        properties.getProperty("backupPath"), properties.getProperty("backupType"), resolvedState,
                        "Detached backup job exited with code " + exitCode);
                logger.info("{} phase=[DETACHED_EXIT_CODE_RESOLVED], jobId=[{}], exitCode=[{}], state=[{}]",
                        getTracePrefix(properties), jobId, exitCode, resolvedState);
                return resolvedState;
            } catch (IOException e) {
                logger.warn("Failed to read ABLESTACK detached backup exit code for job [{}]", jobId, e);
            }
        }

        final String unitName = properties.getProperty("unitName");
        if (safeValue(unitName).isBlank()) {
            return STATE_INTERRUPTED;
        }
        if (isSystemdUnitActive(unitName, logger)) {
            logger.info("{} phase=[DETACHED_UNIT_ACTIVE], jobId=[{}], unit=[{}], log=[{}]",
                    getTracePrefix(properties), jobId, unitName, getJobDirectory(jobId).resolve(LOG_FILE));
            return STATE_RUNNING;
        }
        return STATE_INTERRUPTED;
    }

    private static boolean isCancelRequested(final String jobId, final Logger logger) {
        return isCancelRequested(readJob(jobId, logger));
    }

    private static boolean isCancelRequested(final Properties properties) {
        return properties != null && Boolean.parseBoolean(properties.getProperty("cancelRequested"));
    }

    private static void writeJobState(final Logger logger, final String jobId, final String provider, final String vmName,
            final String backupPath, final String backupType, final String state, final String details) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(JOB_ROOT);
            Properties properties = readJob(jobId, logger);
            if (properties == null) {
                properties = new Properties();
            }
            final String previousState = properties.getProperty("state");
            properties.setProperty("jobId", jobId);
            properties.setProperty("provider", safeValue(provider));
            properties.setProperty("vmName", safeValue(vmName));
            properties.setProperty("backupPath", safeValue(backupPath));
            properties.setProperty("backupType", safeValue(backupType));
            properties.setProperty("operation", AblestackBackupFrameworkUtils.resolveJobOperation(backupType));
            properties.setProperty("capabilities", resolveCapabilities(properties));
            properties.setProperty("state", state);
            properties.setProperty("step", state);
            properties.setProperty("progress", String.valueOf(resolveProgress(state, properties)));
            properties.setProperty("updated", String.valueOf(System.currentTimeMillis()));
            if (details != null) {
                properties.setProperty("details", details);
            }
            try (OutputStream outputStream = Files.newOutputStream(getJobPath(jobId), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(outputStream, "ABLESTACK backup job");
            }
            if (!state.equals(previousState)) {
                appendJobEvent(logger, jobId, provider, vmName, backupPath, backupType, state, details, properties);
            }
        } catch (IOException e) {
            logger.warn("Failed to write ABLESTACK backup job state for job [{}]", jobId, e);
        }
    }

    private static void appendJobEvent(final Logger logger, final String jobId, final String provider, final String vmName,
            final String backupPath, final String backupType, final String state, final String details, final Properties properties) {
        try {
            final Path jobDirectory = getJobDirectory(jobId);
            Files.createDirectories(jobDirectory);
            final long nextOffset = Long.parseLong(properties.getProperty("eventsOffset", "0")) + 1;
            properties.setProperty("eventsOffset", String.valueOf(nextOffset));
            storeJobProperties(logger, jobId, properties);
            final String event = "{"
                    + "\"offset\":" + nextOffset
                    + ",\"created\":\"" + jsonEscape(Instant.now().toString()) + "\""
                    + ",\"provider\":\"" + jsonEscape(provider) + "\""
                    + ",\"operation\":\"" + jsonEscape(properties.getProperty("operation", AblestackBackupFrameworkUtils.resolveJobOperation(backupType))) + "\""
                    + ",\"jobId\":\"" + jsonEscape(jobId) + "\""
                    + ",\"vm\":\"" + jsonEscape(vmName) + "\""
                    + ",\"backupPath\":\"" + jsonEscape(backupPath) + "\""
                    + ",\"backupType\":\"" + jsonEscape(backupType) + "\""
                    + ",\"state\":\"" + jsonEscape(state) + "\""
                    + ",\"step\":\"" + jsonEscape(properties.getProperty("step", state)) + "\""
                    + ",\"progress\":" + resolveProgress(state, properties)
                    + ",\"message\":\"" + jsonEscape(details != null ? details : state) + "\""
                    + "}\n";
            Files.writeString(jobDirectory.resolve(EVENTS_FILE), event, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (RuntimeException | IOException e) {
            logger.warn("Failed to append ABLESTACK backup job event for job [{}]", jobId, e);
        }
    }

    private static String readJobEventsJson(final String jobId, final Long eventsOffset, final Integer eventsLimit,
            final BackupAnswer answer) {
        if (jobId == null || jobId.isBlank()) {
            answer.setEventsOffset(eventsOffset != null && eventsOffset > 0 ? eventsOffset : 0);
            return "[]";
        }
        final Path eventsPath = getJobDirectory(jobId).resolve(EVENTS_FILE);
        final long offset = eventsOffset != null && eventsOffset > 0 ? eventsOffset : 0;
        final int limit = Math.min(MAX_EVENTS_LIMIT, Math.max(0, eventsLimit != null ? eventsLimit : DEFAULT_EVENTS_LIMIT));
        final List<String> selectedEvents = new ArrayList<>();
        long nextOffset = offset;

        if (limit > 0 && Files.exists(eventsPath)) {
            try {
                final List<String> lines = Files.readAllLines(eventsPath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    final String trimmedLine = line.trim();
                    if (!trimmedLine.startsWith("{") || !trimmedLine.endsWith("}")) {
                        continue;
                    }
                    final Long eventOffset = extractEventOffset(trimmedLine);
                    if (eventOffset == null || eventOffset <= offset) {
                        continue;
                    }
                    if (selectedEvents.size() >= limit) {
                        break;
                    }
                    selectedEvents.add(trimmedLine);
                    nextOffset = Math.max(nextOffset, eventOffset);
                }
            } catch (IOException ignored) {
                answer.setEventsOffset(offset);
                return "[]";
            }
        }

        answer.setEventsOffset(nextOffset);
        return "[" + String.join(",", selectedEvents) + "]";
    }

    private static Long extractEventOffset(final String eventJson) {
        final String marker = "\"offset\":";
        final int markerIndex = eventJson.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int cursor = markerIndex + marker.length();
        StringBuilder value = new StringBuilder();
        while (cursor < eventJson.length() && Character.isDigit(eventJson.charAt(cursor))) {
            value.append(eventJson.charAt(cursor));
            cursor++;
        }
        if (value.length() == 0) {
            return null;
        }
        return Long.parseLong(value.toString());
    }

    private static Integer readExitCode(final String jobId, final Logger logger) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        final Path exitCodePath = getJobDirectory(jobId).resolve(EXIT_CODE_FILE);
        if (!Files.exists(exitCodePath)) {
            return null;
        }
        try {
            return Integer.parseInt(Files.readString(exitCodePath).trim());
        } catch (IOException | NumberFormatException e) {
            logger.debug("Failed to read ABLESTACK backup exit code for job [{}]", jobId, e);
            return null;
        }
    }

    private static Properties refreshLiveProgress(final String jobId, final Properties properties, final String state, final Logger logger) {
        if (properties == null || !STATE_RUNNING.equals(state)) {
            return properties;
        }
        if (refreshRbdProgress(jobId, properties, logger)) {
            return properties;
        }
        final String vmName = properties.getProperty("vmName");
        if (safeValue(vmName).isBlank()) {
            return properties;
        }
        final Pair<Integer, String> jobInfo = executeAndCapture("virsh", "-c", "qemu:///system", "domjobinfo", vmName);
        if (jobInfo.first() != 0 || safeValue(jobInfo.second()).isBlank()) {
            return properties;
        }
        final Integer transferProgress = calculateTransferProgress(jobInfo.second());
        if (transferProgress == null) {
            return properties;
        }
        if (Boolean.parseBoolean(properties.getProperty("liveBandwidthSupported")) && !isRbdBackup(properties)) {
            properties.setProperty("liveBandwidthActive", Boolean.TRUE.toString());
            properties.setProperty("capabilities", resolveCapabilities(properties));
        }
        final int currentProgress = parseInteger(properties.getProperty("progress"), resolveProgress(state));
        final int progress = Math.max(currentProgress, transferProgress);
        properties.setProperty("progress", String.valueOf(progress));
        properties.setProperty("liveProgressUpdated", String.valueOf(System.currentTimeMillis()));
        storeJobProperties(logger, jobId, properties);
        return properties;
    }

    private static boolean refreshRbdProgress(final String jobId, final Properties properties, final Logger logger) {
        final Path rbdProgressPath = getJobDirectory(jobId).resolve(RBD_PROGRESS_FILE);
        if (!Files.exists(rbdProgressPath)) {
            return false;
        }

        try (InputStream inputStream = Files.newInputStream(rbdProgressPath)) {
            Properties rbdProgress = new Properties();
            rbdProgress.load(inputStream);
            final long processedBytes = parseLong(rbdProgress.getProperty("processedBytes"), 0L);
            final long totalBytes = parseLong(rbdProgress.getProperty("totalBytes"), 0L);
            final int diskIndex = Math.max(1, parseInteger(rbdProgress.getProperty("diskIndex"), 1));
            final int diskCount = Math.max(1, parseInteger(rbdProgress.getProperty("diskCount"), 1));
            final String step = rbdProgress.getProperty("step", "RBD_EXPORT");
            final int currentProgress = parseInteger(properties.getProperty("progress"), resolveProgress(STATE_RUNNING));
            properties.setProperty("step", step);
            if (totalBytes <= 0L) {
                properties.setProperty("progressUnavailable", Boolean.TRUE.toString());
                properties.setProperty("liveProgressUpdated", String.valueOf(System.currentTimeMillis()));
                storeJobProperties(logger, jobId, properties);
                return true;
            }
            int progress = currentProgress;
            final double diskFraction = Math.min(1D, Math.max(0D, processedBytes / (double) totalBytes));
            final double overallFraction = Math.min(1D, Math.max(0D, ((diskIndex - 1D) + diskFraction) / diskCount));
            final int range = LIVE_PROGRESS_MAXIMUM - LIVE_PROGRESS_MINIMUM;
            progress = Math.max(currentProgress, LIVE_PROGRESS_MINIMUM + (int) Math.round(overallFraction * range));
            properties.remove("progressUnavailable");
            properties.setProperty("progress", String.valueOf(Math.min(LIVE_PROGRESS_MAXIMUM, progress)));
            properties.setProperty("liveProgressUpdated", String.valueOf(System.currentTimeMillis()));
            storeJobProperties(logger, jobId, properties);
            return true;
        } catch (IOException e) {
            logger.debug("Failed to read RBD progress for ABLESTACK backup job [{}]", jobId, e);
            return false;
        }
    }

    private static Integer calculateTransferProgress(final String domJobInfo) {
        final Long processed = parseDomJobInfoSize(domJobInfo, "Data processed:", "File processed:");
        Long total = parseDomJobInfoSize(domJobInfo, "Data total:", "File total:");
        final Long remaining = parseDomJobInfoSize(domJobInfo, "Data remaining:", "File remaining:");
        if ((total == null || total <= 0L) && processed != null && remaining != null && remaining >= 0L) {
            total = processed + remaining;
        }
        if (processed == null || total == null || total <= 0L) {
            return null;
        }
        final double percent = Math.min(100D, Math.max(0D, processed.doubleValue() * 100D / total.doubleValue()));
        final int range = LIVE_PROGRESS_MAXIMUM - LIVE_PROGRESS_MINIMUM;
        return Math.max(LIVE_PROGRESS_MINIMUM, Math.min(LIVE_PROGRESS_MAXIMUM,
                LIVE_PROGRESS_MINIMUM + (int) Math.round(percent * range / 100D)));
    }

    private static int resolveRestoreStepProgress(final String step) {
        switch (safeValue(step)) {
            case "PREPARE_SOURCE":
                return 15;
            case "VALIDATE_CHAIN":
                return 30;
            case "RESTORE_DATA":
                return 45;
            case "ATTACH_VOLUME":
                return 85;
            case "CLEANUP_SOURCE":
                return 95;
            default:
                return resolveProgress(STATE_RUNNING);
        }
    }

    private static Long parseDomJobInfoSize(final String domJobInfo, final String... labels) {
        for (String line : domJobInfo.split("\\R")) {
            final String trimmedLine = line.trim();
            for (String label : labels) {
                if (trimmedLine.startsWith(label)) {
                    final String value = trimmedLine.substring(label.length()).trim();
                    return parseHumanReadableBytes(value);
                }
            }
        }
        return null;
    }

    private static Long parseHumanReadableBytes(final String value) {
        if (safeValue(value).isBlank()) {
            return null;
        }
        final String[] parts = value.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        try {
            final double amount = Double.parseDouble(parts[0]);
            final String unit = parts.length > 1 ? parts[1].toLowerCase() : "b";
            final double multiplier;
            if (unit.startsWith("ki") || unit.equals("kib")) {
                multiplier = 1024D;
            } else if (unit.startsWith("mi") || unit.equals("mib")) {
                multiplier = 1024D * 1024D;
            } else if (unit.startsWith("gi") || unit.equals("gib")) {
                multiplier = 1024D * 1024D * 1024D;
            } else if (unit.startsWith("ti") || unit.equals("tib")) {
                multiplier = 1024D * 1024D * 1024D * 1024D;
            } else {
                multiplier = 1D;
            }
            return Math.max(0L, Math.round(amount * multiplier));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int resolveProgress(final String state, final Properties properties) {
        if (STATE_COMPLETED.equals(state) || STATE_CANCELED.equals(state)) {
            return 100;
        }
        if (STATE_RUNNING.equals(state)) {
            return parseInteger(properties != null ? properties.getProperty("progress") : null, 50);
        }
        if (STATE_STARTED.equals(state)) {
            return 5;
        }
        return 0;
    }

    private static int resolveProgress(final String state) {
        return resolveProgress(state, null);
    }

    private static String resolveCapabilities(final Properties properties) {
        final List<String> capabilities = new ArrayList<>();
        capabilities.add(AblestackBackupFrameworkUtils.CAPABILITY_CANCEL);
        capabilities.add(AblestackBackupFrameworkUtils.CAPABILITY_EVENTS);
        capabilities.add(AblestackBackupFrameworkUtils.CAPABILITY_LOG);
        capabilities.add(AblestackBackupFrameworkUtils.CAPABILITY_PROGRESS);
        if (AblestackBackupFrameworkUtils.OPERATION_RESTORE.equals(properties.getProperty("operation"))) {
            capabilities.add(AblestackBackupFrameworkUtils.CAPABILITY_RESTORE_PROGRESS);
        } else if (Boolean.parseBoolean(properties.getProperty("liveBandwidthSupported"))
                && Boolean.parseBoolean(properties.getProperty("liveBandwidthActive")) && !isRbdBackup(properties)) {
            capabilities.add(AblestackBackupFrameworkUtils.CAPABILITY_LIVE_BANDWIDTH);
        }
        return String.join(",", capabilities);
    }

    private static Properties ensureCommonJobMetadata(final String jobId, final Properties properties, final Logger logger) {
        if (properties == null) {
            return null;
        }
        boolean changed = false;
        if (safeValue(properties.getProperty("jobId")).isBlank() && !safeValue(jobId).isBlank()) {
            properties.setProperty("jobId", jobId);
            changed = true;
        }
        if (safeValue(properties.getProperty("operation")).isBlank()) {
            properties.setProperty("operation", AblestackBackupFrameworkUtils.resolveJobOperation(properties.getProperty("backupType")));
            changed = true;
        }
        if (safeValue(properties.getProperty("capabilities")).isBlank()) {
            properties.setProperty("capabilities", resolveCapabilities(properties));
            changed = true;
        }
        if (changed) {
            storeJobProperties(logger, properties.getProperty("jobId"), properties);
        }
        return properties;
    }

    private static int parseInteger(final String value, final int defaultValue) {
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Integer parseNullableInteger(final String value) {
        try {
            return value != null ? Integer.valueOf(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(final String value, final long defaultValue) {
        try {
            return value != null ? Long.parseLong(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean isRbdBackup(final Properties properties) {
        return Files.exists(getJobDirectory(properties.getProperty("jobId")).resolve(RBD_PROGRESS_FILE));
    }

    private static String buildBlockJobBandwidthCommand(final String vmName, final int virshLimitMiBps) {
        return "set -o pipefail; rc=0; active=0; info=''; "
                + "while read -r disk; do "
                + "[ -z \"$disk\" ] && continue; "
                + "info=$(virsh -c qemu:///system blockjob " + shellQuote(vmName) + " \"$disk\" --info 2>&1) || continue; "
                + "[ -z \"$info\" ] && continue; "
                + "case \"$info\" in *'No current block job'*|*'does not have an active block job'*) continue;; esac; "
                + "active=$((active + 1)); "
                + "virsh -c qemu:///system blockjob " + shellQuote(vmName) + " \"$disk\" --bandwidth " + virshLimitMiBps + " || rc=$?; "
                + "done < <(virsh -c qemu:///system domblklist " + shellQuote(vmName) + " --details 2>/dev/null | awk '$2 == \"disk\" {print $3}'); "
                + "if [ \"$active\" -eq 0 ]; then echo 'No active backup block jobs were found' >&2; exit 1; fi; "
                + "exit $rc";
    }

    private static void storeJobProperties(final Logger logger, final String jobId, final Properties properties) {
        try (OutputStream outputStream = Files.newOutputStream(getJobPath(jobId), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(outputStream, "ABLESTACK backup job");
        } catch (IOException e) {
            logger.warn("Failed to store ABLESTACK backup job properties for job [{}]", jobId, e);
        }
    }

    private static Properties readJob(final String jobId, final Logger logger) {
        if (jobId == null || jobId.isBlank() || !Files.exists(getJobPath(jobId))) {
            return null;
        }
        try (InputStream inputStream = Files.newInputStream(getJobPath(jobId))) {
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties;
        } catch (IOException e) {
            logger.warn("Failed to read ABLESTACK backup job state for job [{}]", jobId, e);
            return null;
        }
    }

    private static Path getJobPath(final String jobId) {
        return JOB_ROOT.resolve(jobId.replaceAll("[^A-Za-z0-9_.-]", "_") + ".properties");
    }

    private static Path getJobDirectory(final String jobId) {
        return JOB_ROOT.resolve(jobId.replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    private static Path getDetachedScriptPath(final String jobId) {
        return getJobDirectory(jobId).resolve(SCRIPT_FILE);
    }

    private static void writeDetachedScript(final String jobId, final String provider, final String vmName, final String backupPath,
            final String backupType, final String[] scriptCommand) throws IOException {
        final Path jobDirectory = getJobDirectory(jobId);
        final Path scriptPath = getDetachedScriptPath(jobId);
        final Path logPath = jobDirectory.resolve(LOG_FILE);
        final Path exitCodePath = jobDirectory.resolve(EXIT_CODE_FILE);
        Files.deleteIfExists(exitCodePath);
        String script = "#!/bin/bash\n"
                + "set +e\n"
                + "export ABLESTACK_BACKUP_JOB_ID=" + shellQuote(jobId) + "\n"
                + "export ABLESTACK_BACKUP_JOB_DIR=" + shellQuote(jobDirectory.toString()) + "\n"
                + "export ABLESTACK_BACKUP_OPERATION=" + shellQuote(AblestackBackupFrameworkUtils.resolveJobOperation(backupType)) + "\n"
                + "echo \"" + safeForLog(AblestackBackupFrameworkUtils.buildTracePrefix(provider, AblestackBackupFrameworkUtils.resolveJobOperation(backupType)))
                + " phase=[SCRIPT_STARTED] jobId=" + safeForLog(jobId) + " vm=" + safeForLog(vmName)
                + " backupPath=" + safeForLog(backupPath) + " backupType=" + safeForLog(backupType) + " created=" + Instant.now() + "\" >> "
                + shellQuote(logPath.toString()) + "\n"
                + "echo \"" + safeForLog(AblestackBackupFrameworkUtils.buildTracePrefix(provider, AblestackBackupFrameworkUtils.resolveJobOperation(backupType)))
                + " phase=[SCRIPT_COMMAND] jobId=" + safeForLog(jobId) + " command=" + safeForLog(formatCommand(scriptCommand)) + "\" >> "
                + shellQuote(logPath.toString()) + "\n"
                + formatCommand(scriptCommand) + " >> " + shellQuote(logPath.toString()) + " 2>&1\n"
                + "rc=$?\n"
                + "echo \"$rc\" > " + shellQuote(exitCodePath.toString()) + "\n"
                + "echo \"" + safeForLog(AblestackBackupFrameworkUtils.buildTracePrefix(provider, AblestackBackupFrameworkUtils.resolveJobOperation(backupType)))
                + " phase=[SCRIPT_EXITED] jobId=" + safeForLog(jobId) + " rc=$rc finished=$(date --iso-8601=seconds)\" >> "
                + shellQuote(logPath.toString()) + "\n"
                + "exit $rc\n";
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("rwx------"));
    }

    private static Pair<Integer, String> launchDetached(final String jobId, final String unitName) {
        final Path scriptPath = getDetachedScriptPath(jobId);
        return executeAndCapture("systemd-run", "--unit", unitName, "--collect", "--quiet", "/bin/bash", scriptPath.toString());
    }

    private static boolean isSystemdUnitActive(final String unitName, final Logger logger) {
        Pair<Integer, String> result = executeAndCapture("systemctl", "is-active", "--quiet", unitName);
        if (result.first() == 0) {
            return true;
        }
        logger.debug("ABLESTACK detached backup unit [{}] is not active: {}", unitName, result.second());
        return false;
    }

    private static String getTracePrefix(final Properties properties) {
        if (properties == null) {
            return AblestackBackupFrameworkUtils.buildTracePrefix(null, null);
        }
        return AblestackBackupFrameworkUtils.buildTracePrefix(properties.getProperty("provider"), properties.getProperty("operation"));
    }

    private static Pair<Integer, String> executeAndCapture(final String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            return new Pair<>(exitCode, new String(output, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return new Pair<>(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Pair<>(-1, e.getMessage());
        }
    }

    private static String getSystemdUnitName(final String jobId) {
        return "ablestack-backup-" + jobId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String formatCommand(final String[] command) {
        return Arrays.stream(command).map(LibvirtAblestackAsyncBackupRunner::shellQuote).reduce((left, right) -> left + " " + right).orElse("");
    }

    private static String shellQuote(final String value) {
        return "'" + safeValue(value).replace("'", "'\"'\"'") + "'";
    }

    private static String safeValue(final String value) {
        return value == null ? "" : value;
    }

    private static String safeForLog(final String value) {
        return safeValue(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonEscape(final String value) {
        return safeValue(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
