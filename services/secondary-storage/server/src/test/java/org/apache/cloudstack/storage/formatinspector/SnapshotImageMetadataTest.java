// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to You under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package org.apache.cloudstack.storage.formatinspector;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import com.cloud.storage.Storage.ImageFormat;

public class SnapshotImageMetadataTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final long SIZE = 1073741824L;

    private File qcow(int version) throws IOException {
        File file = folder.newFile("backup-" + version + ".raw");
        try (RandomAccessFile header = new RandomAccessFile(file, "rw")) {
            header.setLength(1376256);
            header.writeInt(0x514649fb);
            header.writeInt(version);
            header.seek(20); header.writeInt(16); header.writeLong(SIZE);
        }
        return file;
    }



    @Test public void qcowV3IgnoresMisleadingRawExtension() throws Exception {
        SnapshotImageMetadata meta = SnapshotImageMetadata.inspect(qcow(3), SIZE);
        assertEquals(ImageFormat.QCOW2, meta.getFormat());
        assertEquals(SIZE, meta.getVirtualSize());
        assertEquals(1376256, meta.getFileSize());
        assertEquals(SIZE, meta.toFormatInfo("template.qcow2").virtualSize);
    }
    @Test public void qcowV2WorksWithoutSourceVolume() throws Exception {
        assertEquals(SIZE, SnapshotImageMetadata.inspect(qcow(2), null).getVirtualSize());
    }
    @Test public void rawRequiresSnapshotSizeAndPreservesLogicalSize() throws Exception {
        File raw = folder.newFile();
        try (RandomAccessFile f = new RandomAccessFile(raw, "rw")) { f.setLength(SIZE); }
        SnapshotImageMetadata meta = SnapshotImageMetadata.inspect(raw, SIZE);
        assertEquals(ImageFormat.RAW, meta.getFormat()); assertEquals(SIZE, meta.getFileSize());
    }
    @Test(expected = IOException.class) public void rawDoesNotDefaultWithoutMetadata() throws Exception {
        File raw = folder.newFile(); try (RandomAccessFile h = new RandomAccessFile(raw, "rw")) { h.setLength(512); }
        SnapshotImageMetadata.inspect(raw, null);
    }
    @Test(expected = IOException.class) public void rawRejectsTruncatedBackup() throws Exception {
        File raw = folder.newFile(); try (RandomAccessFile h = new RandomAccessFile(raw, "rw")) { h.setLength(512); }
        SnapshotImageMetadata.inspect(raw, SIZE);
    }
    @Test(expected = IOException.class) public void rejectVirtualSizeMismatch() throws Exception {
        SnapshotImageMetadata.inspect(qcow(3), SIZE * 2);
    }
    @Test(expected = IOException.class) public void rejectUnsupportedFormat() throws Exception {
        File f = folder.newFile(); try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.setLength(SIZE); h.writeBytes("vhdxfile"); }
        SnapshotImageMetadata.inspect(f, SIZE);
    }
    @Test(expected = IOException.class) public void rejectEmptyFile() throws Exception {
        SnapshotImageMetadata.inspect(folder.newFile(), SIZE);
    }
    @Test(expected = IOException.class) public void rejectHeaderSizeMismatch() throws Exception {
        File f = qcow(3); try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.seek(24); h.writeLong(SIZE * 2); }
        SnapshotImageMetadata.inspect(f, SIZE);
    }
    @Test(expected = IOException.class) public void rejectWrongHeader() throws Exception {
        File f = qcow(3); try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.writeInt(0); }
        SnapshotImageMetadata.inspect(f, SIZE);
    }
    @Test(expected = IOException.class) public void rejectUnknownVersion() throws Exception {
        SnapshotImageMetadata.inspect(qcow(4), SIZE);
    }
    @Test(expected = IOException.class) public void rejectBackingReference() throws Exception {
        File f = qcow(3); try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.seek(8); h.writeLong(104); }
        SnapshotImageMetadata.inspect(f, SIZE);
    }
    @Test(expected = IOException.class) public void rejectExternalData() throws Exception {
        File f = qcow(3); try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.seek(72); h.writeLong(4); }
        SnapshotImageMetadata.inspect(f, SIZE);
    }
    @Test(expected = IOException.class) public void rejectEncrypted() throws Exception {
        File f = qcow(3); try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.seek(32); h.writeInt(1); }
        SnapshotImageMetadata.inspect(f, SIZE);
    }
    @Test(expected = IOException.class) public void rejectMissingFile() throws Exception {
        SnapshotImageMetadata.inspect(new File(folder.getRoot(), "missing"), SIZE);
    }
    @Test public void truncatedQcowHeaderHasActionableReason() throws Exception {
        File f = folder.newFile();
        try (RandomAccessFile h = new RandomAccessFile(f, "rw")) { h.writeInt(0x514649fb); h.writeInt(3); }
        try {
            SnapshotImageMetadata.inspect(f, SIZE);
            org.junit.Assert.fail("Expected rejection");
        } catch (IOException e) {
            org.junit.Assert.assertTrue(e.getMessage().contains("Truncated snapshot backup"));
        }
    }
}
