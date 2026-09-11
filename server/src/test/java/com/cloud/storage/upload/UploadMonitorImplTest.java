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
package com.cloud.storage.upload;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

public class UploadMonitorImplTest {
    private String copyUrl(boolean ssl, String domain) throws Exception {
        UploadMonitorImpl monitor = new UploadMonitorImpl();
        Field sslField = UploadMonitorImpl.class.getDeclaredField("_sslCopy");
        sslField.setAccessible(true);
        sslField.set(monitor, ssl);
        Field domainField = UploadMonitorImpl.class.getDeclaredField("_ssvmUrlDomain");
        domainField.setAccessible(true);
        domainField.set(monitor, domain);
        Method method = UploadMonitorImpl.class.getDeclaredMethod("generateCopyUrl", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(monitor, "10.11.12.13", "image.iso");
    }

    @Test
    public void httpCopyUsesAddress() throws Exception {
        Assert.assertEquals("http://10.11.12.13/userdata/image.iso", copyUrl(false, "example.com"));
    }

    @Test
    public void httpsCopyPreservesCustomDomain() throws Exception {
        Assert.assertEquals("https://10-11-12-13.example.com/userdata/image.iso", copyUrl(true, "example.com"));
    }

    @Test
    public void httpsWithoutDomainDoesNotUseRetiredDns() throws Exception {
        Assert.assertEquals("https://10.11.12.13/userdata/image.iso", copyUrl(true, null));
        Assert.assertEquals("https://10.11.12.13/userdata/image.iso", copyUrl(true, ""));
    }
}
