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
package org.apache.cloudstack.storage.command;

import java.util.Map;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import com.cloud.storage.VolumeAllocationPolicy;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VolumeAllocationTransportTest {
    private VolumeObjectTO explicit() { VolumeObjectTO volume = new VolumeObjectTO(); volume.setAllocation(VolumeAllocationPolicy.request("full", "raw", null)); return volume; }
    @Test public void explicitCreateWaitIncludesLongZeroingAndVerification() { assertEquals(3600, new CreateObjectCommand(explicit()).getWait()); }
    @Test public void explicitCopyWaitHasAnHourMinimum() { assertEquals(3600, new CopyCommand(null, explicit(), 60, false).getWait()); }
    @Test public void largerExistingCopyBudgetIsPreserved() { assertEquals(7200, new CopyCommand(null, explicit(), 7200, false).getWait()); }
    @Test public void legacyTimeoutsRemainUnchanged() { assertEquals(0, new CreateObjectCommand(new VolumeObjectTO()).getWait()); assertEquals(60, new CopyCommand(null, new VolumeObjectTO(), 60, false).getWait()); }
    @Test public void gsonTransportsIntentAndEvidenceWithoutDependingOnAnOfferingLookup() {
        VolumeObjectTO volume = explicit(); VolumeObjectTO copy = new Gson().fromJson(new Gson().toJson(volume), VolumeObjectTO.class); assertEquals(volume.getAllocation(), copy.getAllocation());
    }
    @Test public void unknownOldAgentPayloadHasEmptyIntent() { assertTrue(new Gson().fromJson("{}", VolumeObjectTO.class).getAllocation().isEmpty()); }
    @Test public void accessorsDoNotAllowMutationOfTheStoredRequest() { VolumeObjectTO volume = explicit(); Map<String,String> intent = volume.getAllocation(); intent.clear(); assertEquals("full", volume.getAllocation().get(VolumeAllocationPolicy.POLICY)); }
}
