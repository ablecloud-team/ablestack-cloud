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
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LibvirtCheckVolumeUseCommandWrapperTest {
    @Test public void identifiesGfs2FileAndRbdImagePathsWithoutSubstringMatches() {
        assertTrue(LibvirtCheckVolumeUseCommandWrapper.matches("/mnt/glue-gfs/aa/root-uuid", "root-uuid"));
        assertTrue(LibvirtCheckVolumeUseCommandWrapper.matches("rbd/root-uuid", "root-uuid"));
        assertTrue(LibvirtCheckVolumeUseCommandWrapper.matches("root-uuid", "rbd/root-uuid"));
        assertFalse(LibvirtCheckVolumeUseCommandWrapper.matches("/mnt/other-root-uuid", "root-uuid"));
        assertFalse(LibvirtCheckVolumeUseCommandWrapper.matches(null, "root-uuid"));
        assertFalse(LibvirtCheckVolumeUseCommandWrapper.matches("/mnt/root-uuid", ""));
    }
}
