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

public class SharedFSCapacityProjectionTest {
    @Test public void deduplicatesOneVolumeSharedByMultipleProtocols() {
        SharedFSCapacityProjection.Capacity c = new SharedFSCapacityProjection.Capacity();
        c.add(1,100L,"Ready"); c.add(1,100L,"Ready"); c.add(2,200L,"Ready");
        Assert.assertEquals(Long.valueOf(300),c.getTotal());
        Assert.assertEquals(2,c.getCount());
        Assert.assertEquals("PROVISIONED_USAGE_UNOBSERVED",c.getState());
    }
    @Test public void unknownSizeIsNotPresentedAsZeroOrACompleteTotal() {
        SharedFSCapacityProjection.Capacity c = new SharedFSCapacityProjection.Capacity();
        c.add(1,null,"Ready"); c.add(2,100L,"Ready");
        Assert.assertNull(c.getTotal()); Assert.assertEquals("UNAVAILABLE",c.getState());
    }
    @Test public void transitionsAreReportedWithoutInventingRuntimeUsage() {
        SharedFSCapacityProjection.Capacity c = new SharedFSCapacityProjection.Capacity();
        c.add(1,100L,"Creating");
        Assert.assertEquals(Long.valueOf(100),c.getTotal()); Assert.assertEquals("TRANSITIONING",c.getState());
    }
}
