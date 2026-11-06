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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A GFS2 glock timeout may return ESTALE before rename changes either name. */
final class LibvirtAblestackRestoreFileMove {
    private static final Logger LOGGER = LogManager.getLogger(LibvirtAblestackRestoreFileMove.class);
    private static final int MAX_RETRIES = 3;

    private LibvirtAblestackRestoreFileMove() { }

    static void move(Path source, Path target) throws IOException {
        final BasicFileAttributes original = attributes(source);
        assertAbsent(target);
        for (int retry = 0; ; retry++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Interrupted before restore rename: " + source);
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileSystemException error) {
                if (retry >= MAX_RETRIES || !isStale(error)
                        || !"gfs2".equalsIgnoreCase(Files.getFileStore(source.getParent()).type())) {
                    throw error;
                }
                validateRetry(source, target, original, error);
                LOGGER.warn("Restore rename received ESTALE; retry=[{}/{}], source=[{}], target=[{}]",
                        retry + 1, MAX_RETRIES, source, target);
                try {
                    Thread.sleep(250L << retry);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException("Interrupted during GFS2 restore rename: " + source);
                    failure.initCause(error);
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
                // A competing operation may have changed either name while we backed off.
                validateRetry(source, target, original, error);
            }
        }
    }

    private static boolean isStale(FileSystemException error) {
        return "Stale file handle".equalsIgnoreCase(error.getReason()) || "ESTALE".equals(error.getReason());
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void assertAbsent(Path path) throws IOException {
        try {
            attributes(path);
        } catch (NoSuchFileException absent) {
            return;
        }
        throw new IOException("Restore transaction refuses to overwrite " + path);
    }

    private static void validateRetry(Path source, Path target, BasicFileAttributes original, IOException error) throws IOException {
        BasicFileAttributes current = attributes(source);
        if (!original.isRegularFile() || !current.isRegularFile() || original.fileKey() == null
                || !original.fileKey().equals(current.fileKey()) || original.size() != current.size()
                || !original.lastModifiedTime().equals(current.lastModifiedTime())) {
            throw new IOException("Restore rename source changed after ESTALE; refusing retry: " + source, error);
        }
        assertAbsent(target);
    }
}
