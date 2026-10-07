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

package org.apache.cloudstack.storage.dataservice;

import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UploadStorageServiceConfigBackupCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.RestoreStorageServiceLastKnownGoodCmd;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.crypt.EncryptionSecretKeyChecker;

public class StorageConfigQueueProtectionTest {
    @Test public void sensitiveTransfersCannotEnterUnencryptedPersistentQueue() {
        Object previous = ReflectionTestUtils.getField(EncryptionSecretKeyChecker.class, "s_useEncryption");
        try {
            ReflectionTestUtils.setField(EncryptionSecretKeyChecker.class, "s_useEncryption", false);
            for (BaseCmd cmd : new BaseCmd[] {new UploadStorageServiceConfigBackupCmd(), new ApplyStorageServiceConfigRestoreCmd(), new RestoreStorageServiceLastKnownGoodCmd()}) {
                APICommand metadata = cmd.getClass().getAnnotation(APICommand.class);
                Assert.assertTrue(metadata.requestHasSensitiveInfo());Assert.assertTrue(metadata.responseHasSensitiveInfo());
                Assert.assertThrows(InvalidParameterValueException.class, () -> cmd.validateSpecificParameters(Collections.emptyMap()));
            }
            ReflectionTestUtils.setField(EncryptionSecretKeyChecker.class, "s_useEncryption", true);
            new UploadStorageServiceConfigBackupCmd().validateSpecificParameters(Collections.emptyMap());
        } finally { ReflectionTestUtils.setField(EncryptionSecretKeyChecker.class, "s_useEncryption", previous); }
    }
}
