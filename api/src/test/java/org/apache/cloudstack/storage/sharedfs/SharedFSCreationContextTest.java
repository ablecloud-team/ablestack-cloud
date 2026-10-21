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
package org.apache.cloudstack.storage.sharedfs;

import java.util.Map;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SharedFSCreationContextTest {
    private CreateSharedFSCmd cmd(boolean existing, String fs) {
        CreateSharedFSCmd cmd = mock(CreateSharedFSCmd.class);
        when(cmd.getCustomComputeParameters()).thenReturn(Map.of());
        when(cmd.getVolumeId()).thenReturn(null); when(cmd.getDiskOfferingId()).thenReturn(null);
        when(cmd.getStorageId()).thenReturn(null); when(cmd.getSize()).thenReturn(null);
        when(cmd.getMinIops()).thenReturn(null); when(cmd.getMaxIops()).thenReturn(null);
        when(cmd.getName()).thenReturn("initial-test");
        when(cmd.getFsFormat()).thenReturn(fs);
        if (existing) when(cmd.getVolumeId()).thenReturn(7L);
        return cmd;
    }
    @Test public void existingAutoRequiresNoNewOffering() {
        SharedFSCreationContext c = SharedFSCreationContext.from(cmd(true, null));
        assertTrue(c.existing); assertEquals("AUTO", c.expectedFilesystem);
        assertEquals("MOUNT_EXISTING", c.importMode); assertNull(c.diskOfferingId);
    }
    @Test public void newExt4KeepsIntent() {
        SharedFSCreationContext c = SharedFSCreationContext.from(cmd(false, "EXT4"));
        assertFalse(c.existing); assertEquals("EXT4", c.expectedFilesystem);
    }
    @Test(expected=InvalidParameterValueException.class) public void newCannotAuto() { SharedFSCreationContext.from(cmd(false, "AUTO")); }
    @Test(expected=InvalidParameterValueException.class) public void existingCannotMixNewSize() {
        CreateSharedFSCmd x=cmd(true,"AUTO");when(x.getSize()).thenReturn(20L);SharedFSCreationContext.from(x);
    }
    @Test(expected=InvalidParameterValueException.class) public void existingCannotChooseFormat() {
        CreateSharedFSCmd x=cmd(true,"EXT4");when(x.getImportMode()).thenReturn("FORMAT_IF_EMPTY");SharedFSCreationContext.from(x);
    }
    @Test public void fingerprintStableAndSensitiveToIntent() {
        CreateSharedFSCmd x=cmd(true,"AUTO");
        String before=SharedFSCreationContext.from(x).fingerprint;
        assertEquals(before,SharedFSCreationContext.from(x).fingerprint);
        when(x.getName()).thenReturn("different");
        assertNotEquals(before,SharedFSCreationContext.from(x).fingerprint);
    }
    @Test public void plannedVmAndContextSurviveRequery() {
        SharedFSCreationContext c=SharedFSCreationContext.from(cmd(true,"AUTO"));c.planVm("fixed-sharedfs-uuid");
        c.phase="ATTACH_PENDING";SharedFSCreationContext read=SharedFSCreationContext.read(c.json());
        assertEquals(c.plannedVmUuid,read.plannedVmUuid);assertEquals("ATTACH_PENDING",read.phase);
        SharedFSCreationContext second=SharedFSCreationContext.from(cmd(true,"AUTO"));second.planVm("fixed-sharedfs-uuid");
        assertEquals(c.plannedVmUuid,second.plannedVmUuid);
        assertFalse(c.json().toLowerCase().contains("password"));
    }
    @Test public void observedExt4AcceptedOnlyWithUuid() {
        SharedFSCreationContext c=SharedFSCreationContext.from(cmd(true,"AUTO"));c.acceptInspection("ext4","actual-fs-uuid");
        assertEquals("ext4",c.observedFilesystem);assertEquals("actual-fs-uuid",c.filesystemUuid);
    }
    @Test(expected=InvalidParameterValueException.class) public void fallbackXfsWithoutUuidIsNotObserved() {
        SharedFSCreationContext.from(cmd(true,"AUTO")).acceptInspection("xfs","");
    }
    @Test(expected=InvalidParameterValueException.class) public void explicitMismatchRejected() {
        SharedFSCreationContext.from(cmd(true,"XFS")).acceptInspection("ext4","uuid");
    }
    @Test(expected=InvalidParameterValueException.class) public void unsupportedFormatRejected() {
        SharedFSCreationContext.from(cmd(true,"AUTO")).acceptInspection("ntfs","uuid");
    }
    @Test public void canonicalRequestKey() {
        assertEquals("a1cc7f04-1334-429f-b7ab-a2b19b50ccef",SharedFSCreationContext.normalizeKey("A1CC7F04-1334-429F-B7AB-A2B19B50CCEF"));
        assertNull(SharedFSCreationContext.normalizeKey(null));
    }
    @Test(expected=InvalidParameterValueException.class) public void malformedRequestKeyRejected() { SharedFSCreationContext.normalizeKey("0-0-0-0-0"); }
}
