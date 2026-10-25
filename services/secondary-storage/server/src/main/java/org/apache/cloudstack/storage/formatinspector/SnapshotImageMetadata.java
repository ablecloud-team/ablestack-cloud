// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.formatinspector;

import java.io.File;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.template.Processor.FormatInfo;

/** Verified metadata for a standalone KVM snapshot backup. The source volume format and filename
 * are not evidence of the backup format. Shared by snapshot template and export paths. */
public final class SnapshotImageMetadata {
    private final ImageFormat format;
    private final long virtualSize;
    private final long fileSize;

    private SnapshotImageMetadata(ImageFormat format, long virtualSize, long fileSize) {
        this.format = format;
        this.virtualSize = virtualSize;
        this.fileSize = fileSize;
    }
    public ImageFormat getFormat() { return format; }
    public long getVirtualSize() { return virtualSize; }
    // Transferable physical size is file length, distinct from allocated filesystem blocks.
    public long getFileSize() { return fileSize; }
    public FormatInfo toFormatInfo(String filename) {
        FormatInfo info = new FormatInfo();
        info.format = format; info.filename = filename; info.virtualSize = virtualSize; info.size = fileSize;
        return info;
    }

    public static SnapshotImageMetadata inspect(File file, Long expectedVirtualSize) throws IOException {
        if (!file.isFile() || !file.canRead() || file.length() == 0) {
            throw new IOException("Snapshot backup is missing, unreadable or empty");
        }
        try (RandomAccessFile header = new RandomAccessFile(file, "r")) {
            int magic = header.readInt();
            if (magic == 0x514649fb) {
                int version = header.readInt();
                if (version != 2 && version != 3) {
                    throw new IOException("Unsupported QCOW2 snapshot version: " + version);
                }
                if (header.readLong() != 0 || header.readInt() != 0) {
                    throw new IOException("Snapshot backup must not contain a backing file");
                }
                int clusterBits = header.readInt();
                long size = header.readLong();
                if (clusterBits < 9 || clusterBits > 21 || size <= 0 || size % 512 != 0 || header.readInt() != 0 ||
                        file.length() < (version == 2 ? 72 : 104)) {
                    throw new IOException("Invalid or encrypted QCOW2 snapshot header");
                }
                if (expectedVirtualSize != null && expectedVirtualSize > 0 && size != expectedVirtualSize) {
                    throw new IOException("Snapshot backup virtual size does not match snapshot metadata");
                }
                if (version == 3) {
                    Qcow2Inspector.validateQcow2File(file.getAbsolutePath());
                }
                return new SnapshotImageMetadata(ImageFormat.QCOW2, size, file.length());
            }
            rejectUnsupportedFormat(header, magic);
            // RAW has no identifying header. Absence of QCOW2 magic is not sufficient evidence:
            // require the independently stored snapshot logical size, even with no source volume.
            if (expectedVirtualSize == null || expectedVirtualSize <= 0 || expectedVirtualSize != file.length() || file.length() % 512 != 0) {
                throw new IOException("Unrecognized snapshot backup: RAW requires matching logical snapshot size and file length");
            }
            return new SnapshotImageMetadata(ImageFormat.RAW, expectedVirtualSize, file.length());
        } catch (EOFException e) {
            throw new IOException("Truncated snapshot backup image header", e);
        } catch (RuntimeException e) {
            throw new IOException("Invalid snapshot backup image metadata: " + e.getMessage(), e);
        }
    }

    private static void rejectUnsupportedFormat(RandomAccessFile file, int magic) throws IOException {
        file.seek(0);
        byte[] prefix = new byte[(int)Math.min(file.length(), 512)]; file.readFully(prefix);
        String text = new String(prefix, StandardCharsets.ISO_8859_1);
        if (magic == 0x51454400 || magic == 0x4b444d56 || magic == 0x434f5744 || text.startsWith("vhdxfile") ||
                text.startsWith("# Disk DescriptorFile") || text.startsWith("<<< Oracle VM VirtualBox") ||
                text.startsWith("<<< Sun VirtualBox") || text.startsWith("KDMV") || text.startsWith("conectix")) {
            throw new IOException("Unsupported snapshot backup image format; only standalone QCOW2 and verified RAW are supported");
        }
        if (file.length() >= 512) {
            file.seek(file.length() - 512); byte[] footer = new byte[8]; file.readFully(footer);
            if ("conectix".equals(new String(footer, StandardCharsets.US_ASCII))) {
                throw new IOException("Unsupported VHD snapshot backup format");
            }
        }
    }
}
