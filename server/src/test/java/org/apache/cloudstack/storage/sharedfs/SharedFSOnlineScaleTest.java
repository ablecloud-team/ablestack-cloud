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

package org.apache.cloudstack.storage.sharedfs;
import org.junit.Assert;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import java.util.ArrayList;
import java.util.List;

public class SharedFSOnlineScaleTest {
    private JsonObject resources(int cpu,long bytes) { JsonObject r=new JsonObject();r.addProperty("success",true);r.addProperty("scaleActivationSupported",true);r.addProperty("onlineCpuCount",cpu);r.addProperty("memoryTotalBytes",bytes);return r; }
    private class Runtime implements SharedFSOnlineScale.Runtime {
        boolean resized,restored,activated,failResize,failHealth,failRestore;int bootProbeFailures;List<String> phases=new ArrayList<>();
        public void health() { if (restored && bootProbeFailures-->0) throw new CloudRuntimeException("booting");if (failHealth) throw new CloudRuntimeException("bad health"); }
        public JsonObject resources() { return SharedFSOnlineScaleTest.this.resources(resized && activated && !restored ? 4 : 2,resized && activated && !restored ? 8L<<30 : 4L<<30); }
        public void prepare(int count) { }
        public void resize() { resized=true;if (failResize) throw new CloudRuntimeException("partial memory change"); }
        public void activate(int count) { activated=true; }
        public void restore() { if (failRestore) throw new CloudRuntimeException("restore failed");restored=true; }
        public void pause() { }
        public void phase(String value) { phases.add(value); }
    }
    @Test public void coldBootKernelReservationDoesNotMasqueradeAsLostMemory() {
        JsonObject original=resources(4,8386260992L);
        Assert.assertTrue(SharedFSOnlineScale.originalResourcesMatch(original,resources(4,8312860672L)));
        Assert.assertFalse(SharedFSOnlineScale.originalResourcesMatch(original,resources(4,6L<<30)));
        Assert.assertFalse(SharedFSOnlineScale.originalResourcesMatch(original,resources(2,8312860672L)));
    }
    @Test public void rejectsDownscaleAndNoOpWithoutHardwareChanges() {
        Assert.assertThrows(InvalidParameterValueException.class,()->SharedFSOnlineScale.validate(2,4096,1,8192));
        Assert.assertThrows(InvalidParameterValueException.class,()->SharedFSOnlineScale.validate(2,4096,4,2048));
        Assert.assertThrows(InvalidParameterValueException.class,()->SharedFSOnlineScale.validate(2,4096,2,4096));
    }
    @Test public void verifiesGuestCpuAndMemoryBeforeCompleting() {
        Runtime r=new Runtime();JsonObject after=SharedFSOnlineScale.execute(r,4,4L<<30);
        Assert.assertEquals(4,after.get("onlineCpuCount").getAsInt());Assert.assertFalse(r.restored);Assert.assertEquals("COMPLETE",r.phases.get(r.phases.size()-1));
    }
    @Test public void oldRuntimeCannotMutateHardwareWithoutActivationSupport() {
        Runtime runtime=new Runtime() {
            @Override public JsonObject resources() { JsonObject legacy=super.resources();legacy.remove("scaleActivationSupported");return legacy; }
        };
        Assert.assertThrows(InvalidParameterValueException.class,()->SharedFSOnlineScale.execute(runtime,4,4L<<30));Assert.assertFalse(runtime.resized);
    }
    @Test public void unhealthyServiceNeverStartsResize() {
        Runtime r=new Runtime();r.failHealth=true;Assert.assertThrows(CloudRuntimeException.class,()->SharedFSOnlineScale.execute(r,4,4L<<30));Assert.assertFalse(r.resized);
    }
    @Test public void partialResizeFailureRestoresAndVerifiesOriginalResources() {
        Runtime r=new Runtime();r.failResize=true;Assert.assertThrows(CloudRuntimeException.class,()->SharedFSOnlineScale.execute(r,4,4L<<30));Assert.assertTrue(r.restored);Assert.assertEquals("ROLLED_BACK",r.phases.get(r.phases.size()-1));
    }
    @Test public void recoveryWaitsForGuestBootAndProtocolHealth() {
        Runtime r=new Runtime();r.failResize=true;r.bootProbeFailures=2;
        Assert.assertThrows(CloudRuntimeException.class,()->SharedFSOnlineScale.execute(r,4,4L<<30));
        Assert.assertTrue(r.restored);Assert.assertEquals("ROLLED_BACK",r.phases.get(r.phases.size()-1));
    }
    @Test public void failedRecoveryIsExplicitRatherThanSuccessfulRollback() {
        Runtime r=new Runtime();r.failResize=true;r.failRestore=true;Assert.assertThrows(CloudRuntimeException.class,()->SharedFSOnlineScale.execute(r,4,4L<<30));Assert.assertEquals("RECOVERY_REQUIRED",r.phases.get(r.phases.size()-1));
    }
}
