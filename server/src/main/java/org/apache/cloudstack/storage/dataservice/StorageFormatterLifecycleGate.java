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
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
/** Native lease absence cannot prove an older orphan formatter or its partial filesystem is safe. */
public final class StorageFormatterLifecycleGate {
    private StorageFormatterLifecycleGate() { }
    public static void requireIdle(JsonObject status) {
        if(status==null || !status.has("success") || !status.get("success").getAsBoolean())throw new CloudRuntimeException("Formatter journal is unobserved; preserve VM/DATA");
        String phase=status.has("status")?status.get("status").getAsString():"UNKNOWN";
        if (status.has("formatterActive") && !status.get("formatterActive").isJsonNull() && status.get("formatterActive").getAsBoolean()
                || status.has("terminationPending") && !status.get("terminationPending").isJsonNull() && status.get("terminationPending").getAsBoolean()) {
            throw new CloudRuntimeException("Explicit active or terminating formatter overrides journal phase; preserve VM/DATA");
        }
        if("NOT_STARTED".equals(phase))return;
        if(!status.has("formatterActive") || status.get("formatterActive").getAsBoolean()
                || status.has("terminationPending") && status.get("terminationPending").getAsBoolean())throw new CloudRuntimeException("Active or terminating formatter preserves VM/DATA");
        JsonObject journal=status.has("operation") && status.get("operation").isJsonObject()?status.getAsJsonObject("operation"):null;
        if(journal==null)throw new CloudRuntimeException("Formatter journal is unavailable; preserve VM/DATA");
        if(journal.has("terminationPending") && journal.get("terminationPending").getAsBoolean())throw new CloudRuntimeException("Formatter termination is pending; preserve VM/DATA");
        boolean started=journal.has("formatStarted") && journal.get("formatStarted").getAsBoolean();
        String recorded=journal.has("phase")?journal.get("phase").getAsString():"UNKNOWN";
        if(started && !"COMPLETE".equals(recorded))throw new CloudRuntimeException("Incomplete formatter journal requires forward recovery; UUID/type alone do not permit lifecycle or rollback mutation");
    }
}
