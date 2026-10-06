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
import org.json.JSONObject;
import org.json.JSONArray;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class HostBlockDeviceSafetyTest {
    private static class Inspector extends HostBlockDeviceSafety {
        private String blocks;
        private String domain = "<domain/>";
        private String inactive = "<domain/>";
        private boolean fail;
        private boolean incomplete;
        private String mountInfo = "{\"filesystems\":[]}";
        private void complete(JSONArray nodes) {
            for (int n = 0; n < nodes.length(); n++) {
                JSONObject node = nodes.getJSONObject(n);
                String kname = node.getString("kname");
                for (String field : Arrays.asList("name", "fstype", "wwn", "size", "serial", "model", "pttype")) {
                    if (!node.has(field)) { node.put(field, "name".equals(field) ? "/dev/" + kname.replace("/dev/", "") : ""); }
                }
                node.put("maj:min", kname.endsWith("1") ? "8:17" : kname.endsWith("c") ? "8:32" : "8:16");
                node.put("mountpoints", new JSONArray().put(node.opt("mountpoint") == null ? JSONObject.NULL : node.opt("mountpoint")));
                JSONArray children = node.optJSONArray("children");
                if (children != null) { complete(children); }
            }
        }

        Inspector(String blocks) { this.blocks = "{\"blockdevices\":[" + blocks + "]}"; }

        @Override protected String run(String command, String... args) throws Exception {
            if (fail) { throw new IllegalStateException("offline"); }
            if (command.contains("lsblk")) {
                JSONObject data = new JSONObject(blocks);
                if (!incomplete) { complete(data.getJSONArray("blockdevices")); }
                return data.toString();
            }
            if (command.contains("findmnt")) { return mountInfo; }
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

    @Test public void unmountedPartitionsRequireExplicitAcknowledgement() {
        Inspector i = new Inspector(disk("sdb", ",\"children\":[{\"kname\":\"sdb1\",\"type\":\"part\"}]")); i.inspect();
        assertEquals("partitioned", i.status("sdb"));
        String xml = "<disk type='block'><source dev='/dev/sdb'/></disk>";
        assertTrue(i.attachmentDenial(xml, false, false).contains("확인"));
        assertNull(i.attachmentDenial(xml, false, true));
        assertEquals(true, i.details(Arrays.asList("/dev/sdb")).get("/dev/sdb").get("haspartitions"));
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
    @Test public void ordinaryPartitionFilesystemDoesNotBlockButStorageSignaturesDo() {
        for (String fs : Arrays.asList("ext4", "xfs", "ntfs", "vfat")) {
            Inspector i = new Inspector(disk("sdb", ",\"children\":[{\"kname\":\"sdb1\",\"type\":\"part\",\"fstype\":\"" + fs + "\"}]")); i.inspect();
            assertEquals("partitioned", i.status("sdb"));
            assertNull(i.attachmentDenial("<hostdev type='scsi'><source><adapter name='scsi_host0'/><address bus='0' target='275' unit='0'/></source></hostdev>", true, true));
        }
        for (String fs : Arrays.asList("LVM2_member", "linux_raid_member", "swap", "crypto_LUKS")) {
            Inspector i = new Inspector(disk("sdb", ",\"children\":[{\"kname\":\"sdb1\",\"type\":\"part\",\"fstype\":\"" + fs + "\"}]")); i.inspect();
            assertEquals("host-volume", i.status("sdb"));
            assertNotEquals(null, i.attachmentDenial("<disk type='block'><source dev='/dev/sdb'/></disk>", false, true));
        }
    }

    @Test public void realMountTableAndAliasUsageOverridePartitionWarning() {
        Inspector i = new Inspector(disk("sdb", ",\"wwn\":\"same\",\"children\":[{\"kname\":\"sdb1\",\"type\":\"part\",\"fstype\":\"ext4\"}]") + "," + disk("sdc", ",\"wwn\":\"same\""));
        i.mountInfo = "{\"filesystems\":[{\"maj:min\":\"8:17\",\"target\":\"/mnt/test\",\"children\":[{\"maj:min\":\"8:17\",\"target\":\"/mnt/bind\"}]}]}";
        i.inspect();
        assertEquals("mounted", i.status("sdb"));
        assertEquals("mounted", i.status("sdc"));
        assertNotEquals(null, i.attachmentDenial("<disk type='block'><source dev='/dev/sdc'/></disk>", false, true));
        assertEquals(Arrays.asList("/mnt/bind", "/mnt/test"), i.details(Arrays.asList("/dev/sdb")).get("/dev/sdb").get("mountpoints"));
        i.mountInfo = "{\"filesystems\":[]}";
        i.inspect();
        assertEquals("partitioned", i.status("sdb"));
        i.domain = "<domain><devices><disk type='block'><source dev='/dev/sdc'/></disk></devices></domain>";
        i.inspect();
        assertEquals("vm-connected", i.status("sdb"));
    }

    @Test public void missingMountFieldsAndEmptyPartitionTableFailSafely() {
        Inspector i = new Inspector(disk("sdb", ",\"pttype\":\"gpt\"")); i.inspect();
        assertEquals("partitioned", i.status("sdb"));
        i.incomplete = true; i.inspect();
        assertEquals("unknown", i.status("sdb"));
        assertNotEquals(null, i.attachmentDenial("<disk type='block'><source dev='/dev/sdb'/></disk>", false, true));
    }

}
