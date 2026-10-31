// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package com.cloud.hypervisor.kvm.storage;

import com.ceph.rbd.RbdImage;
import com.ceph.rbd.RbdException;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RbdVmFullCloneTest {
    @Test public void fullCopiesFlattenBeforeSuccessButLinkedAndOrdinaryRestoresKeepTheirParent() throws Exception {
        KVMStorageProcessor processor=new KVMStorageProcessor(mock(KVMStoragePoolManager.class),mock(LibvirtComputingResource.class));
        RbdImage image=mock(RbdImage.class);
        processor.finishRbdVmClone(image,"linked"); processor.finishRbdVmClone(image,null); verifyNoInteractions(image);
        processor.finishRbdVmClone(image,"full"); verify(image).flatten();
    }
    @Test public void failedFlattenCannotReturnSuccessfulFullCopy() throws Exception {
        KVMStorageProcessor processor=new KVMStorageProcessor(mock(KVMStoragePoolManager.class),mock(LibvirtComputingResource.class));
        RbdImage image=mock(RbdImage.class); RbdException failure=mock(RbdException.class); doThrow(failure).when(image).flatten();
        try { processor.finishRbdVmClone(image,"full"); fail("flatten failure was hidden"); } catch(RbdException expected) { assertSame(failure,expected); }
    }
}
