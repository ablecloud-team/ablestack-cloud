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

package org.apache.cloudstack.storage.dataservice.dao;
import org.junit.Assert;
import org.junit.Test;
public class StorageConfigArtifactPromotionTest {
    @Test public void baselineAndIncreasingRevisionAreAccepted() {
        Assert.assertTrue(StorageConfigArtifactDaoImpl.matchesPromotion(null, 0, null, 0, 1));
        Assert.assertTrue(StorageConfigArtifactDaoImpl.matchesPromotion(8L, 34, 8L, 34, 35));
    }
    @Test public void stalePointerCannotReplaceNewerSuccessfulConfiguration() {
        Assert.assertFalse(StorageConfigArtifactDaoImpl.matchesPromotion(8L, 34, 9L, 35, 36));
        Assert.assertFalse(StorageConfigArtifactDaoImpl.matchesPromotion(null, 0, 9L, 35, 36));
        Assert.assertFalse(StorageConfigArtifactDaoImpl.matchesPromotion(8L, 34, 8L, 35, 36));
    }
    @Test public void replayOrOlderRevisionCannotBecomeActive() {
        Assert.assertFalse(StorageConfigArtifactDaoImpl.matchesPromotion(8L, 34, 8L, 34, 34));
        Assert.assertFalse(StorageConfigArtifactDaoImpl.matchesPromotion(8L, 34, 8L, 34, 33));
        Assert.assertFalse(StorageConfigArtifactDaoImpl.matchesPromotion(8L, 34, null, 0, 35));
    }
}
