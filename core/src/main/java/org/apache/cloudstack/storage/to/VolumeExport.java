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
package org.apache.cloudstack.storage.to;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;

/** Independent content verification of an export; never evidence of source allocation. */
public final class VolumeExport {
    public static final String SUFFIX = ".export-v1.qcow2";
    private VolumeExport() { }

    public static Map<String, String> receipt(VolumeObjectTO source, String pool, long fileBytes) {
        return Map.of("version", "1", "sourceUuid", source.getUuid(), "sourcePath", source.getPath(),
                "sourcePool", pool, "logicalBytes", source.getSize().toString(), "fileBytes", Long.toString(fileBytes),
                "content", "qemu-img-compare", "format", "qcow2", "verifiedAt", Instant.now().toString());
    }

    public static void validate(VolumeObjectTO source, VolumeObjectTO result) {
        try {
            Map<String, String> v = result.getExportVerification();
            if (result.getFormat() != Storage.ImageFormat.QCOW2 || !Objects.equals(source.getSize(), result.getSize()) ||
                    result.getPath() == null || !result.getPath().endsWith(SUFFIX) || result.getPath().contains("..") ||
                    !result.getAllocation().isEmpty() || !"1".equals(v.get("version")) ||
                    !Objects.equals(source.getUuid(), v.get("sourceUuid")) || !Objects.equals(source.getPath(), v.get("sourcePath")) ||
                    !Objects.equals(((PrimaryDataStoreTO) source.getDataStore()).getUuid(), v.get("sourcePool")) ||
                    !source.getSize().toString().equals(v.get("logicalBytes")) || Long.parseLong(v.get("fileBytes")) <= 0 ||
                    !"qemu-img-compare".equals(v.get("content")) || !"qcow2".equals(v.get("format"))) {
                throw new IllegalArgumentException("Missing or mismatched export verification");
            }
            Instant verified = Instant.parse(v.get("verifiedAt"));
            if (verified.isAfter(Instant.now().plusSeconds(300)) || verified.isBefore(Instant.now().minusSeconds(14400))) {
                throw new IllegalArgumentException("Stale export verification");
            }
        } catch (RuntimeException invalid) {
            throw new CloudRuntimeException("VOLUME_EXPORT_UNVERIFIED: refusing to publish download URL", invalid);
        }
    }
}
