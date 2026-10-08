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
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Opt-in diagnostics for NAS QCOW2 incremental file restores; never modifies a backup artifact. */
final class LibvirtAblestackNasRestoreDiagnostics {
    static final Path SETTINGS_PATH = Paths.get("/etc/cloudstack/agent/ablestack-nas-restore-test.properties");
    private static final String RESTORE_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("ablestack-nas",
            AblestackBackupFrameworkUtils.OPERATION_RESTORE);
    private final String sparseMode;
    private final boolean failOnCompare;
    private final String caseId;
    private final String diskOffering;
    private final JsonArray sourceChecks = new JsonArray();
    private JsonObject sourceInfo;
    private long convertStart;
    private long convertStartNanos;
    private long convertElapsedMillis;

    private LibvirtAblestackNasRestoreDiagnostics(final Properties settings) {
        sparseMode = settings.getProperty("sparse.mode", "FULL_ALLOCATED").trim().toUpperCase(Locale.ROOT);
        if (!"FULL_ALLOCATED".equals(sparseMode) && !"SPARSE".equals(sparseMode)) {
            throw new IllegalArgumentException("sparse.mode must be FULL_ALLOCATED or SPARSE");
        }
        failOnCompare = booleanSetting(settings, "fail.on.compare", true);
        caseId = clean(settings.getProperty("case.id", "UNSPECIFIED"));
        // A test operator label, not an inferred offering or proof of filesystem allocation.
        diskOffering = clean(settings.getProperty("source.disk.offering", "UNSPECIFIED"));
    }

    static LibvirtAblestackNasRestoreDiagnostics load(final Logger logger) {
        return load(SETTINGS_PATH, logger);
    }

    static LibvirtAblestackNasRestoreDiagnostics load(final Path path, final Logger logger) {
        if (Files.notExists(path)) {
            return null;
        }
        final Properties settings = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            settings.load(stream);
            return fromProperties(settings);
        } catch (IOException | IllegalArgumentException e) {
            logger.error("{} phase=[QCOW2_TEST_SETTINGS_FAILED], settingsPath=[{}], error=[{}]", RESTORE_TRACE, path, e.getMessage(), e);
            throw new CloudRuntimeException("Invalid NAS restore test settings: " + path, e);
        }
    }

    static LibvirtAblestackNasRestoreDiagnostics fromProperties(final Properties settings) {
        return booleanSetting(settings, "enabled", false) ? new LibvirtAblestackNasRestoreDiagnostics(settings) : null;
    }

    private static boolean booleanSetting(final Properties settings, final String key, final boolean defaultValue) {
        final String value = settings.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    String getSparseSize() {
        return "SPARSE".equals(sparseMode) ? "4k" : "0";
    }

    void logSkipped(final String trace, final Logger logger, final String source, final String target) {
        logger.info("{} phase=[QCOW2_TEST_SKIPPED], testCase=[{}], source=[{}], target=[{}], reason=[NOT_INCREMENTAL_QCOW2_FILE_RESTORE]",
                trace, caseId, source, target);
    }

    void inspectSource(final String trace, final Logger logger, final String leaf, final List<String> backupPaths, final int timeout) {
        final String testTrace = trace;
        final Pair<Integer, String> version = run("qemu-img --version", timeout);
        logger.info("{} phase=[QCOW2_TEST_ENVIRONMENT], testCase=[{}], sourceDiskOffering=[{}], sparseMode=[{}], sparseSize=[{}], qemuVersion=[{}], exitCode=[{}], failOnCompare=[{}]",
                testTrace, caseId, diskOffering, sparseMode, getSparseSize(), clean(version.second()), version.first(), failOnCompare);
        final Pair<Integer, String> result = run("qemu-img info --backing-chain --output=json " + quote(leaf), timeout);
        logger.info("{} phase=[QCOW2_CHAIN_SOURCE], leaf=[{}], backupPaths=[{}], chainOrder=[LEAF_TO_BASE], exitCode=[{}], chain=[{}]",
                testTrace, leaf, backupPaths, result.first(), clean(result.second()));
        final Set<String> checkFiles = new LinkedHashSet<>();
        if (result.first() == 0) {
            try {
                final JsonArray chain = JsonParser.parseString(result.second()).getAsJsonArray();
                for (int index = 0; index < chain.size(); index++) {
                    final JsonObject image = chain.get(index).getAsJsonObject();
                    if (index == 0) {
                        sourceInfo = image;
                    }
                    logger.info("{} phase=[QCOW2_CHAIN_IMAGE], index=[{}], info=[{}]", testTrace, index, image);
                    if ("qcow2".equals(value(image, "format"))) {
                        final String filename = value(image, "filename");
                        if (filename != null) {
                            checkFiles.add(filename);
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("{} phase=[QCOW2_CHAIN_PARSE_FAILED], error=[{}]", testTrace, e.getMessage());
            }
        }
        // Retain diagnostics even when info cannot enumerate the chain. Never rebase or repair sources.
        checkFiles.add(leaf);
        for (String backupPath : backupPaths) {
            if (backupPath.endsWith(".qcow2")) {
                checkFiles.add(backupPath);
            }
        }
        for (String filename : checkFiles) {
            final Pair<Integer, String> check = run("qemu-img check -f qcow2 " + quote(filename), timeout);
            final JsonObject entry = new JsonObject();
            entry.addProperty("file", filename);
            entry.addProperty("exitCode", check.first());
            sourceChecks.add(entry);
            logger.info("{} phase=[QCOW2_CHECK_SOURCE], source=[{}], exitCode=[{}], output=[{}], policy=[LOG_ONLY]",
                    testTrace, filename, check.first(), clean(check.second()));
        }
    }

    void convertBegin(final String trace, final Logger logger, final String source, final String target) {
        convertStart = System.currentTimeMillis();
        convertStartNanos = System.nanoTime();
        logger.info("{} phase=[QCOW2_CONVERT_BEGIN], testCase=[{}], sparseMode=[{}], sparseSize=[{}], source=[{}], temporaryTarget=[{}], convertStart=[{}]",
                trace, caseId, sparseMode, getSparseSize(), source, target, convertStart);
    }

    void convertEnd(final String trace, final Logger logger, final String source, final String target, final int exitCode) {
        convertElapsedMillis = elapsed(convertStartNanos);
        logger.info("{} phase=[QCOW2_CONVERT_END], testCase=[{}], sparseMode=[{}], sparseSize=[{}], source=[{}], temporaryTarget=[{}], convertStart=[{}], convertEnd=[{}], elapsedMillis=[{}], exitCode=[{}]",
                trace, caseId, sparseMode, getSparseSize(), source, target, convertStart, System.currentTimeMillis(), convertElapsedMillis, exitCode);
    }

    void verifyTarget(final String trace, final Logger logger, final String source, final String target, final int timeout) throws QemuImgException {
        final String testTrace = trace;
        final Pair<Integer, String> info = run("qemu-img info --output=json " + quote(target), timeout);
        logger.info("{} phase=[QCOW2_INFO_TARGET], target=[{}], exitCode=[{}], info=[{}]", testTrace, target, info.first(), clean(info.second()));
        JsonObject targetInfo = null;
        if (info.first() == 0) {
            try {
                targetInfo = JsonParser.parseString(info.second()).getAsJsonObject();
            } catch (RuntimeException e) {
                logger.warn("{} phase=[QCOW2_TARGET_PARSE_FAILED], error=[{}]", testTrace, e.getMessage());
            }
        }
        final Pair<Integer, String> check = run("qemu-img check -f qcow2 " + quote(target), timeout);
        logger.info("{} phase=[QCOW2_CHECK_TARGET], target=[{}], exitCode=[{}], output=[{}]", testTrace, target, check.first(), clean(check.second()));
        final Pair<Integer, String> stat = run("stat -c '%s %b %B' " + quote(target), timeout);
        Long apparentBytes = null;
        Long allocatedBytes = null;
        if (stat.first() == 0) {
            try {
                final String[] fields = stat.second().trim().split("\\s+");
                apparentBytes = Long.parseLong(fields[0]);
                allocatedBytes = Math.multiplyExact(Long.parseLong(fields[1]), Long.parseLong(fields[2]));
            } catch (RuntimeException e) {
                logger.warn("{} phase=[QCOW2_STAT_PARSE_FAILED], error=[{}]", testTrace, e.getMessage());
            }
        }
        final Long targetVirtualSize = longValue(targetInfo, "virtual-size");
        final String allocationRatio = allocatedBytes != null && targetVirtualSize != null && targetVirtualSize > 0
                ? String.format(Locale.ROOT, "%.2f", allocatedBytes.doubleValue() / targetVirtualSize * 100.0) : "UNKNOWN";
        logger.info("{} phase=[QCOW2_ALLOCATION_TARGET], target=[{}], virtualSizeBytes=[{}], apparentSizeBytes=[{}], allocatedBytes=[{}], allocationRatio=[{}], exitCode=[{}], output=[{}]",
                testTrace, target, targetVirtualSize, apparentBytes, allocatedBytes, allocationRatio, stat.first(), clean(stat.second()));
        logger.info("{} phase=[QCOW2_COMPARE_BEGIN], source=[{}], target=[{}]", testTrace, source, target);
        final long compareStart = System.nanoTime();
        final Pair<Integer, String> compare = run("qemu-img compare -f qcow2 -F qcow2 " + quote(source) + " " + quote(target), timeout);
        final long compareElapsedMillis = elapsed(compareStart);
        logger.info("{} phase=[QCOW2_COMPARE], source=[{}], target=[{}], exitCode=[{}], result=[{}], elapsedMillis=[{}], output=[{}]",
                testTrace, source, target, compare.first(), compareResult(compare.first()), compareElapsedMillis, clean(compare.second()));
        final boolean backingRemaining = value(targetInfo, "backing-filename") != null || value(targetInfo, "full-backing-filename") != null;
        logger.info("{} phase=[QCOW2_TEST_SUMMARY], testCase=[{}], sourceDiskOffering=[{}], sparseMode=[{}], sparseSize=[{}], source=[{}], temporaryTarget=[{}], sourceVirtualSize=[{}], sourceActualSize=[{}], targetVirtualSize=[{}], targetActualSize=[{}], apparentSizeBytes=[{}], allocatedBytes=[{}], allocationRatio=[{}], sourceChecks=[{}], targetCheckExitCode=[{}], compareExitCode=[{}], convertElapsedMillis=[{}], compareElapsedMillis=[{}], backingFileRemaining=[{}]",
                testTrace, caseId, diskOffering, sparseMode, getSparseSize(), source, target, longValue(sourceInfo, "virtual-size"), longValue(sourceInfo, "actual-size"), targetVirtualSize,
                longValue(targetInfo, "actual-size"), apparentBytes, allocatedBytes, allocationRatio, sourceChecks, check.first(), compare.first(),
                convertElapsedMillis, compareElapsedMillis, targetInfo == null ? "UNKNOWN" : backingRemaining);
        if (failOnCompare && compare.first() != 0) {
            throw new QemuImgException("NAS QCOW2 test compare failed: exitCode=" + compare.first() + ", result=" + compareResult(compare.first()));
        }
        // compare without -s permits trailing zeros; independently require the exact virtual size and an independent image.
        if (sourceInfo == null || targetInfo == null || targetVirtualSize == null || !targetVirtualSize.equals(longValue(sourceInfo, "virtual-size"))
                || !"qcow2".equals(value(targetInfo, "format")) || backingRemaining || check.first() != 0 || allocatedBytes == null) {
            throw new QemuImgException("NAS QCOW2 test target validation failed; see QCOW2_TEST_SUMMARY");
        }
    }

    static String compareResult(final int exitCode) {
        switch (exitCode) {
            case 0: return "IDENTICAL";
            case 1: return "DIFFERENT";
            case 2: return "IMAGE_OPEN_ERROR";
            case 3: return "SECTOR_ALLOCATION_ERROR";
            case 4: return "READ_ERROR";
            default: return "COMMAND_ERROR_OR_TIMEOUT";
        }
    }

    private static Pair<Integer, String> run(final String command, final int timeout) {
        try {
            return LibvirtAblestackFileRestoreHelper.runCommandWithOutput(command, timeout * 1000);
        } catch (CloudRuntimeException e) {
            return new Pair<>(-1, e.getMessage());
        }
    }

    private static String value(final JsonObject object, final String key) {
        final JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Long longValue(final JsonObject object, final String key) {
        try {
            final String value = value(object, key);
            return value == null ? null : Long.valueOf(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long elapsed(final long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String clean(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
