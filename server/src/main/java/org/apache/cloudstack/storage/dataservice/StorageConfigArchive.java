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
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Bounded configuration archives. No extraction, scripts, raw SQL, or secret state are accepted. */
public final class StorageConfigArchive {
    public static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_EXPANDED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final Set<String> PATHS = Set.of("manifest.json", "desired/instance.json", "desired/protocols.json",
            "desired/file-shares.json", "desired/block-targets.json", "desired/access-rules.json", "desired/identity-domain.json",
            "desired/volumes.json", "desired/posix-directory-policies.json", "runtime/inventory.json", "runtime/health.json",
            "runtime/sessions.json", "identity/ad-source-descriptor.json", "rendered/nfs/exports.json", "rendered/samba/shares.json", "rendered/iscsi/targets.json",
            "rendered/nvmeof/subsystems.json", "system/mounts.json", "system/services.json", "README.md", "SHA256SUMS");
    private StorageConfigArchive() { }

    public static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder value = new StringBuilder();
            for (byte item : hash) value.append(String.format("%02x", item & 255));
            return value.toString();
        } catch (Exception error) { throw new CloudRuntimeException("Configuration checksum is unavailable", error); }
    }
    public static byte[] create(Map<String, byte[]> input, JsonObject metadata) {
        Map<String, byte[]> entries = new LinkedHashMap<>(input);
        if (entries.containsKey("manifest.json") || entries.containsKey("SHA256SUMS")) throw new CloudRuntimeException("Reserved configuration entries");
        JsonObject hashes = new JsonObject();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) hashes.addProperty(entry.getKey(), sha256(entry.getValue()));
        JsonObject manifest = metadata.deepCopy();manifest.addProperty("schemaVersion", 1);manifest.add("checksums", hashes);
        entries.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) sums.append(sha256(entry.getValue())).append("  ").append(entry.getKey()).append((char) 10);
        entries.put("SHA256SUMS", sums.toString().getBytes(StandardCharsets.UTF_8));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();
                }
            }
            byte[] result = bytes.toByteArray();validate(result);return result;
        } catch (java.io.IOException error) { throw new CloudRuntimeException("Unable to create configuration archive", error); }
    }

    public static Map<String, byte[]> validate(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) throw new CloudRuntimeException("Configuration archive size exceeds limit");
        Map<String, Long> directory = validateZipDirectory(archive);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!directory.containsKey(name) || entries.containsKey(name) || entry.isDirectory()) throw new CloudRuntimeException("Configuration ZIP header mismatch");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];int count;
                while ((count = zip.read(buffer)) != -1) {
                    if (data.size() + count > MAX_ENTRY_BYTES || total + count > MAX_EXPANDED_BYTES) throw new CloudRuntimeException("Expanded configuration archive exceeds limit");
                    data.write(buffer, 0, count);total += count;
                }
                zip.closeEntry();
                byte[] bytes = data.toByteArray();
                if (bytes.length != directory.get(name)) throw new CloudRuntimeException("Configuration entry length does not match");
                if (name.endsWith(".json")) validateJson(bytes);
                entries.put(name, bytes);
            }
        } catch (java.io.IOException error) { throw new CloudRuntimeException("Invalid configuration archive", error); }
        if (!entries.keySet().equals(directory.keySet())) throw new CloudRuntimeException("Configuration ZIP entry list mismatch");
        if (!entries.containsKey("manifest.json") || !entries.containsKey("SHA256SUMS") || !entries.containsKey("desired/instance.json")) {
            throw new CloudRuntimeException("Configuration archive omits required entries");
        }
        JsonObject manifest = json(entries.get("manifest.json")).getAsJsonObject();
        if (!manifest.has("schemaVersion") || manifest.get("schemaVersion").getAsInt() != 1 || !manifest.has("checksums")) {
            throw new CloudRuntimeException("Unsupported configuration schema");
        }
        JsonObject hashes = manifest.getAsJsonObject("checksums");
        Set<String> names = new java.util.HashSet<>(entries.keySet());names.remove("manifest.json");names.remove("SHA256SUMS");
        if (!hashes.keySet().equals(names)) throw new CloudRuntimeException("Configuration manifest entry list does not match");
        for (String name : names) if (!sha256(entries.get(name)).equals(hashes.get(name).getAsString())) throw new CloudRuntimeException("Configuration entry checksum mismatch");
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) if (!"SHA256SUMS".equals(entry.getKey())) {
            sums.append(sha256(entry.getValue())).append("  ").append(entry.getKey()).append((char) 10);
        }
        // Checksum rows are compared as a set, so archive order is not an apply input.
        Set<String> expected = new java.util.HashSet<>(java.util.Arrays.asList(sums.toString().trim().split("\n")));
        Set<String> actual = new java.util.HashSet<>(java.util.Arrays.asList(new String(entries.get("SHA256SUMS"), StandardCharsets.UTF_8).trim().split("\n")));
        if (!expected.equals(actual) || actual.size() != entries.size() - 1) throw new CloudRuntimeException("Configuration SHA256SUMS does not match");
        return Collections.unmodifiableMap(entries);
    }
    /** Inspect central-directory Unix attributes before JDK streaming; no archive paths are written to disk. */
    private static Map<String, Long> validateZipDirectory(byte[] bytes) {
        ByteBuffer zip = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int pos = bytes.length - 22; pos >= Math.max(0, bytes.length - 65557); pos--) {
            if (zip.getInt(pos) == 0x06054b50 && pos + 22 + Short.toUnsignedInt(zip.getShort(pos + 20)) == bytes.length) { end = pos;break; }
        }
        if (end < 0 || zip.getShort(end + 4) != 0 || zip.getShort(end + 6) != 0) throw new CloudRuntimeException("Unsupported multi-disk or incomplete ZIP");
        int count = Short.toUnsignedInt(zip.getShort(end + 10));
        long size = Integer.toUnsignedLong(zip.getInt(end + 12));long offset = Integer.toUnsignedLong(zip.getInt(end + 16));
        if (count == 0 || count > PATHS.size() || count != Short.toUnsignedInt(zip.getShort(end + 8))
                || offset + size != end || offset > bytes.length) throw new CloudRuntimeException("Invalid configuration ZIP directory");
        Map<String, Long> entries = new LinkedHashMap<>();
        int pos = (int) offset;
        for (int item = 0; item < count; item++) {
            if (pos + 46 > end || zip.getInt(pos) != 0x02014b50) throw new CloudRuntimeException("Invalid configuration ZIP directory entry");
            int flags = Short.toUnsignedInt(zip.getShort(pos + 8));int method = Short.toUnsignedInt(zip.getShort(pos + 10));
            int nameSize = Short.toUnsignedInt(zip.getShort(pos + 28));int extra = Short.toUnsignedInt(zip.getShort(pos + 30));
            int comment = Short.toUnsignedInt(zip.getShort(pos + 32));int disk = Short.toUnsignedInt(zip.getShort(pos + 34));
            long length = Integer.toUnsignedLong(zip.getInt(pos + 24));long compressed = Integer.toUnsignedLong(zip.getInt(pos + 20));
            long localOffset = Integer.toUnsignedLong(zip.getInt(pos + 42));int unixMode = (zip.getInt(pos + 38) >>> 16) & 0170000;
            int next = pos + 46 + nameSize + extra + comment;
            if (next > end || nameSize == 0 || nameSize > 256 || disk != 0 || length > MAX_ENTRY_BYTES
                    || compressed > MAX_ARCHIVE_BYTES || localOffset >= offset || (flags & 1) != 0
                    || (method != 0 && method != 8) || (unixMode != 0 && unixMode != 0100000)) {
                throw new CloudRuntimeException("Unsafe configuration ZIP entry");
            }
            String name = new String(bytes, pos + 46, nameSize, StandardCharsets.UTF_8);
            if (!PATHS.contains(name) || entries.put(name, length) != null) throw new CloudRuntimeException("Unsafe or duplicate configuration ZIP path");
            pos = next;
        }
        if (pos != end) throw new CloudRuntimeException("Unexpected configuration ZIP directory data");
        return entries;
    }
    public static JsonElement json(byte[] bytes) { return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)); }
    private static void validateJson(byte[] bytes) {
        String source = new String(bytes, StandardCharsets.UTF_8);
        int depth = 0;int tokens = 0;
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setLenient(false);
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (++tokens > 100000) throw new CloudRuntimeException("Configuration JSON complexity exceeds limit");
                switch (reader.peek()) {
                    case BEGIN_ARRAY: reader.beginArray();depth++;break;
                    case BEGIN_OBJECT: reader.beginObject();depth++;break;
                    case END_ARRAY: reader.endArray();depth--;break;
                    case END_OBJECT: reader.endObject();depth--;break;
                    case NAME:
                        String name = reader.nextName().toLowerCase(java.util.Locale.ROOT);
                        if (name.contains("password") || name.contains("secret") || Set.of("keytab", "lmhash", "nthash", "dhchapkey", "dhchapctrlkey", "privatekey").contains(name)) {
                            throw new CloudRuntimeException("Configuration archive contains a forbidden secret field");
                        }
                        break;
                    case STRING:
                        String value = reader.nextString();
                        if (value.length() > 512 * 1024 || value.contains("PRIVATE KEY-----") || value.startsWith("DHHC-1:")) {
                            throw new CloudRuntimeException("Configuration archive contains forbidden credential material");
                        }
                        break;
                    case NUMBER: reader.nextString();break;
                    case BOOLEAN: reader.nextBoolean();break;
                    case NULL: reader.nextNull();break;
                    default: throw new CloudRuntimeException("Invalid configuration JSON token");
                }
                if (depth > 32) throw new CloudRuntimeException("Configuration JSON nesting exceeds limit");
            }
        } catch (java.io.IOException error) { throw new CloudRuntimeException("Invalid configuration JSON", error); }
        if (depth != 0) throw new CloudRuntimeException("Incomplete configuration JSON");
    }
}
