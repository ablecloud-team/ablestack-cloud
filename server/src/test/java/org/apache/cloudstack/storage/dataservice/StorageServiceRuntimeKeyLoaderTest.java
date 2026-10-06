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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageServiceRuntimeKeyLoaderTest {
    private StorageServiceRuntimeUpgradeManagerImpl manager(Path directory) {
        return new StorageServiceRuntimeUpgradeManagerImpl() {
            @Override protected Path trustedKeyDirectory() { return directory; }
            @Override protected byte[] resource(String path) { return ("classpath:" + path).getBytes(StandardCharsets.US_ASCII); }
        };
    }

    @Test
    public void acceptsOperatorManagedPublicKeyAndUsesClasspathWhenAbsent() throws Exception {
        Path directory = Files.createTempDirectory("runtime-public-key");
        Path key = directory.resolve("release.pem");
        byte[] publicKey = "-----BEGIN PUBLIC KEY-----\npublic-test-fixture\n-----END PUBLIC KEY-----\n".getBytes(StandardCharsets.US_ASCII);
        try {
            Files.write(key, publicKey);
            Assert.assertArrayEquals(publicKey, manager(directory).trustedKey("release"));
            Assert.assertTrue(new String(manager(directory).trustedKey("other"), StandardCharsets.US_ASCII).startsWith("classpath:"));
        } finally { Files.deleteIfExists(key); Files.deleteIfExists(directory); }
    }

    @Test
    public void rejectsPrivateMaterialLinksAndPathTraversal() throws Exception {
        Path directory = Files.createTempDirectory("runtime-public-key");
        Path privateFixture = directory.resolve("private.pem");
        Path link = directory.resolve("link.pem");
        try {
            Files.write(privateFixture, "-----BEGIN PRIVATE KEY-----\ninvalid-test-fixture\n".getBytes(StandardCharsets.US_ASCII));
            Files.createSymbolicLink(link, privateFixture);
            Assert.assertThrows(CloudRuntimeException.class, () -> manager(directory).trustedKey("private"));
            Assert.assertThrows(CloudRuntimeException.class, () -> manager(directory).trustedKey("link"));
            Assert.assertThrows(IllegalArgumentException.class, () -> manager(directory).trustedKey("../private"));
        } finally { Files.deleteIfExists(link); Files.deleteIfExists(privateFixture); Files.deleteIfExists(directory); }
    }
}
