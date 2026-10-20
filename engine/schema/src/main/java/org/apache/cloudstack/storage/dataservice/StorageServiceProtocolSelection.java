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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import com.cloud.utils.exception.CloudRuntimeException;

/** A representative is advisory metadata; every registered listener group remains in the payload. */
public final class StorageServiceProtocolSelection {
    private StorageServiceProtocolSelection() { }
    private static boolean active(StorageServiceProtocolVO row) {
        return row.getState()==StorageServiceInstance.ResourceState.Ready||row.getState()==StorageServiceInstance.ResourceState.Allocated;
    }
    private static boolean wildcard(String ip) {return ip==null||ip.isBlank()||"0.0.0.0".equals(ip)||"::".equals(ip);}
    private static int rank(StorageServiceProtocolVO row) {return row.isEnabled()?(active(row)?0:1):(active(row)?2:3);}
    private static int port(StorageServiceProtocolVO row) {
        if(row.getPort()!=null)return row.getPort();
        return row.getProtocol()==StorageServiceInstance.Protocol.NFS?2049:row.getProtocol()==StorageServiceInstance.Protocol.SMB?445:
                row.getProtocol()==StorageServiceInstance.Protocol.ISCSI?3260:4420;
    }
    public static List<StorageServiceProtocolVO> ordered(long instanceId,StorageServiceInstance.Protocol protocol,List<StorageServiceProtocolVO> rows) {
        if(rows==null||rows.stream().anyMatch(row->row==null||row.getInstanceId()!=instanceId||row.getProtocol()!=protocol))
            throw new CloudRuntimeException("Storage protocol representative scope is unavailable");
        List<StorageServiceProtocolVO> result=new ArrayList<>(rows);
        // Prefer an enabled existing explicit endpoint, then the oldest persisted declaration.
        // Adding a later port group must not silently change the original primary representative.
        result.sort(Comparator.comparingInt(StorageServiceProtocolSelection::rank)
                .thenComparingInt(row->wildcard(row.getListenIp())?1:0)
                .thenComparingLong(row->row.getId()>0?row.getId():Long.MAX_VALUE)
                .thenComparingInt(StorageServiceProtocolSelection::port)
                .thenComparing(row->row.getListenIp()==null?"":row.getListenIp().toLowerCase(Locale.ROOT))
                .thenComparing(row->row.getUuid()==null?"":row.getUuid()));
        return result;
    }
    public static StorageServiceProtocolVO representative(long instanceId,StorageServiceInstance.Protocol protocol,List<StorageServiceProtocolVO> rows) {
        List<StorageServiceProtocolVO> ordered=ordered(instanceId,protocol,rows);return ordered.isEmpty()?null:ordered.get(0);
    }
    public static boolean usable(StorageServiceProtocolVO row) {
        return row!=null&&row.isEnabled()&&active(row)&&(row.getPort()==null||row.getPort()>0&&row.getPort()<=65535);
    }
}
