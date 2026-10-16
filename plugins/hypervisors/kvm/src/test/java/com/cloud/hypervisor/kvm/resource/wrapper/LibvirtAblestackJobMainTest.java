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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackJobMainTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void detachedWorkerInitializesAgentFileLoggingInFreshJvm() throws Exception {
        final Path directory = temporaryFolder.getRoot().toPath();
        final Path agentLog = directory.resolve("agent.log");
        final Path workerOutput = directory.resolve("job.log");
        final String configuration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Configuration><Appenders><File name=\"AGENT\" fileName=\""
                + agentLog.toString().replace("&", "&amp;").replace("\"", "&quot;")
                + "\"><PatternLayout pattern=\"%level %message%n\"/></File></Appenders>"
                + "<Loggers><Root level=\"INFO\"><AppenderRef ref=\"AGENT\"/></Root></Loggers></Configuration>";
        Files.writeString(directory.resolve("log4j-cloud.xml"), configuration, StandardCharsets.UTF_8);
        final String classpath = directory + File.pathSeparator
                + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final Process worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, LibvirtAblestackJobMain.class.getName())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(workerOutput.toFile()).start();
        try {
            assertTrue("The detached entry point must exit without contacting libvirt when arguments are missing",
                    worker.waitFor(30, TimeUnit.SECONDS));
            assertEquals(1, worker.exitValue());
            assertTrue(Files.readString(workerOutput).contains("backup command class and command file are required"));
            assertTrue("The worker must discover log4j-cloud.xml and persist INFO logs outside its job output",
                    Files.isRegularFile(agentLog));
            assertTrue(Files.readString(agentLog).contains("INFO log4j configuration found at"));
            assertTrue(Files.readString(agentLog).contains("phase=[WORKER_LOGGING_INITIALIZED]"));
        } finally {
            if (worker.isAlive()) {
                worker.destroyForcibly();
            }
        }
    }
}
