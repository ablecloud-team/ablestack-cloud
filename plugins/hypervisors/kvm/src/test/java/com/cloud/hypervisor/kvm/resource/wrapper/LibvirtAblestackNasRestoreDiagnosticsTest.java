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

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackNasRestoreDiagnosticsTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final Logger logger = Mockito.mock(Logger.class);

    private Properties settings(final String mode) {
        final Properties settings = new Properties();
        settings.setProperty("enabled", "true");
        settings.setProperty("sparse.mode", mode);
        settings.setProperty("case.id", "THIN-INC3-" + mode);
        settings.setProperty("source.disk.offering", "THIN");
        return settings;
    }

    @Test
    public void settingsAreDisabledUnlessExplicitlyEnabled() throws Exception {
        assertNull(LibvirtAblestackNasRestoreDiagnostics.fromProperties(new Properties()));
        assertNull(LibvirtAblestackNasRestoreDiagnostics.load(folder.getRoot().toPath().resolve("absent"), logger));
        final Properties disabled = settings("invalid-mode");
        disabled.setProperty("enabled", "false");
        assertNull(LibvirtAblestackNasRestoreDiagnostics.fromProperties(disabled));
        assertEquals("0", LibvirtAblestackNasRestoreDiagnostics.fromProperties(settings("FULL_ALLOCATED")).getSparseSize());
        assertEquals("4k", LibvirtAblestackNasRestoreDiagnostics.fromProperties(settings("SPARSE")).getSparseSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidModeCannotSilentlySelectAnotherExperiment() {
        LibvirtAblestackNasRestoreDiagnostics.fromProperties(settings("invalid-mode"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void misspelledComparePolicyCannotDisableTheGate() {
        final Properties settings = settings("SPARSE");
        settings.setProperty("fail.on.compare", "treu");
        LibvirtAblestackNasRestoreDiagnostics.fromProperties(settings);
    }

    @Test
    public void enabledSettingsAreReadAgainForTheNextRestore() throws Exception {
        final Path path = folder.getRoot().toPath().resolve("test.properties");
        Files.writeString(path, "enabled=true\nsparse.mode=SPARSE\n");
        assertEquals("4k", LibvirtAblestackNasRestoreDiagnostics.load(path, logger).getSparseSize());
        Files.writeString(path, "enabled=true\nsparse.mode=FULL_ALLOCATED\n");
        assertEquals("0", LibvirtAblestackNasRestoreDiagnostics.load(path, logger).getSparseSize());
    }

    @Test(expected = CloudRuntimeException.class)
    public void invalidEnabledFileFailsBeforeEnteringTheRestore() throws Exception {
        final Path path = folder.getRoot().toPath().resolve("invalid-test.properties");
        Files.writeString(path, "enabled=true\nsparse.mode=invalid\n");
        LibvirtAblestackNasRestoreDiagnostics.load(path, logger);
    }

    @Test
    public void bothModesCompareBeforePromotingAndNeverRewriteTheSourceChain() throws Exception {
        final List<String> full = restore("FULL_ALLOCATED", 0, 0, 0, false, false, false, true);
        final List<String> sparse = restore("SPARSE", 0, 0, 0, false, false, false, true);
        assertTrue(full.stream().anyMatch(command -> command.contains("convert -p -S 0 -f qcow2 -O qcow2")));
        assertTrue(sparse.stream().anyMatch(command -> command.contains("convert -p -S 4k -f qcow2 -O qcow2")));
        assertTrue(sparse.stream().noneMatch(command -> command.contains("rebase") || command.contains("commit")
                || command.contains("rsync") || command.contains("--target-is-zero") || command.contains("--image-opts")
                || command.contains("preallocation") || command.contains("compare -s") || command.contains("check -r")));
    }

    @Test
    public void everyCompareFailureRestoresTheOriginalVolume() throws Exception {
        for (int exitCode : new int[]{1, 2, 3, 4, -1}) {
            restore("SPARSE", exitCode, 0, 0, false, false, false, false);
        }
    }

    @Test
    public void targetStructureErrorAndRemainingBackingAreRejectedEvenIfCompareMatches() throws Exception {
        restore("SPARSE", 0, 0, 2, false, false, false, false);
        restore("SPARSE", 0, 0, 0, true, false, false, false);
        restore("SPARSE", 0, 0, 0, false, true, false, false);
    }

    @Test
    public void sourceCheckFailureIsObservationalAndDoesNotSkipCompare() throws Exception {
        restore("SPARSE", 0, 2, 0, false, false, false, true);
    }

    @Test
    public void timedOutConvertRollsBackInsteadOfDeletingTheOriginal() throws Exception {
        restore("SPARSE", 0, 0, 0, false, false, true, false);
    }

    @Test
    public void disabledDiagnosticsKeepTheCurrentConvertAndSkipAllTestCommands() throws Exception {
        final List<String> commands = restore(null, 0, 0, 0, false, false, false, true);
        assertTrue(commands.stream().anyMatch(command -> command.contains("convert -p -S 0 -f qcow2 -O qcow2")));
        assertFalse(commands.stream().anyMatch(command -> command.contains("compare") || command.contains("check -f")
                || command.contains("--backing-chain") || command.contains("stat -c")));
    }

    @Test
    public void independentFullBackupKeepsRsyncEvenWhenSparseTestIsEnabled() throws Exception {
        final Path directory = folder.newFolder().toPath();
        final Path source = Files.writeString(directory.resolve("FULL.qcow2"), "full");
        final Path target = Files.writeString(directory.resolve("volume"), "original");
        final List<String> commands = new ArrayList<>();
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenReturn(Map.of("file_format", "qcow2", QemuImg.VIRTUAL_SIZE, "1048576")));
             MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScriptWithFullResult(Mockito.anyString(), Mockito.anyInt())).thenAnswer(invocation -> {
                final String command = invocation.getArgument(0);
                commands.add(command);
                if (command.contains("grep")) {
                    return result("", 1);
                }
                assertTrue(command.contains("rsync -az"));
                final Matcher matcher = Pattern.compile("'([^']+\\.qcow2)' 2>&1;").matcher(command);
                assertTrue(matcher.find());
                Files.writeString(Path.of(matcher.group(1)), "restored-full");
                return result("ok", 0);
            });
            assertTrue(LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup("RESTORE_TRACE", logger, target.toString(),
                    List.of(source.toString()), 37, "cs-nas-restore-volume-",
                    LibvirtAblestackNasRestoreDiagnostics.fromProperties(settings("SPARSE"))));
        }
        assertEquals("restored-full", Files.readString(target));
        assertFalse(commands.stream().anyMatch(command -> command.contains("convert") || command.contains("compare") || command.contains("check -f")));
    }

    @Test
    public void compareExitCodesRemainDistinct() {
        assertEquals("IDENTICAL", LibvirtAblestackNasRestoreDiagnostics.compareResult(0));
        assertEquals("DIFFERENT", LibvirtAblestackNasRestoreDiagnostics.compareResult(1));
        assertEquals("IMAGE_OPEN_ERROR", LibvirtAblestackNasRestoreDiagnostics.compareResult(2));
        assertEquals("SECTOR_ALLOCATION_ERROR", LibvirtAblestackNasRestoreDiagnostics.compareResult(3));
        assertEquals("READ_ERROR", LibvirtAblestackNasRestoreDiagnostics.compareResult(4));
        assertEquals("COMMAND_ERROR_OR_TIMEOUT", LibvirtAblestackNasRestoreDiagnostics.compareResult(-1));
    }

    private List<String> restore(final String mode, final int compareExit, final int sourceCheckExit, final int targetCheckExit,
            final boolean backingRemaining, final boolean wrongSize, final boolean convertTimeout, final boolean expectedSuccess) throws Exception {
        final Path directory = folder.newFolder().toPath();
        final Path base = Files.writeString(directory.resolve("FULL.qcow2"), "base");
        final Path leaf = Files.writeString(directory.resolve("INC3 '$(literal).qcow2"), "leaf");
        final Path target = Files.writeString(directory.resolve("volume"), "original");
        final List<String> commands = new ArrayList<>();
        final JsonArray chain = new JsonArray();
        final JsonObject leafInfo = image(leaf.toString(), 1048576L);
        leafInfo.addProperty("backing-filename", base.toString());
        chain.add(leafInfo);
        chain.add(image(base.toString(), 1048576L));
        final JsonObject targetInfo = image("temporary", wrongSize ? 2097152L : 1048576L);
        if (backingRemaining) {
            targetInfo.addProperty("backing-filename", base.toString());
        }
        final LibvirtAblestackNasRestoreDiagnostics diagnostics = mode == null ? null
                : LibvirtAblestackNasRestoreDiagnostics.fromProperties(settings(mode));
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenReturn(Map.of("file_format", "qcow2", QemuImg.VIRTUAL_SIZE, "1048576")));
             MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScriptWithFullResult(Mockito.anyString(), Mockito.anyInt())).thenAnswer(invocation -> {
                final String command = invocation.getArgument(0);
                commands.add(command);
                if (command.contains("qemu-img convert")) {
                    if (convertTimeout) {
                        throw new CloudRuntimeException("simulated command timeout");
                    }
                    final Matcher matcher = Pattern.compile("'([^']+\\.qcow2)' 2>&1;").matcher(command);
                    assertTrue("Conversion destination must be quoted", matcher.find());
                    Files.writeString(Path.of(matcher.group(1)), "restored");
                }
                if (command.contains("--backing-chain")) {
                    return result(chain.toString(), 0);
                }
                if (command.contains("qemu-img info") && !command.contains("grep")) {
                    return result(targetInfo.toString(), 0);
                }
                if (command.contains("check -f qcow2")) {
                    return result("check result", command.contains("cs-nas-restore-volume-") ? targetCheckExit : sourceCheckExit);
                }
                if (command.contains("stat -c")) {
                    return result("1049000 1024 512", 0);
                }
                if (command.contains("qemu-img compare")) {
                    assertFalse("The temporary image must not be promoted before compare", Files.exists(target));
                    try (Stream<Path> entries = Files.list(directory)) {
                        final Path original = entries.filter(path -> path.toString().endsWith(".bak")).findFirst().orElseThrow();
                        assertEquals("original", Files.readString(original));
                    }
                    assertTrue("Source path must be shell quoted literally", command.contains("'\"'\"'"));
                    return compareExit < 0 ? null : result("compare result", compareExit);
                }
                return result("ok", 0);
            });
            final boolean success = LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup("RESTORE_TRACE", logger, target.toString(),
                    List.of(base.toString(), leaf.toString()), 37, "cs-nas-restore-volume-", diagnostics);
            assertEquals(expectedSuccess, success);
        }
        assertEquals(expectedSuccess ? "restored" : "original", Files.readString(target));
        assertEquals("base", Files.readString(base));
        assertEquals("leaf", Files.readString(leaf));
        try (Stream<Path> entries = Files.list(directory)) {
            assertFalse("Temporary outputs and moved-aside originals must be cleaned up", entries.anyMatch(path ->
                    path.toString().contains("cs-nas-restore-volume-") || path.toString().endsWith(".bak")));
        }
        if (mode != null && !convertTimeout) {
            final int compareIndex = indexOf(commands, "qemu-img compare");
            assertTrue(compareIndex > indexOf(commands, "qemu-img convert"));
            assertTrue(compareIndex > indexOf(commands, "check -f qcow2 '" + directory + "/cs-nas-restore-volume-"));
            assertTrue(compareIndex > indexOf(commands, "stat -c"));
            assertTrue(Mockito.mockingDetails(logger).getInvocations().stream().anyMatch(invocation ->
                    invocation.getArguments()[0].toString().contains("phase=[QCOW2_TEST_SUMMARY]")));
            for (org.mockito.invocation.Invocation invocation : Mockito.mockingDetails(logger).getInvocations()) {
                if (invocation.getArguments().length > 0 && invocation.getArguments()[0].toString().contains("QCOW2_")) {
                    assertTrue("Keep the existing trace/phase log format", invocation.getArguments()[0].toString().startsWith("{} phase=["));
                }
            }
        }
        return commands;
    }

    private static int indexOf(final List<String> commands, final String fragment) {
        for (int index = 0; index < commands.size(); index++) {
            if (commands.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static JsonObject image(final String filename, final long size) {
        final JsonObject info = new JsonObject();
        info.addProperty("filename", filename);
        info.addProperty("format", "qcow2");
        info.addProperty("virtual-size", size);
        info.addProperty("actual-size", 524288);
        info.addProperty("cluster-size", 65536);
        return info;
    }

    private static String result(final String output, final int exitCode) {
        return output + "\n__CMD_EXIT__=" + exitCode;
    }
}
