// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.Assert;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;

public class DrHardwareCompatibilityPolicyTest {
    @Test public void tuningAndComputeDifferencesDoNotChangeBootCompatibility() {
        for (String sourceValue : new String[] {null, "true", "false"}) {
            for (String targetValue : new String[] {null, "true", "false"}) {
                Map<String, String> source = new HashMap<>();
                Map<String, String> target = new HashMap<>();
                source.put("UEFI", "LEGACY"); target.put("UEFI", "legacy");
                source.put("iothreads", sourceValue); target.put("iothreads", targetValue);
                source.put("io.policy", "native"); target.put("io.policy", "io_uring");
                source.put("memory", "4096"); target.put("memory", "8192");
                DrHardwareCompatibilityPolicy.verifyDetails(source, target);
            }
        }
    }
    @Test(expected = CloudRuntimeException.class) public void firmwareMismatchStillBlocks() {
        DrHardwareCompatibilityPolicy.verifyDetails(Map.of("UEFI", "LEGACY"), Map.of());
    }
    @Test(expected = CloudRuntimeException.class) public void securityMismatchStillBlocks() {
        DrHardwareCompatibilityPolicy.verifyDetails(Map.of("UEFI", "SECURE"), Map.of("UEFI", "LEGACY"));
    }
    @Test(expected = CloudRuntimeException.class) public void requiredControllerStillBlocks() {
        DrHardwareCompatibilityPolicy.verifyDetails(Map.of("rootDiskController", "virtio"),
                Map.of("rootDiskController", "scsi"));
    }
    @Test public void bootEvidenceOmitsPerformanceButKeepsSourceIdentity() {
        Assert.assertEquals(JsonParser.parseString("{\"sourceVmRef\":\"vm-1\",\"vmDetails\":{\"uefi\":\"legacy\"}}"),
                DrHardwareCompatibilityPolicy.bootSnapshot(JsonParser.parseString(
                        "{\"sourceVmRef\":\"vm-1\",\"cpuCount\":4,\"vmDetails\":{\"UEFI\":\"LEGACY\",\"iothreads\":\"true\"}}").getAsJsonObject()));
    }
}
