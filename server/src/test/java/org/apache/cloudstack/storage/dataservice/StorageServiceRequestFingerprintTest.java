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
import org.junit.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbAclCmd;
import com.cloud.utils.exception.CloudRuntimeException;
public class StorageServiceRequestFingerprintTest {
    private UpdateStorageSmbAclCmd command(long target,String password) {UpdateStorageSmbAclCmd value=new UpdateStorageSmbAclCmd();ReflectionTestUtils.setField(value,"id",target);ReflectionTestUtils.setField(value,"password",password);return value;}
    @Test public void targetAndSecretIntentAreBoundAndNeitherSecretNorUnkeyedDigestIsStored() {
        String first=StorageServiceRequestFingerprint.of(command(1,"runtime-only-A"),"protected-test-key");Assert.assertEquals(first,StorageServiceRequestFingerprint.of(command(1,"runtime-only-A"),"protected-test-key"));
        Assert.assertNotEquals(first,StorageServiceRequestFingerprint.of(command(2,"runtime-only-A"),"protected-test-key"));Assert.assertNotEquals(first,StorageServiceRequestFingerprint.of(command(1,"runtime-only-B"),"protected-test-key"));Assert.assertFalse(first.contains("runtime-only"));Assert.assertFalse(first.contains("protected-test-key"));Assert.assertTrue(first.startsWith("API_INTENT_HMAC_V1:"));
    }
    @Test public void protectedKeyRotationChangesIntentAndMissingKeyFailsClosed() {Assert.assertNotEquals(StorageServiceRequestFingerprint.of(command(1,"A"),"key-A"),StorageServiceRequestFingerprint.of(command(1,"A"),"key-B"));Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceRequestFingerprint.of(command(1,"A"),null));}
    @Test public void transportRetryFieldsDoNotChangeMutationIntent() {UpdateStorageSmbAclCmd command=command(1,"A");String initial=StorageServiceRequestFingerprint.of(command,"key");ReflectionTestUtils.setField(command,"idempotencyKey","new-transport-key");ReflectionTestUtils.setField(command,"expectedRevision",99L);Assert.assertEquals(initial,StorageServiceRequestFingerprint.of(command,"key"));}
}
