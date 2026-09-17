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

import java.util.Map;

import org.libvirt.LibvirtException;

public final class Qcow2MetadataCache {

    static final long L2_ENTRY_BYTES = 8L;
    static final String REFCOUNT_BITS = "refcount_bits";
    static final long DEFAULT_REFCOUNT_BITS = 16L;
    static final String QCOW2_FORMAT = "qcow2";

    private Qcow2MetadataCache() {
    }

    public static long calculateFullSize(final String path, final int timeout) throws LibvirtException, QemuImgException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("QCOW2 image path must not be empty");
        }
        final QemuImg qemuImg = new QemuImg(timeout);
        return calculateFullSize(qemuImg.info(new QemuImgFile(path)));
    }

    public static long calculateFullSize(final Map<String, String> imageInfo) throws QemuImgException {
        if (imageInfo == null) {
            throw new QemuImgException("QCOW2 image information is missing");
        }
        final String format = imageInfo.get(QemuImg.FILE_FORMAT);
        if (!QCOW2_FORMAT.equalsIgnoreCase(format)) {
            throw new QemuImgException(String.format("Expected QCOW2 image format but found [%s]", format));
        }
        final String virtualSize = imageInfo.get(QemuImg.VIRTUAL_SIZE);
        final String clusterSize = imageInfo.get(QemuImg.CLUSTER_SIZE);
        if (virtualSize == null || clusterSize == null) {
            throw new QemuImgException(String.format("QCOW2 image information is incomplete: virtual_size=[%s], cluster_size=[%s]", virtualSize, clusterSize));
        }
        try {
            final String refcountBits = imageInfo.get(REFCOUNT_BITS);
            return calculateFullSize(Long.parseLong(virtualSize), Long.parseLong(clusterSize),
                    refcountBits == null ? DEFAULT_REFCOUNT_BITS : Long.parseLong(refcountBits));
        } catch (RuntimeException e) {
            throw new QemuImgException(String.format("Invalid QCOW2 image information: virtual_size=[%s], cluster_size=[%s], reason=[%s]",
                    virtualSize, clusterSize, e.getMessage()));
        }
    }

    public static long calculateFullSize(final long virtualSize, final long clusterSize) {
        return calculateFullSize(virtualSize, clusterSize, DEFAULT_REFCOUNT_BITS);
    }

    public static long calculateFullSize(final long virtualSize, final long clusterSize, final long refcountBits) {
        if (virtualSize <= 0) {
            throw new IllegalArgumentException("QCOW2 virtual size must be positive");
        }
        if (clusterSize <= 0 || (clusterSize & (clusterSize - 1)) != 0) {
            throw new IllegalArgumentException("QCOW2 cluster size must be a positive power of two");
        }
        if (refcountBits <= 0 || refcountBits > 64 || (refcountBits & (refcountBits - 1)) != 0) {
            throw new IllegalArgumentException("QCOW2 refcount bits must be a power of two between 1 and 64");
        }

        final long guestClusters = divideRoundUp(virtualSize, clusterSize);
        final long l2CacheSize = Math.multiplyExact(guestClusters, L2_ENTRY_BYTES);
        final long refcountCacheBits = Math.multiplyExact(guestClusters, refcountBits);
        final long refcountCacheSize = divideRoundUp(refcountCacheBits, Byte.SIZE);
        return alignUp(Math.addExact(l2CacheSize, refcountCacheSize), clusterSize);
    }

    private static long divideRoundUp(final long dividend, final long divisor) {
        return 1L + ((dividend - 1L) / divisor);
    }

    private static long alignUp(final long value, final long alignment) {
        final long remainder = value % alignment;
        return remainder == 0 ? value : Math.addExact(value, alignment - remainder);
    }
}
