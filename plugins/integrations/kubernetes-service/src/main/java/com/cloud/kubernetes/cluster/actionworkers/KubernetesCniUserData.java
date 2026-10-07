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

package com.cloud.kubernetes.cluster.actionworkers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Keeps cloud-config lists separate until cloud-init renders and merges them. */
final class KubernetesCniUserData {
    private KubernetesCniUserData() { }

    private static String contentType(String data) {
        String normalized = data.replace("\r\n", "\n").stripLeading();
        if (normalized.startsWith("## template: jinja\n#cloud-config\n")) {
            return "text/jinja2";
        }
        return normalized.startsWith("#cloud-config\n") ? "text/cloud-config" : null;
    }

    static String mergeCloudConfig(String encodedBase, String encodedCni) {
        String base = new String(Base64.getDecoder().decode(encodedBase), StandardCharsets.UTF_8);
        String cni = new String(Base64.getDecoder().decode(encodedCni), StandardCharsets.UTF_8);
        String baseType = contentType(base);
        String cniType = contentType(cni);
        if (baseType == null || cniType == null) {
            return null; // Existing provider handles shell scripts and other supported formats.
        }
        String boundary = "mold-kubernetes-cni-" + UUID.randomUUID();
        String mime = "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\nMIME-Version: 1.0\r\n\r\n"
                + part(boundary, baseType, base, false) + part(boundary, cniType, cni, true)
                + "--" + boundary + "--\r\n";
        return Base64.getEncoder().encodeToString(mime.getBytes(StandardCharsets.UTF_8));
    }

    private static String part(String boundary, String type, String payload, boolean append) {
        return "--" + boundary + "\r\nContent-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + (append ? "Merge-Type: list(append)+dict(no_replace,recurse_list)\r\n" : "")
                + "\r\n" + Base64.getMimeEncoder(76, new byte[] {13, 10})
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "\r\n";
    }
}
