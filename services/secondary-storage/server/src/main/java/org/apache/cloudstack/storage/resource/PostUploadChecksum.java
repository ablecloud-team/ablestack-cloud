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

package org.apache.cloudstack.storage.resource;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;

import org.apache.cloudstack.storage.template.UploadEntity;
import org.apache.cloudstack.utils.security.ChecksumValue;
import org.apache.cloudstack.utils.security.DigestHelper;
import com.cloud.utils.script.Script;

/** Preserve the legacy volume script ABI; verify template bytes before moving them. */
final class PostUploadChecksum {
    private PostUploadChecksum() { }

    static void addChecksumArgument(Script script, UploadEntity entity, String checksum) {
        if (entity.getResourceType() != UploadEntity.ResourceType.TEMPLATE) {
            script.add("-c", checksum);
        }
    }

    static String execute(Script script, UploadEntity entity, String filename) {
        String checksum = entity.getChksum();
        if (entity.getResourceType() == UploadEntity.ResourceType.TEMPLATE && checksum != null && !checksum.trim().isEmpty()) {
            ChecksumValue expected = new ChecksumValue(checksum);
            try (InputStream input = new FileInputStream(filename)) {
                ChecksumValue actual = DigestHelper.digest(expected.getAlgorithm(), input);
                if (!actual.getChecksum().equalsIgnoreCase(expected.getChecksum())) {
                    return "Checksum does not match the uploaded file.";
                }
            } catch (NoSuchAlgorithmException e) {
                return "Unsupported checksum algorithm.";
            } catch (IOException e) {
                return "Unable to verify uploaded file checksum.";
            }
        }
        return script.execute();
    }
}
