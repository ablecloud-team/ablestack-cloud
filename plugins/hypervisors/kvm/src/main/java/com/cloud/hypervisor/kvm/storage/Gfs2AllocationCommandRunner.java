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
package com.cloud.hypervisor.kvm.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.cloud.utils.exception.CloudRuntimeException;

/** Preserve command exit/output without competing readers, bounded by the allocation deadline. */
final class Gfs2AllocationCommandRunner {
    private Gfs2AllocationCommandRunner() { }

    static final class CommandFailure extends CloudRuntimeException {
        private final int exitCode;
        CommandFailure(int exitCode, String output) {
            super("GFS2_ALLOCATION_COMMAND_FAILED: exit=" + exitCode + " " + output);
            this.exitCode = exitCode;
        }
        int exitCode() { return exitCode; }
    }

    static String run(List<String> arguments, long timeout) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("cloudstack-allocation-command-", ".out");
            process = new ProcessBuilder(List.copyOf(arguments)).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectOutput(output.toFile()).start();
            if (!process.waitFor(Math.max(1, timeout), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new CloudRuntimeException("GFS2_ALLOCATION_TIMEOUT: " + arguments.get(0));
            }
            if (Files.size(output) > 1_048_576) {
                throw new CloudRuntimeException("GFS2_ALLOCATION_COMMAND_FAILED: command output exceeds 1 MiB");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new CommandFailure(process.exitValue(), text.trim());
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException("GFS2_ALLOCATION_INTERRUPTED", e);
        } catch (IOException e) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_COMMAND_FAILED: " + e.getMessage(), e);
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            if (output != null) {
                try { Files.deleteIfExists(output); }
                catch (IOException e) { throw new CloudRuntimeException("GFS2_ALLOCATION_COMMAND_CLEANUP_FAILED: " + output, e); }
            }
        }
    }
}
