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
package org.apache.cloudstack.storage.dataservice;

import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Persistent exact DATA observation needed when a stopped guest cannot answer safety probes. */
public final class StorageVolumeLifecycleProtection {
    public static final String STATE = "storage.service.format.protection";
    public static final String RECEIPT = "storage.service.lifecycle.safety";
    private StorageVolumeLifecycleProtection() { }

    public static JsonObject identity(VolumeVO volume) {
        JsonObject value = new JsonObject();
        value.addProperty("id", volume.getId());
        value.addProperty("uuid", volume.getUuid());
        value.addProperty("type", volume.getVolumeType().name());
        value.addProperty("accountId", volume.getAccountId());
        value.addProperty("domainId", volume.getDomainId());
        value.addProperty("zoneId", volume.getDataCenterId());
        value.addProperty("poolId", volume.getPoolId());
        value.addProperty("sizeBytes", volume.getSize());
        return value;
    }

    public static void requireVerified(VolumeVO volume, String state, String receipt) {
        boolean verified = "VERIFIED".equals(state) && receipt != null;
        try {
            verified = verified && identity(volume).equals(JsonParser.parseString(receipt));
        } catch (RuntimeException malformed) {
            verified = false;
        }
        if (!verified) throw new CloudRuntimeException("Stopped or unavailable guest has no exact persistent formatter/DATA safety receipt; preserve VM and DATA until fresh verification");
    }
}
