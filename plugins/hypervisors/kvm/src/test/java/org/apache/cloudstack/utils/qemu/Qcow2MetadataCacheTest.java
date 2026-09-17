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
package org.apache.cloudstack.utils.qemu;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class Qcow2MetadataCacheTest {

    @Test
    public void calculateFullSizeFor200GiBWith64KiBClusters() {
        Assert.assertEquals(32768000L, Qcow2MetadataCache.calculateFullSize(200L * 1024L * 1024L * 1024L, 64L * 1024L));
    }

    @Test
    public void calculateFullSizeForOneTiBWith64KiBClusters() {
        Assert.assertEquals(167772160L, Qcow2MetadataCache.calculateFullSize(1024L * 1024L * 1024L * 1024L, 64L * 1024L));
    }

    @Test
    public void calculateFullSizeAlignsToClusterSize() {
        Assert.assertEquals(65536L, Qcow2MetadataCache.calculateFullSize(1L, 65536L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void calculateFullSizeRejectsNonPowerOfTwoClusterSize() {
        Qcow2MetadataCache.calculateFullSize(1024L, 1000L);
    }

    @Test(expected = ArithmeticException.class)
    public void calculateFullSizeRejectsOverflow() {
        Qcow2MetadataCache.calculateFullSize(Long.MAX_VALUE, 1L);
    }

    @Test
    public void calculateFullSizeFromQemuImgInformation() throws Exception {
        final Map<String, String> imageInfo = new HashMap<>();
        imageInfo.put(QemuImg.FILE_FORMAT, "qcow2");
        imageInfo.put(QemuImg.VIRTUAL_SIZE, String.valueOf(200L * 1024L * 1024L * 1024L));
        imageInfo.put(QemuImg.CLUSTER_SIZE, String.valueOf(64L * 1024L));

        Assert.assertEquals(32768000L, Qcow2MetadataCache.calculateFullSize(imageInfo));
    }

    @Test
    public void calculateFullSizeUsesRefcountBitsFromQemuImgInformation() throws Exception {
        final Map<String, String> imageInfo = new HashMap<>();
        imageInfo.put(QemuImg.FILE_FORMAT, "qcow2");
        imageInfo.put(QemuImg.VIRTUAL_SIZE, String.valueOf(200L * 1024L * 1024L * 1024L));
        imageInfo.put(QemuImg.CLUSTER_SIZE, String.valueOf(64L * 1024L));
        imageInfo.put(Qcow2MetadataCache.REFCOUNT_BITS, "64");

        Assert.assertEquals(52428800L, Qcow2MetadataCache.calculateFullSize(imageInfo));
    }

    @Test(expected = QemuImgException.class)
    public void calculateFullSizeRejectsNonQcow2Information() throws Exception {
        final Map<String, String> imageInfo = new HashMap<>();
        imageInfo.put(QemuImg.FILE_FORMAT, "raw");
        imageInfo.put(QemuImg.VIRTUAL_SIZE, "1024");
        imageInfo.put(QemuImg.CLUSTER_SIZE, "65536");

        Qcow2MetadataCache.calculateFullSize(imageInfo);
    }
}
