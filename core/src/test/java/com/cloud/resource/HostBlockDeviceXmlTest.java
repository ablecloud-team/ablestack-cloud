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
package com.cloud.resource;

import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class HostBlockDeviceXmlTest {
    private final String request = "<disk type='block' device='lun'><source dev='/dev/disk/by-id/test'/><target bus='scsi'/></disk>";
    private final String current = "<domain><devices><disk type='block' device='lun'><source dev='/dev/sdy' index='16'/><target dev='sdf' bus='scsi'/><address type='drive' controller='0' bus='0' target='0' unit='5'/></disk></devices></domain>";
    @Test public void detachPreservesLiveTargetAndMatchesAnAliasByPhysicalSource() throws Exception {
        String xml = HostBlockDeviceXml.findLun(current, request, (a, b) -> a.endsWith("/test") && b.equals("/dev/sdy"));
        assertTrue(xml.contains("dev=\"sdf\""));
        assertTrue(xml.contains("unit=\"5\""));
        assertTrue(xml.contains("dev=\"/dev/sdy\""));
    }
    @Test public void noMatchingDiskIsAnIdempotentDetach() throws Exception {
        assertNull(HostBlockDeviceXml.findLun(current, request, String::equals));
    }
    @Test(expected = IllegalArgumentException.class) public void ambiguousMediaDoNotDetachAnotherTarget() throws Exception {
        HostBlockDeviceXml.findLun(current.replace("</devices>", "<disk type='block' device='lun'><source dev='/dev/sdy'/><target dev='sdg'/></disk></devices>"), request, (a, b) -> true);
    }
    @Test(expected = Exception.class) public void externalEntitiesAreRejected() throws Exception {
        HostBlockDeviceXml.findLun("<!DOCTYPE domain [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><domain>&x;</domain>", request, (a,b) -> true);
    }
}
