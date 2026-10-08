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
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
public class StorageFormatterLifecycleGateTest {
    private JsonObject state(String phase,boolean active){JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("status",phase);result.addProperty("formatterActive",active);JsonObject journal=new JsonObject();journal.addProperty("formatStarted",true);journal.addProperty("phase",phase);journal.addProperty("filesystemUuid","partial-header-uuid");result.add("operation",journal);return result;}
    @Test public void rawIdleCannotApproveOrphanOrPartialFormatterWithAHeaderUuid(){Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(state("TIMED_OUT_PENDING_RECONCILE",false)));Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(state("FORMATTING",false)));Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(state("COMPLETE",true)));}
    @Test public void knownInactiveCompleteJournalPermitsExistingMountLifecycleButUnknownDoesNot(){StorageFormatterLifecycleGate.requireIdle(state("COMPLETE",false));JsonObject unknown=state("COMPLETE",false);unknown.remove("formatterActive");Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(unknown));JsonObject blocked=state("COMPLETE",false);blocked.addProperty("terminationPending",true);Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(blocked));}
    @Test public void explicitActiveOrTerminatingFormatterOverridesNotStartedButLegacyAbsenceRemainsReadable() {
        JsonObject value=new JsonObject();value.addProperty("success",true);value.addProperty("status","NOT_STARTED");
        StorageFormatterLifecycleGate.requireIdle(value);
        value.addProperty("formatterActive",true);
        Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(value));
        value.remove("formatterActive");value.addProperty("terminationPending",true);
        Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(value));
    }

}
