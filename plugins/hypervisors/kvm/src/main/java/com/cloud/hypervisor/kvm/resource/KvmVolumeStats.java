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
package com.cloud.hypervisor.kvm.resource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.VolumeStatsEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded, read-only file observations that never enter libvirt storage pool locks. */
public final class KvmVolumeStats {
    private static final Logger LOGGER = LogManager.getLogger(KvmVolumeStats.class);
    private static final long COLLECTION_BUDGET_MS = 5000;
    private static final long VOLUME_BUDGET_MS = 1000;
    // All filesystem access stays in the bounded child: even realpath/stat can block on GFS.
    // exec preserves the child's PID so a stuck qemu-img retains the shared probe admission.
    static final String FILE_PROBE = "import os,pathlib,sys\n"
            + "root=pathlib.Path(sys.argv[1]).resolve(strict=True)\n"
            + "path=(root/sys.argv[2]).resolve(strict=True)\n"
            + "path.relative_to(root)\n"
            + "if not root.is_dir() or not path.is_file(): raise RuntimeError('Not a volume file')\n"
            + "os.execvp('qemu-img',['qemu-img','info','--force-share','--output=json',str(path)])\n";

    private KvmVolumeStats() { }

    public static HashMap<String, VolumeStatsEntry> collect(String poolPath, List<String> volumeLocators) {
        HashMap<String, VolumeStatsEntry> result = new HashMap<>();
        if (volumeLocators == null || volumeLocators.isEmpty()) {
            return result;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COLLECTION_BUDGET_MS);
        for (String locator : volumeLocators) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (locator == null || locator.isBlank()) {
                continue;
            }
            try {
                String output = KvmVmOperationGuard.probe(Math.min(VOLUME_BUDGET_MS, remaining),
                        "python3", "-c", FILE_PROBE, poolPath, locator);
                JsonObject info = JsonParser.parseString(output).getAsJsonObject();
                long allocated = size(info, "actual-size");
                long virtual = size(info, "virtual-size");
                result.put(locator, new VolumeStatsEntry(locator, allocated, virtual));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("Volume stats deferred volume={} reason={}", locator, e.toString());
            }
        }
        LOGGER.debug("Collected file volume stats poolPath={} requested={} collected={}",
                poolPath, volumeLocators.size(), result.size());
        return result;
    }

    private static long size(JsonObject info, String key) {
        if (!info.has(key) || !info.get(key).isJsonPrimitive() || !info.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing volume size: " + key);
        }
        long value = new BigDecimal(info.get(key).getAsString()).longValueExact();
        if (value < 0) {
            throw new IllegalArgumentException("Negative volume size: " + key);
        }
        return value;
    }
}
