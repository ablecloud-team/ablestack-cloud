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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

public class StorageConfigArchiveTest {
    private byte[] raw(String... names) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name));zip.write("{}".getBytes(StandardCharsets.UTF_8));zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private Map<String, byte[]> entries(String source) {
        Map<String, byte[]> entries = new LinkedHashMap<>();entries.put("desired/instance.json", source.replace((char) 39, (char) 34).getBytes(StandardCharsets.UTF_8));return entries;
    }
    @Test public void roundTripUsesOnlyVerifiedStructuredEntries() {
        byte[] archive = StorageConfigArchive.create(entries("{'uuid':'source','credentialCoverage':'REQUIRES_REENTRY'}".replace((char) 39, (char) 34)), new JsonObject());
        Map<String, byte[]> restored = StorageConfigArchive.validate(archive);
        Assert.assertEquals(3, restored.size());Assert.assertEquals(64, StorageConfigArchive.sha256(archive).length());
    }
    @Test public void traversalAbsoluteAndUnknownArchiveEntriesAreRejectedWithoutExtraction() throws Exception {
        for (String path : new String[] {"../desired/instance.json", "/desired/instance.json", "desired/../instance.json", "rendered/start.sh",
                "etc/krb5.keytab", "var/lib/samba/private/passdb.tdb"}) {
            byte[] archive = raw(path);
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.validate(archive));
        }
    }
    @Test public void plainPasswordsPrivateKeysAndAuthenticationHashesCannotBeExported() {
        for (String source : new String[] {"{'password':'plain'}", "{'nested':{'nthash':'hash'}}",
                "{'value':'-----BEGIN PRIVATE KEY-----'}", "{'dhChapKey':'DHHC-1:foo'}"}) {
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.create(entries(source), new JsonObject()));
        }
    }
    @Test public void excessiveJsonDepthAndOversizedArchiveCannotBeImported() {
        String deep = "[".repeat(33) + "0" + "]".repeat(33);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.create(entries(deep), new JsonObject()));
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.validate(new byte[StorageConfigArchive.MAX_ARCHIVE_BYTES + 1]));
    }
    @Test public void malformedJsonAndMissingManifestCannotBeImported() throws Exception {
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.create(entries("{broken"), new JsonObject()));
        byte[] missing = raw("desired/instance.json");Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.validate(missing));
    }
    @Test public void changedEntryCannotPassOriginalChecksum() throws Exception {
        byte[] archive = StorageConfigArchive.create(entries("{'uuid':'source'}"), new JsonObject());
        Map<String, byte[]> valid = StorageConfigArchive.validate(archive);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : valid.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write("desired/instance.json".equals(entry.getKey()) ? "{'uuid':'changed'}".replace((char) 39, (char) 34).getBytes(StandardCharsets.UTF_8) : entry.getValue());zip.closeEntry();
            }
        }
        byte[] changed = bytes.toByteArray();Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigArchive.validate(changed));
    }
}
