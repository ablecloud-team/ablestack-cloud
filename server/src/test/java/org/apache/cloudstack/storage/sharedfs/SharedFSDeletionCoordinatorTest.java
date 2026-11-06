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
import com.google.gson.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
public class SharedFSDeletionCoordinatorTest {
    private JsonObject plan;
    private SharedFSDeletionOperationVO op;
    private Fake port;
    private SharedFSDeletionCoordinator coordinator;
    private int writes;
    @Before public void open(){
        plan=JsonParser.parseString("{\"vmuuid\":\"vm-a\",\"volumes\":[{\"id\":\"1\",\"uuid\":\"data-a\",\"type\":\"DATADISK\",\"accountid\":\"2\",\"zoneid\":\"1\",\"size\":\"1024\",\"poolid\":\"1\",\"delete\":false},{\"id\":\"2\",\"uuid\":\"data-b\",\"type\":\"DATADISK\",\"accountid\":\"2\",\"zoneid\":\"1\",\"size\":\"2048\",\"poolid\":\"1\",\"delete\":false}]}").getAsJsonObject();
        op=new SharedFSDeletionOperationVO();op.setPlanJson(plan.toString());op.setState("PLANNED");op.setPhase("PLAN_VALIDATED");
        port=new Fake(plan);coordinator=new SharedFSDeletionCoordinator(port,x->writes++);
    }
    private JsonObject run(){return coordinator.execute(op,true);}
    @Test public void preserveAllDetachesBeforeRootAndNeverDeletes(){
        assertTrue(run().get("success").getAsBoolean());assertEquals(Arrays.asList("stop","detach:data-a","detach:data-b","root","metadata"),port.calls);
        assertEquals("COMPLETED",op.getState());assertTrue(writes>=8);
    }
    @Test public void selectedOnlyAfterAllDetachAndRoot(){
        plan.getAsJsonArray("volumes").get(1).getAsJsonObject().addProperty("delete",true);op.setPlanJson(plan.toString());
        run();assertEquals(Arrays.asList("stop","detach:data-a","detach:data-b","root","delete:data-b","metadata"),port.calls);
    }
    @Test public void partialDetachPersistsAndResumesOnlyPendingData(){
        port.fail="detach:data-b";run();assertEquals("RECOVERY_REQUIRED",op.getState());
        assertEquals(Arrays.asList("stop","detach:data-a","detach:data-b"),port.calls);String first=op.getOriginalFailureJson();
        port.fail=null;port.calls.clear();run();assertEquals(Arrays.asList("stop","detach:data-b","root","metadata"),port.calls);
        assertEquals(first,op.getOriginalFailureJson());assertEquals("COMPLETED",op.getState());
    }
    @Test public void stopFailureHasNoDetachOrDeletion(){port.fail="stop";run();assertEquals(Collections.singletonList("stop"),port.calls);assertEquals("RECOVERY_REQUIRED",op.getState());}
    @Test public void detachReturningWithoutActualDetachBlocksRoot(){port.noDetach=true;run();assertEquals(Arrays.asList("stop","detach:data-a"),port.calls);assertTrue(op.getOriginalFailureJson().contains("DATA_DETACH_UNCONFIRMED"));}
    @Test public void foreignAttachmentBlocksBeforeDetach(){port.volumes.get("data-a").addProperty("attachedvm","foreign");run();assertEquals(Collections.singletonList("stop"),port.calls);assertTrue(op.getOriginalFailureJson().contains("VOLUME_ATTACHED_ELSEWHERE"));}
    @Test public void changedSizeBlocksBeforeDetach(){port.volumes.get("data-a").addProperty("size","2049");run();assertEquals(Collections.singletonList("stop"),port.calls);assertTrue(op.getOriginalFailureJson().contains("VOLUME_IDENTITY_CHANGED"));}
    @Test public void rootFailureNeverDeletesSelected(){plan.getAsJsonArray("volumes").get(1).getAsJsonObject().addProperty("delete",true);op.setPlanJson(plan.toString());port.fail="root";run();assertFalse(port.calls.contains("delete:data-b"));assertFalse(port.calls.contains("metadata"));}
    @Test public void selectedFailureThenResumeDoesNotRepeatVmRemoval(){
        plan.getAsJsonArray("volumes").get(1).getAsJsonObject().addProperty("delete",true);op.setPlanJson(plan.toString());port.fail="delete:data-b";run();
        port.calls.clear();port.fail=null;run();assertEquals(Arrays.asList("delete:data-b","metadata"),port.calls);
    }
    @Test public void selectedProtectionAddedAfterApprovalBlocksDelete(){
        plan.getAsJsonArray("volumes").get(1).getAsJsonObject().addProperty("delete",true);op.setPlanJson(plan.toString());
        port.volumes.get("data-b").addProperty("deleteprotection",true);run();assertFalse(port.calls.contains("delete:data-b"));assertTrue(op.getOriginalFailureJson().contains("SELECTED_DATA_PROTECTED"));
    }
    @Test public void metadataFailureResumesWithoutDestructiveReplay(){
        port.fail="metadata";run();port.calls.clear();port.fail=null;run();assertEquals(Collections.singletonList("metadata"),port.calls);
    }
    @Test public void softPlanPromotedToExpungeKeepsIntentAcrossPartialFailureAndOldRequestReplay(){
        coordinator.execute(op,false);assertEquals("SOFT_DESTROYED",op.getState());
        port.fail="detach:data-b";coordinator.execute(op,true);
        assertEquals("RECOVERY_REQUIRED",op.getState());assertTrue(op.getResultJson().contains("expungeRequested"));
        assertFalse(port.calls.contains("root"));port.fail=null;port.calls.clear();
        coordinator.execute(op,false);
        assertEquals("COMPLETED",op.getState());assertFalse(port.calls.contains("soft"));assertEquals(Arrays.asList("stop","detach:data-b","root","metadata"),port.calls);
    }
    @Test public void completedReplayHasNoSideEffects(){run();port.calls.clear();run();assertTrue(port.calls.isEmpty());}
    @Test public void recoverableDestroyKeepsDataAttached(){coordinator.execute(op,false);assertEquals(Arrays.asList("stop","soft"),port.calls);assertEquals("SOFT_DESTROYED",op.getState());assertEquals("vm-a",port.volumes.get("data-a").get("attachedvm").getAsString());}
    @Test public void preEffectRejectionIsTerminalAndDoesNotStopTheVm(){port.fail="validate";run();assertEquals("REJECTED",op.getState());assertTrue(port.calls.isEmpty());port.fail=null;run();assertTrue(port.calls.isEmpty());}
    static class Fake implements SharedFSDeletionCoordinator.Port {
        final Map<String,JsonObject> volumes=new HashMap<>();final List<String> calls=new ArrayList<>();String fail;boolean removed,noDetach;
        Fake(JsonObject plan){for(JsonElement e:plan.getAsJsonArray("volumes")){JsonObject v=e.getAsJsonObject().deepCopy();v.addProperty("attachedvm","vm-a");v.addProperty("state","Ready");volumes.put(v.get("uuid").getAsString(),v);}}
        void call(String name){calls.add(name);if(name.equals(fail))throw new SharedFSDeletionCoordinator.Failure("TEST_CONTROLLED_FAILURE");}
        public void validate(JsonObject p){if("validate".equals(fail))throw new SharedFSDeletionCoordinator.Failure("TEST_CONTROLLED_REJECTION");}
        public void stopAndConfirm(JsonObject p){call("stop");}
        public void softDestroy(JsonObject p){call("soft");}
        public JsonObject observeVolume(JsonObject v){return volumes.get(v.get("uuid").getAsString()).deepCopy();}
        public void detach(JsonObject p,JsonObject v){String id=v.get("uuid").getAsString();call("detach:"+id);if(!noDetach)volumes.get(id).remove("attachedvm");}
        public boolean vmRemoved(JsonObject p){return removed;}
        public void removeRootOnlyVm(JsonObject p){call("root");removed=true;}
        public void deleteSelected(JsonObject v){String id=v.get("uuid").getAsString();call("delete:"+id);volumes.get(id).addProperty("removed",true);}
        public void cleanupMetadata(JsonObject p){call("metadata");}
    }
}
