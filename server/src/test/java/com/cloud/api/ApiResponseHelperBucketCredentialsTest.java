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
package com.cloud.api;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.Date;
import com.cloud.storage.BucketVO;
import com.cloud.storage.DataStoreRole;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectStoreCredentialProvider;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.BucketCredentials;
import org.junit.Before;
import org.junit.Test;

public class ApiResponseHelperBucketCredentialsTest {
    private final ApiResponseHelper helper = new ApiResponseHelper();
    private final ObjectStoreVO store = mock(ObjectStoreVO.class);
    private final DataStoreManager manager = mock(DataStoreManager.class);
    private final DataStore dataStore = mock(DataStore.class);
    private final DataStoreDriver driver = mock(DataStoreDriver.class, withSettings().extraInterfaces(ObjectStoreCredentialProvider.class));
    private BucketVO bucket;
    @Before public void setup() throws Exception {
        Field field = ApiResponseHelper.class.getDeclaredField("_dataStoreMgr"); field.setAccessible(true); field.set(helper, manager);
        when(store.getId()).thenReturn(12L); when(store.getProviderName()).thenReturn("Glue");
        when(manager.getDataStore(12L, DataStoreRole.Object)).thenReturn(dataStore); when(dataStore.getDriver()).thenReturn(driver);
        bucket = new BucketVO(77, 1, 12, "bucket", 1, false, false, false, "Private");
        bucket.setAccessKey("stale-access"); bucket.setSecretKey("stale-secret");
    }
    @Test public void glueResponseUsesVerifiedProviderCredential() {
        when(((ObjectStoreCredentialProvider) driver).getBucketCredentials(bucket)).thenReturn(new BucketCredentials("new-access", "new-secret", "Verified", new Date()));
        BucketCredentials result = helper.resolveBucketCredentials(bucket, store);
        assertEquals("new-access", result.getAccessKey()); assertEquals("Verified", result.getState());
        assertFalse(result.toString().contains("new-secret"));
    }
    @Test public void failedProviderNeverFallsBackToLegacyBucketKeys() {
        when(((ObjectStoreCredentialProvider) driver).getBucketCredentials(bucket)).thenThrow(new RuntimeException("private provider data"));
        BucketCredentials result = helper.resolveBucketCredentials(bucket, store);
        assertNull(result.getAccessKey()); assertNull(result.getSecretKey()); assertEquals("NeedsVerification", result.getState());
    }
    @Test public void otherProvidersRetainTheirExistingCredentialContract() {
        when(store.getProviderName()).thenReturn("Cloudian");
        BucketCredentials result = helper.resolveBucketCredentials(bucket, store);
        assertEquals("stale-access", result.getAccessKey()); assertNull(result.getState());
        verifyNoInteractions(manager);
    }
}
