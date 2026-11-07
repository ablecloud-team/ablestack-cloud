// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Linux IFNAMSIZ includes the terminating NUL: usable names have at most 15 bytes. */
final class VlanBridgeName {
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

    private VlanBridgeName() {
    }

    static String forVlan(String pif, String vlanId) {
        if (pif == null || !pif.matches("[a-zA-Z0-9_.:-]{1,15}") || vlanId == null || !vlanId.matches("[0-9]{1,4}")) {
            throw new IllegalArgumentException("Invalid VLAN interface identity: pif=" + pif + ", vlan=" + vlanId);
        }
        String legacy = "br" + pif + "-" + vlanId;
        if (legacy.length() <= 15) {
            return legacy;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(pif.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 5; i++) {
                value = (value << 8) | (digest[i] & 0xff);
            }
            char[] token = new char[8];
            for (int i = token.length - 1; i >= 0; i--) {
                token[i] = BASE32.charAt((int) (value & 31));
                value >>>= 5;
            }
            // 40 bits of parent identity, plus the full VID. The script also verifies ownership.
            return "br" + new String(token) + "-" + vlanId;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for VLAN bridge names", e);
        }
    }
}
