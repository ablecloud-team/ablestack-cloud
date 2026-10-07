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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import javax.mail.BodyPart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KubernetesCniUserDataTest {
    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    @Test
    public void duplicateListsRemainSeparateAndJinjaAndUtf8ArePreserved() throws Exception {
        String base = "## template: jinja\n#cloud-config\nwrite_files:\n - path: /opt/bootstrap\nruncmd:\n - echo {{ ds.meta_data.local_hostname }}\n";
        String cni = "#cloud-config\nwrite_files:\n - path: /opt/cni-profile\n   content: 한글\nruncmd:\n - echo cni\n";
        String merged = KubernetesCniUserData.mergeCloudConfig(encode(base), encode(cni));
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getDecoder().decode(merged)));
        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertEquals(2, parts.getCount());
        BodyPart first = parts.getBodyPart(0);
        BodyPart second = parts.getBodyPart(1);
        assertTrue(first.isMimeType("text/jinja2"));
        assertTrue(second.isMimeType("text/cloud-config"));
        assertEquals(base, new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(cni, new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("list(append)+dict(no_replace,recurse_list)", second.getHeader("Merge-Type")[0]);
    }
    @Test
    public void customJinjaConfigKeepsItsOwnTemplateHandler() throws Exception {
        String base = "#cloud-config\nwrite_files: []\n";
        String cni = "## template: jinja\n#cloud-config\nruncmd:\n - echo {{ v1.instance_id }}\n";
        String merged = KubernetesCniUserData.mergeCloudConfig(encode(base), encode(cni));
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(Base64.getDecoder().decode(merged)));
        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertTrue(parts.getBodyPart(0).isMimeType("text/cloud-config"));
        assertTrue(parts.getBodyPart(1).isMimeType("text/jinja2"));
    }
    @Test
    public void nonCloudConfigUsesTheExistingProvider() {
        assertNull(KubernetesCniUserData.mergeCloudConfig(encode("#cloud-config\nwrite_files: []\n"),
                encode("#!/bin/bash\necho cni\n")));
    }
}
