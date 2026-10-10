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

import java.util.List;
import java.util.Comparator;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Volume;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.NicVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.cloud.utils.exception.CloudRuntimeException;

/** The ROOT device and template can change; VM identity, NICs and every DATA attachment cannot. */
public final class StorageRootTopologySnapshot {
    private StorageRootTopologySnapshot() { }
    public static JsonObject capture(UserVmVO vm,List<NicVO> nics,List<com.cloud.vm.dao.NicSecondaryIpVO> aliases,List<VolumeVO> volumes) {
        JsonObject result=new JsonObject();
        result.addProperty("vmUuid",vm.getUuid());result.addProperty("vmId",vm.getId());
        result.addProperty("accountId",vm.getAccountId());result.addProperty("domainId",vm.getDomainId());
        result.addProperty("zoneId",vm.getDataCenterId());result.addProperty("serviceOfferingId",vm.getServiceOfferingId());
        JsonArray interfaces=new JsonArray();
        nics.stream().sorted(Comparator.comparingLong(NicVO::getId)).forEach(nic->{
            if (nic.getInstanceId()!=vm.getId()) throw new CloudRuntimeException("NIC snapshot scope changed");
            JsonObject value=new JsonObject();value.addProperty("id",nic.getId());value.addProperty("uuid",nic.getUuid());
            value.addProperty("deviceId",nic.getDeviceId());value.addProperty("networkId",nic.getNetworkId());value.addProperty("defaultNic",nic.isDefaultNic());
            value.addProperty("mac",nic.getMacAddress());value.addProperty("ipv4",nic.getIPv4Address());value.addProperty("ipv6",nic.getIPv6Address());
            interfaces.add(value);
        });
        JsonArray secondary=new JsonArray();
        java.util.Set<Long> nicIds=new java.util.HashSet<>();nics.forEach(nic->nicIds.add(nic.getId()));
        aliases.stream().sorted(Comparator.comparingLong(com.cloud.vm.dao.NicSecondaryIpVO::getId)).forEach(alias->{
            if (!nicIds.contains(alias.getNicId())) throw new CloudRuntimeException("Secondary IP snapshot scope changed");
            JsonObject value=new JsonObject();value.addProperty("id",alias.getId());value.addProperty("nicId",alias.getNicId());
            value.addProperty("networkId",alias.getNetworkId());value.addProperty("ipv4",alias.getIp4Address());value.addProperty("ipv6",alias.getIp6Address());
            secondary.add(value);
        });
        result.add("secondaryIps",secondary);
        JsonArray data=new JsonArray();
        volumes.stream().filter(volume->volume.getVolumeType()==Volume.Type.DATADISK)
                .sorted(Comparator.comparingLong(VolumeVO::getId)).forEach(volume->{
                    if (volume.getInstanceId()==null || volume.getInstanceId()!=vm.getId()) throw new CloudRuntimeException("DATA snapshot attachment scope changed");
                    JsonObject value=new JsonObject();value.addProperty("id",volume.getId());value.addProperty("uuid",volume.getUuid());
                    value.addProperty("deviceId",volume.getDeviceId());value.addProperty("poolId",volume.getPoolId());
                    value.addProperty("size",volume.getSize());value.addProperty("format",volume.getFormat()==null?null:volume.getFormat().name());
                    data.add(value);
                });
        result.add("nics",interfaces);result.add("dataVolumes",data);return result;
    }
    public static void requireSame(JsonObject expected,JsonObject current) {
        if (!expected.equals(current)) throw new CloudRuntimeException("ROOT upgrade changed VM identity, NIC or DATA attachment topology");
    }
}
