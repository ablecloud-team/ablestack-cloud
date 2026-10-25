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
package org.apache.cloudstack.storage.resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.template.Processor.FormatInfo;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.formatinspector.SnapshotImageMetadata;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;

public class SnapshotTemplateCopyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private NfsSecondaryStorageResource resource;
    private SnapshotObjectTO snapshot;
    private TemplateObjectTO template;
    private NfsTO store;
    private File source;
    private File destination;
    private SnapshotImageMetadata metadata;

    @Before public void setup() throws Exception {
        resource = spy(new NfsSecondaryStorageResource());
        source = folder.newFile("snapshot backup"); Files.write(source.toPath(), new byte[4096]);
        destination = folder.newFolder("template");
        StorageLayer storage = mock(StorageLayer.class);
        when(storage.getFile(anyString())).thenAnswer(i -> new File((String)i.getArgument(0)));
        ReflectionTestUtils.setField(resource, "_storage", storage);
        snapshot = new SnapshotObjectTO(); snapshot.setHypervisorType(HypervisorType.KVM);
        snapshot.setPath("source"); snapshot.setSize(1073741824L);
        template = new TemplateObjectTO(); template.setPath("template/tmpl/2/99"); template.setId(99); template.setName("target");
        store = mock(NfsTO.class); when(store.getUrl()).thenReturn("nfs://server/share");
        doReturn(source).when(resource).getSnapshotImageFile(eq("source"), anyString());
        doReturn(destination).when(resource).getFile(eq(template.getPath()), anyString(), any());
        metadata = mock(SnapshotImageMetadata.class);
        when(metadata.getFormat()).thenReturn(ImageFormat.QCOW2);
        when(metadata.getVirtualSize()).thenReturn(1073741824L);
        when(metadata.getFileSize()).thenReturn(4096L);
        when(metadata.toFormatInfo(anyString())).thenAnswer(i -> {
            FormatInfo f = new FormatInfo(); f.format = ImageFormat.QCOW2; f.filename = i.getArgument(0); f.size = 4096; f.virtualSize = 1073741824L; return f;
        });
        doReturn(metadata).when(resource).inspectSnapshotImage(any(File.class), any(Long.class));
        doAnswer(i -> { Files.copy(((File)i.getArgument(0)).toPath(), ((File)i.getArgument(1)).toPath()); return null; })
                .when(resource).copySnapshotImage(any(File.class), any(File.class));
    }
    private Answer copy() { return resource.copySnapshotToTemplateFromNfsToNfs(mock(CopyCommand.class), snapshot, store, template, store); }

    @Test public void rbdSourceFormatCannotOverrideBackupFormat() throws Exception {
        VolumeObjectTO volume = new VolumeObjectTO(); volume.setFormat(ImageFormat.RAW); snapshot.setVolume(volume);
        CopyCmdAnswer answer = (CopyCmdAnswer)copy(); assertTrue(answer.getDetails(), answer.getResult());
        TemplateObjectTO result = (TemplateObjectTO)answer.getNewData();
        assertEquals(ImageFormat.QCOW2, result.getFormat()); assertEquals(Long.valueOf(1073741824L), result.getSize());
        assertEquals(Long.valueOf(4096L), result.getPhysicalSize()); assertTrue(result.getPath().endsWith(".qcow2"));
        Properties props = new Properties(); try (java.io.InputStream in = Files.newInputStream(new File(destination, "template.properties").toPath())) { props.load(in); }
        assertEquals("1073741824", props.getProperty("virtualsize")); assertEquals("4096", props.getProperty("size"));
        assertEquals("1073741824", props.getProperty("qcow2.virtualsize"));
    }
    @Test public void sourceVolumeIsOptional() { assertTrue(copy().getResult()); }
    @Test public void missingSourceLookupDoesNotCreateDirectory() throws Exception {
        doReturn(folder.getRoot().getAbsolutePath()).when(resource).getRootDir(anyString(), any());
        File missing = resource.getSnapshotImageFile("missing-backup", "nfs://server/share");
        assertFalse(missing.exists());
    }
    @Test public void inspectionFailureDoesNotCopyOrRemoveSource() throws Exception {
        doThrow(new IOException("unsupported image")).when(resource).inspectSnapshotImage(source, snapshot.getSize());
        Answer result = copy(); assertFalse(result.getResult()); assertTrue(result.getDetails().contains("unsupported image"));
        verify(resource, never()).copySnapshotImage(any(), any()); assertTrue(source.exists());
    }
    @Test public void partialCopyFailureDoesNotBecomeReady() throws Exception {
        doAnswer(i -> { Files.write(((File)i.getArgument(1)).toPath(), new byte[2]); throw new IOException("no space"); })
                .when(resource).copySnapshotImage(any(), any());
        Answer result = copy(); assertFalse(result.getResult()); assertTrue(result.getDetails().contains("no space"));
        assertTrue(source.exists()); assertFalse(destination.exists());
    }
    @Test public void copiedMetadataMismatchIsRejected() throws Exception {
        SnapshotImageMetadata changed = mock(SnapshotImageMetadata.class); when(changed.getFormat()).thenReturn(ImageFormat.RAW);
        doReturn(changed).when(resource).inspectSnapshotImage(new File(destination, source.getName()+".qcow2"), snapshot.getSize());
        assertFalse(copy().getResult()); assertTrue(source.exists()); assertFalse(destination.exists());
    }
}
