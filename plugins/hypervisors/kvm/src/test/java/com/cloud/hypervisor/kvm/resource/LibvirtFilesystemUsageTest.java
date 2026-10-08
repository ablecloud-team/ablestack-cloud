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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class LibvirtFilesystemUsageTest {
    private JsonObject snapshot() throws Exception {
        try(java.io.InputStream source=getClass().getResourceAsStream("/sharedfs-actual-qga-fsinfo.json")) {
            Assert.assertNotNull(source);return JsonParser.parseString(new String(source.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test public void actualSharedFsBindMountsCountOnceWhileIndependentRootPartitionsAreSummed() throws Exception {
        Map<String,Long> usage=LibvirtComputingResource.parseQemuGuestFilesystemUsage(snapshot().toString());
        Assert.assertEquals(Long.valueOf(183300096L),usage.get("a0bbe566-7aa6-4bbd-9998"));
        Assert.assertEquals(Long.valueOf(53917696L+1500401664L),usage.get("93a86081-946d-4b7f-b64e"));Assert.assertEquals(2,usage.size());
    }
    @Test public void duplicateSamplesChooseMaximumOnceRatherThanAddingPerShare() throws Exception {
        JsonObject data=snapshot();data.getAsJsonArray("return").get(1).getAsJsonObject().addProperty("used-bytes",183300097L);
        Assert.assertEquals(Long.valueOf(183300097L),LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString()).get("a0bbe566-7aa6-4bbd-9998"));
    }
    @Test public void repeatedDiskAddressesAndOrderCannotDuplicateFilesystemUsage() throws Exception {
        JsonObject data=snapshot();JsonObject first=data.getAsJsonArray("return").get(0).getAsJsonObject();JsonObject disk=first.getAsJsonArray("disk").get(0).getAsJsonObject();first.getAsJsonArray("disk").add(disk.deepCopy());
        Assert.assertEquals(Long.valueOf(183300096L),LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString()).get("a0bbe566-7aa6-4bbd-9998"));
    }
    @Test public void missingIdentityNegativeAndFractionalCountersCannotProducePlausibleUsage() throws Exception {
        JsonObject data=snapshot();JsonArray rows=data.getAsJsonArray("return");rows.get(0).getAsJsonObject().remove("name");rows.get(1).getAsJsonObject().addProperty("used-bytes",-1);rows.get(2).getAsJsonObject().addProperty("used-bytes",1.5);
        Assert.assertFalse(LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString()).containsKey("a0bbe566-7aa6-4bbd-9998"));
    }
    @Test public void overlappingNamesOnDifferentHardwareStayIndependentAndMountpointIsIgnored() throws Exception {
        JsonObject data=snapshot();JsonObject other=data.getAsJsonArray("return").get(0).getAsJsonObject().deepCopy();other.getAsJsonArray("disk").get(0).getAsJsonObject().addProperty("serial","0QEMU_QEMU_HARDDISK_bbbbbbbbccccddddeeee");data.getAsJsonArray("return").add(other);
        Map<String,Long> usage=LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString());Assert.assertEquals(Long.valueOf(183300096L),usage.get("bbbbbbbb-cccc-dddd-eeee"));Assert.assertEquals(Long.valueOf(183300096L),usage.get("a0bbe566-7aa6-4bbd-9998"));
    }
    @Test public void overflowOmitsAffectedDiskAndLeavesOtherDisksAvailable() throws Exception {
        JsonObject data=snapshot();JsonArray rows=data.getAsJsonArray("return");rows.get(3).getAsJsonObject().addProperty("used-bytes",Long.MAX_VALUE);rows.get(4).getAsJsonObject().addProperty("used-bytes",1);
        Map<String,Long> usage=LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString());Assert.assertFalse(usage.containsKey("93a86081-946d-4b7f-b64e"));Assert.assertEquals(Long.valueOf(183300096L),usage.get("a0bbe566-7aa6-4bbd-9998"));
    }
    @Test public void shuffledJsonFieldOrderAndRepeatedAddressesHaveTheSameFilesystemIdentity() throws Exception {
        JsonObject data=snapshot();JsonObject duplicate=data.getAsJsonArray("return").get(0).getAsJsonObject().deepCopy();JsonObject disk=duplicate.getAsJsonArray("disk").get(0).getAsJsonObject();
        JsonObject reordered=new JsonObject();reordered.add("target",disk.get("target"));reordered.add("dev",disk.get("dev"));reordered.add("pci-controller",disk.get("pci-controller"));reordered.add("unit",disk.get("unit"));reordered.add("bus",disk.get("bus"));reordered.add("bus-type",disk.get("bus-type"));reordered.add("serial",disk.get("serial"));JsonArray addresses=new JsonArray();addresses.add(reordered);duplicate.add("disk",addresses);data.getAsJsonArray("return").add(duplicate);
        Assert.assertEquals(Long.valueOf(183300096L),LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString()).get("a0bbe566-7aa6-4bbd-9998"));
    }
    @Test public void unavailableCounterIsOmittedWhileOtherFilesystemSamplesRemainAvailable() throws Exception {
        JsonObject data=snapshot();data.getAsJsonArray("return").get(3).getAsJsonObject().remove("used-bytes");
        Map<String,Long> usage=LibvirtComputingResource.parseQemuGuestFilesystemUsage(data.toString());Assert.assertEquals(Long.valueOf(1500401664L),usage.get("93a86081-946d-4b7f-b64e"));Assert.assertEquals(Long.valueOf(183300096L),usage.get("a0bbe566-7aa6-4bbd-9998"));
    }

}
