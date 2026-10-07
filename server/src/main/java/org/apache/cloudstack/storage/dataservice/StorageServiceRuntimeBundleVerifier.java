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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;

/** Verifies immutable catalog bytes before approval; shares the #911 manifest contract. */
public final class StorageServiceRuntimeBundleVerifier {
    private static final Set<String> ENTRYPOINTS = new HashSet<>(Arrays.asList(
            "ablestack-storagectl", "ablestack-storage-boot-reconcile", "ablestack-storage-monitor"));
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;

    public JsonObject verify(final StorageServiceRuntimeBundleVO bundle, final byte[] archive,
            final byte[] manifestBytes, final byte[] signatureBytes, final byte[] publicKeyPem) {
        try {
            if (!sha256(archive).equalsIgnoreCase(bundle.getSha256()) ||
                    !sha256(manifestBytes).equalsIgnoreCase(bundle.getManifestSha256()) ||
                    (bundle.getArtifactSize() != null && bundle.getArtifactSize() != archive.length)) {
                throw new IllegalArgumentException("Runtime artifact size or checksum mismatch");
            }
            final String pem = new String(publicKeyPem, StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            final byte[] encoded = Base64.getDecoder().decode(pem);
            final PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifestBytes);
            if (!verifier.verify(signatureBytes)) throw new IllegalArgumentException("Runtime manifest signature is invalid");
            final JsonObject manifest = new JsonParser().parse(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            require(manifest, "bundleVersion", bundle.getVersion());
            require(manifest, "keyId", bundle.getSigningKeyId());
            require(manifest, "runtimeAbiVersion", bundle.getRuntimeAbiVersion());
            require(manifest, "desiredStateSchemaVersion", bundle.getDesiredStateSchemaVersion());
            require(manifest, "serviceImpact", bundle.getServiceImpact().name());
            StorageRuntimeFeatureCompatibility.advertised(manifest);
            final Map<String, String> files = new HashMap<>();
            final JsonArray declared = manifest.getAsJsonArray("files");
            if (declared == null || declared.size() != ENTRYPOINTS.size()) throw new IllegalArgumentException("Invalid runtime file count");
            for (JsonElement value : declared) {
                final JsonObject entry = value.getAsJsonObject();
                final String path = entry.get("path").getAsString();
                final int mode = Integer.parseInt(entry.get("mode").getAsString(), 8);
                if (!ENTRYPOINTS.contains(path) || files.containsKey(path) || (mode & ~0755) != 0 ||
                        (mode & 0100) == 0 || !"root".equals(entry.get("owner").getAsString()) ||
                        !"root".equals(entry.get("group").getAsString())) {
                    throw new IllegalArgumentException("Unsafe runtime manifest file");
                }
                final String hash = entry.get("sha256").getAsString();
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid runtime file checksum");
                files.put(path, hash);
            }
            final Set<String> received = new HashSet<>();
            long expanded = 0;
            try (TarArchiveInputStream input = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(archive)))) {
                TarArchiveEntry entry;
                while ((entry = input.getNextTarEntry()) != null) {
                    final String path = entry.getName();
                    if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink() || !files.containsKey(path) ||
                            !received.add(path) || entry.getSize() < 0 || entry.getSize() > MAX_EXPANDED_BYTES ||
                            ((expanded += entry.getSize()) > MAX_EXPANDED_BYTES)) {
                        throw new IllegalArgumentException("Unsafe or oversized runtime archive entry");
                    }
                    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[8192];
                    int count;
                    long actual = 0;
                    while ((count = input.read(buffer)) >= 0) { digest.update(buffer, 0, count); actual += count; }
                    if (actual != entry.getSize() || !hex(digest.digest()).equals(files.get(path))) {
                        throw new IllegalArgumentException("Runtime file checksum mismatch");
                    }
                }
            }
            if (!received.equals(files.keySet())) throw new IllegalArgumentException("Runtime archive omits a declared file");
            final JsonObject result = new JsonObject();
            result.addProperty("verified", true);
            result.addProperty("keyFingerprint", sha256(encoded));
            result.add("manifest", manifest);
            return result;
        } catch (Exception error) {
            throw new CloudRuntimeException("Runtime catalog verification failed: " + error.getMessage(), error);
        }
    }

    private void require(JsonObject value, String field, String expected) {
        if (!value.has(field) || !expected.equals(value.get(field).getAsString())) {
            throw new IllegalArgumentException("Runtime manifest metadata mismatch: " + field);
        }
    }

    private String sha256(byte[] value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
