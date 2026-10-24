/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.storage;

import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
import org.junit.Test;
import org.mockito.Mockito;

public class RbdSnapshotDeletionTest {
    private final KVMStorageProcessor processor = new KVMStorageProcessor(null, null);

    @Test
    public void removesProtectedSnapshotInOrder() throws Exception {
        RbdImage image = Mockito.mock(RbdImage.class);
        Mockito.when(image.snapIsProtected("fixture")).thenReturn(true);
        processor.removeRbdSnapshot(image, "fixture");
        org.mockito.InOrder calls = Mockito.inOrder(image);
        calls.verify(image).snapUnprotect("fixture");
        calls.verify(image).snapRemove("fixture");
    }

    @Test
    public void absentSnapshotIsIdempotent() throws Exception {
        RbdImage image = Mockito.mock(RbdImage.class);
        RbdException absent = Mockito.mock(RbdException.class);
        Mockito.when(absent.getReturnValue()).thenReturn(-2);
        Mockito.doThrow(absent).when(image).snapIsProtected("fixture");
        processor.removeRbdSnapshot(image, "fixture");
        Mockito.verify(image, Mockito.never()).snapRemove(Mockito.anyString());
    }

    @Test(expected = RbdException.class)
    public void busySnapshotDoesNotBecomeSuccess() throws Exception {
        RbdImage image = Mockito.mock(RbdImage.class);
        RbdException busy = Mockito.mock(RbdException.class);
        Mockito.when(busy.getReturnValue()).thenReturn(-16);
        Mockito.doThrow(busy).when(image).snapRemove("fixture");
        processor.removeRbdSnapshot(image, "fixture");
    }

    @Test(expected = RbdException.class)
    public void permissionFailureDoesNotBecomeAbsence() throws Exception {
        RbdImage image = Mockito.mock(RbdImage.class);
        RbdException denied = Mockito.mock(RbdException.class);
        Mockito.when(denied.getReturnValue()).thenReturn(-13);
        Mockito.doThrow(denied).when(image).snapIsProtected("fixture");
        processor.removeRbdSnapshot(image, "fixture");
    }
}
