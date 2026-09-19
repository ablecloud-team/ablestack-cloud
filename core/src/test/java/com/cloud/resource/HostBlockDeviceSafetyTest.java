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
package com.cloud.resource;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class HostBlockDeviceSafetyTest {
    private static class Inspector extends HostBlockDeviceSafety {
        private String blocks;
        private String domain = "<domain/>";
        private String inactive = "<domain/>";
        private boolean fail;

        Inspector(String blocks) { this.blocks = "{\"blockdevices\":[" + blocks + "]}"; }

        @Override protected String run(String command, String... args) throws Exception {
            if (fail) { throw new IllegalStateException("offline"); }
            if (command.contains("lsblk")) { return blocks; }
            if ("list".equals(args[0])) { return "vm1\n"; }
            return Arrays.asList(args).contains("--inactive") ? inactive : domain;
        }

        @Override protected String resolve(String path) throws Exception {
            if ("/dev/sg1".equals(path) || "/dev/disk/by-id/wwn-free".equals(path)) { return "sdb"; }
            if ("/dev/mapper/mpatha".equals(path)) { return "dm-2"; }
            if (!path.startsWith("/dev/")) { throw new IllegalArgumentException("not a block device"); }
            return path.substring(5);
        }

        @Override protected String resolveScsi(String address) throws Exception {
            if (!"0:0:275:0".equals(address)) { throw new IllegalArgumentException("unknown"); }
            return "sdb";
        }
    }

    private String disk(String name, String extra) {
        return "{\"kname\":\"/dev/" + name + "\",\"type\":\"disk\",\"fstype\":null,\"mountpoint\":null" + extra + "}";
    }

    @Test public void unusedDiskAndAliasesRemainAvailable() {
        Inspector i = new Inspector(disk("sdb", "")); i.inspect();
        assertEquals("available", i.statuses(Arrays.asList("/dev/sg1 (wwn-free)")).values().iterator().next());
        assertEquals("available", i.attachmentStatus("<disk type='block'><source dev='/dev/disk/by-id/wwn-free'/></disk>", false));
    }

    @Test public void partitionWithoutFilesystemBlocksWholeDisk() {
        Inspector i = new Inspector(disk("sdb", ",\"children\":[{\"kname\":\"sdb1\",\"type\":\"part\"}]")); i.inspect();
        assertEquals("partitioned", i.status("sdb"));
    }

    @Test public void filesystemLvmSwapAndMountBlockWholeDisk() {
        for (String extra : Arrays.asList("\"fstype\":\"ext4\"", "\"fstype\":\"LVM2_member\"", "\"mountpoint\":\"[SWAP]\"", "\"mountpoint\":\"/mnt/gfs\"")) {
            Inspector i = new Inspector("{\"kname\":\"sdb\",\"type\":\"disk\"," + extra + "}"); i.inspect();
            assertNotEquals("available", i.status("sdb"));
        }
    }

    @Test public void multipathMembersSharePartitionAndVolumeUsage() {
        String mapper = "{\"kname\":\"dm-2\",\"type\":\"mpath\",\"children\":[{\"kname\":\"dm-3\",\"type\":\"lvm\"}]}";
        Inspector i = new Inspector(disk("sdb", ",\"wwn\":\"same\",\"children\":[" + mapper + "]") + "," + disk("sdc", ",\"wwn\":\"same\"")); i.inspect();
        assertEquals("host-volume", i.status("sdb"));
        assertEquals("host-volume", i.status("sdc"));
        assertEquals("host-volume", i.status("dm-2"));
    }

    @Test public void emptyMultipathMapIsNotAnAllocatedVolume() {
        Inspector i = new Inspector("{\"kname\":\"sdb\",\"type\":\"disk\",\"fstype\":\"mpath_member\",\"children\":[{\"kname\":\"dm-2\",\"type\":\"mpath\"}]}"); i.inspect();
        assertEquals("available", i.status("dm-2"));
    }

    @Test public void liveAndPersistentVmSourcesBlockScsiAndLunAliases() {
        for (boolean persistent : Arrays.asList(false, true)) {
            for (String device : Arrays.asList("<disk type='block'><source dev='/dev/disk/by-id/wwn-free'/></disk>",
                    "<hostdev type='scsi'><source><adapter name='scsi_host0'/><address bus='0' target='275' unit='0'/></source></hostdev>")) {
                Inspector i = new Inspector(disk("sdb", ""));
                if (persistent) { i.inactive = "<domain><devices>" + device + "</devices></domain>"; }
                else { i.domain = "<domain><devices>" + device + "</devices></domain>"; }
                i.inspect();
                assertEquals("vm-connected", i.status("sdb"));
                assertEquals("vm-connected", i.attachmentStatus("<disk type='block'><source dev='/dev/sdb'/></disk>", false));
            }
        }
    }

    @Test public void unavailableInspectionAndMalformedXmlFailClosed() {
        Inspector i = new Inspector(disk("sdb", "")); i.fail = true; i.inspect();
        assertEquals("unknown", i.status("sdb"));
        i.fail = false; i.inspect();
        assertEquals("unknown", i.attachmentStatus("<hostdev type='scsi'/>", true));
        assertEquals("unknown", i.attachmentStatus("<disk type='block'><source dev='/dev/missing'/></disk>", false));
        i.domain = "<broken"; i.inspect();
        assertEquals("unknown", i.status("sdb"));
    }
}
