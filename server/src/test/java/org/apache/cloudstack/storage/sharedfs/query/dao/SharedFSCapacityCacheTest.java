// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.sharedfs.query.dao;

import org.junit.Assert;
import org.junit.Test;
import com.google.gson.*;

public class SharedFSCapacityCacheTest {
    private static final String UUID="07221486-9900-49ed-b617-9e44b87a0f54";
    private JsonObject snapshot(long epoch) {
        JsonObject capacity=new JsonObject();capacity.addProperty("success",true);capacity.addProperty("generatedEpoch",epoch);capacity.addProperty("staleAfterSeconds",15);
        JsonArray rows=new JsonArray();
        for (String suffix:new String[] {"", "/export/parent", "/export/parent/child"}) {
            JsonObject item=new JsonObject();item.addProperty("target","/srv/ablestack-storage/volumes/"+UUID+suffix);item.addProperty("usedBytes",100);item.addProperty("sizeBytes",1000);rows.add(item);
        }
        capacity.add("capacity",rows);JsonObject result=new JsonObject();result.add("capacitySnapshot",capacity);return result;
    }
    @Test public void sameFilesystemAliasesAreCountedOnceAndOnlyForTheSameVm() {
        long now=System.currentTimeMillis()/1000;
        SharedFSCapacityCache.record(41,snapshot(now));
        SharedFSCapacityProjection.Capacity c=new SharedFSCapacityProjection.Capacity();c.add(43,1000L,"Ready");c.observe(SharedFSCapacityCache.get(41,UUID),now);
        Assert.assertEquals(Long.valueOf(100),c.getUsed());Assert.assertEquals("FRESH",c.getState());Assert.assertNotNull(c.getObservedAt());
        Assert.assertNull(SharedFSCapacityCache.get(42,UUID));SharedFSCapacityCache.invalidate(41);
    }
    @Test public void staleEvidenceIsMarkedAndPartialEvidenceIsNotAServiceWideUsedTotal() {
        long now=System.currentTimeMillis()/1000;
        SharedFSCapacityCache.record(41,snapshot(now-100));
        SharedFSCapacityProjection.Capacity c=new SharedFSCapacityProjection.Capacity();c.add(43,1000L,"Ready");c.observe(SharedFSCapacityCache.get(41,UUID),now);
        Assert.assertEquals("USAGE_STALE",c.getState());
        SharedFSCapacityCache.record(41,snapshot(now));
        c=new SharedFSCapacityProjection.Capacity();c.add(43,1000L,"Ready");c.add(44,500L,"Ready");c.observe(SharedFSCapacityCache.get(41,UUID),now);
        Assert.assertEquals("USAGE_PARTIAL",c.getState());Assert.assertNull(c.getUsed());SharedFSCapacityCache.invalidate(41);
    }
    @Test public void missingObservationIsNotInventedAndExplicitInvalidationRemovesEvidence() {
        SharedFSCapacityCache.record(41,snapshot(System.currentTimeMillis()/1000));SharedFSCapacityCache.invalidate(41);
        Assert.assertNull(SharedFSCapacityCache.get(41,UUID));
        SharedFSCapacityCache.record(41,new JsonObject());Assert.assertNull(SharedFSCapacityCache.get(41,UUID));
    }
}
