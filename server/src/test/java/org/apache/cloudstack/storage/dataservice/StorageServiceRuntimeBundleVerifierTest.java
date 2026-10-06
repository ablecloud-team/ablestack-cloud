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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageServiceRuntimeBundleVerifierTest {
    private byte[] archive;
    private byte[] manifest;
    private byte[] signature;
    private byte[] publicKey;
    private StorageServiceRuntimeBundleVO bundle;

    @Before
    public void prepareSignedBundle() throws Exception {
        final JsonObject metadata = new JsonObject();
        metadata.addProperty("bundleVersion", "epic898-test");
        metadata.addProperty("keyId", "test-key");
        metadata.addProperty("runtimeAbiVersion", "1");
        metadata.addProperty("desiredStateSchemaVersion", "1");
        metadata.addProperty("serviceImpact", "NONE");
        final JsonArray files = new JsonArray();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
            for (String name : new String[] {"ablestack-storagectl", "ablestack-storage-boot-reconcile", "ablestack-storage-monitor"}) {
                byte[] content = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);
                JsonObject file = new JsonObject();
                file.addProperty("path", name); file.addProperty("sha256", hash(content)); file.addProperty("mode", "0755");
                file.addProperty("owner", "root"); file.addProperty("group", "root"); files.add(file);
                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setMode(0755); entry.setSize(content.length); tar.putArchiveEntry(entry); tar.write(content); tar.closeArchiveEntry();
            }
        }
        metadata.add("files", files);
        manifest = metadata.toString().getBytes(StandardCharsets.UTF_8);
        archive = bytes.toByteArray();
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKey = ("-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) +
                "\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate()); signer.update(manifest); signature = signer.sign();
        bundle = new StorageServiceRuntimeBundleVO("epic898-test", "1", "1", StorageServiceRuntimeBundleVO.ServiceImpact.NONE,
                "http://test/archive", "http://test/manifest", "http://test/signature", (long) archive.length,
                hash(archive), hash(manifest), "test-key");
    }

    @Test
    public void acceptsValidSignedArchiveAndReturnsVerifiedManifest() {
        Assert.assertTrue(new StorageServiceRuntimeBundleVerifier().verify(bundle, archive, manifest, signature, publicKey).get("verified").getAsBoolean());
    }

    @Test
    public void rejectsTamperedArchive() {
        archive[archive.length - 1] ^= 1;
        Assert.assertThrows(CloudRuntimeException.class, () -> new StorageServiceRuntimeBundleVerifier().verify(bundle, archive, manifest, signature, publicKey));
    }

    @Test
    public void rejectsInvalidSignature() {
        signature[0] ^= 1;
        Assert.assertThrows(CloudRuntimeException.class, () -> new StorageServiceRuntimeBundleVerifier().verify(bundle, archive, manifest, signature, publicKey));
    }

    @Test
    public void rejectsDifferentTrustedKey() throws Exception {
        publicKey = ("-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()) +
                "\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.US_ASCII);
        Assert.assertThrows(CloudRuntimeException.class, () -> new StorageServiceRuntimeBundleVerifier().verify(bundle, archive, manifest, signature, publicKey));
    }

    @Test
    public void newlyRegisteredBundlesAreNotPublished() {
        Assert.assertEquals(StorageServiceRuntimeBundleVO.State.REGISTERED, bundle.getState());
        StorageServiceRuntimeUpgradeManagerImpl manager = new StorageServiceRuntimeUpgradeManagerImpl();
        Assert.assertFalse(manager.catalogTransitionAllowed(StorageServiceRuntimeBundleVO.State.REGISTERED, StorageServiceRuntimeBundleVO.State.AVAILABLE));
        Assert.assertTrue(manager.catalogTransitionAllowed(StorageServiceRuntimeBundleVO.State.REGISTERED, StorageServiceRuntimeBundleVO.State.VERIFIED));
        Assert.assertTrue(manager.catalogTransitionAllowed(StorageServiceRuntimeBundleVO.State.VERIFIED, StorageServiceRuntimeBundleVO.State.AVAILABLE));
        for (StorageServiceRuntimeBundleVO.State state : StorageServiceRuntimeBundleVO.State.values()) {
            Assert.assertFalse(manager.catalogTransitionAllowed(StorageServiceRuntimeBundleVO.State.REVOKED, state));
        }
    }

    private String hash(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
