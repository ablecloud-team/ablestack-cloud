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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Cleanup is restricted to one job directory, even when staging shares primary storage. */
final class LibvirtAblestackStagingCleanup {
    private static final Map<String, Path> LEGACY_ROOTS = Map.of(
            "ablestack-commvault", Path.of("/tmp/mold/backup"),
            "ablestack-netbackup", Path.of("/tmp/mold/netbackup"),
            "ablestack-veeam", Path.of("/tmp/mold/veeam"));
    private static final String TIMESTAMP = "[0-9]{4}(\\.[0-9]{2}){5}\\.[0-9]{3}";

    private LibvirtAblestackStagingCleanup() { }

    static void validate(String provider, Path path, Path configuredRoot) throws IOException {
        Path legacy = LEGACY_ROOTS.get(provider);
        if (legacy == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IOException("Invalid staging cleanup provider or path: " + path);
        }
        Path root = configuredRoot;
        if (path.startsWith(legacy)) {
            root = legacy;
        } else if (root == null) {
            root = path.getParent() == null ? null : path.getParent().getParent();
        }
        if (root == null || root.getFileName() == null || !root.isAbsolute() || !root.equals(root.normalize())
                || (!root.equals(legacy) && !provider.equals(root.getFileName().toString()))
                || !path.startsWith(root) || root.relativize(path).getNameCount() != 2) {
            throw new IOException("Cleanup must target a provider's individual job directory: " + path);
        }
        Path relative = root.relativize(path);
        String namespace = relative.getName(0).toString();
        String job = relative.getName(1).toString();
        if (!namespace.matches("[A-Za-z0-9_.-]+") || namespace.equals(".") || namespace.equals("..")
                || !(namespace.equals("restore") ? job.matches("[A-Za-z0-9_.-]+") && !job.equals(".") && !job.equals("..")
                        : job.matches(TIMESTAMP))) {
            throw new IOException("Invalid staging job directory: " + path);
        }
        // Checking every ancestor prevents a VM/provider directory symlink from redirecting cleanup.
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Staging cleanup cannot traverse a symbolic link: " + current);
            }
        }
        // Bind mounts can share the same device, so device comparisons alone cannot protect their data.
        List<String> mounts = Files.readAllLines(Path.of("/proc/self/mountinfo"));
        if (mounts.isEmpty()) { throw new IOException("Unable to inspect staging cleanup mounts"); }
        for (String line : mounts) {
            String[] fields = line.split(" ");
            if (fields.length < 10 || !fields[4].startsWith("/")) {
                throw new IOException("Unable to inspect staging cleanup mounts");
            }
            String mount = fields[4].replace("\\040", " ").replace("\\011", "\t")
                    .replace("\\012", "\n").replace("\\134", "\\");
            if (Path.of(mount).startsWith(path)) {
                throw new IOException("Staging cleanup cannot delete a mount point or its parent: " + mount);
            }
        }
    }

    static void delete(String provider, Path path, Path configuredRoot) throws IOException {
        validate(provider, path, configuredRoot);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return; }
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path item : paths) { Files.deleteIfExists(item); }
        }
    }
}
