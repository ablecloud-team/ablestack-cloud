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
import com.cloud.offering.ServiceOffering;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.*;
public class SharedFSOfferingValidatorTest {
    private ServiceOffering valid() {
        ServiceOffering offering=mock(ServiceOffering.class);
        when(offering.getCpu()).thenReturn(2);when(offering.getRamSize()).thenReturn(4096);
        when(offering.isOfferHA()).thenReturn(true);when(offering.isDynamicScalingEnabled()).thenReturn(true);return offering;
    }
    @Test public void compatibleOnlyWhenEveryEffectiveLayerSupportsOnlineScaling() {
        ServiceOffering offering=valid();
        Assert.assertTrue(SharedFSOfferingValidator.reasons(offering,2,1024,true,true,true).isEmpty());
        Assert.assertEquals(List.of("ZONE_DYNAMIC_SCALE_DISABLED"),SharedFSOfferingValidator.reasons(offering,2,1024,false,true,true));
        Assert.assertEquals(List.of("SCALABLE_SYSTEMVM_TEMPLATE_REQUIRED"),SharedFSOfferingValidator.reasons(offering,2,1024,true,false,true));
        Assert.assertEquals(List.of("ONLINE_RESIZE_HYPERVISOR_REQUIRED"),SharedFSOfferingValidator.reasons(offering,2,1024,true,true,false));
    }
    @Test public void reportsAllOfferingViolationsBeforeAllocation() {
        ServiceOffering offering=valid();when(offering.getCpu()).thenReturn(null);when(offering.getRamSize()).thenReturn(512);
        when(offering.isOfferHA()).thenReturn(false);when(offering.isDynamicScalingEnabled()).thenReturn(false);
        Assert.assertEquals(List.of("FIXED_CPU_REQUIRED","MINIMUM_MEMORY_REQUIRED","HA_REQUIRED","OFFERING_DYNAMIC_SCALE_REQUIRED"),
            SharedFSOfferingValidator.reasons(offering,2,1024,true,true,true));
    }
    @Test public void missingOfferingNeverBecomesCompatible() {
        Assert.assertEquals(List.of("OFFERING_NOT_FOUND"),SharedFSOfferingValidator.reasons(null,2,1024,true,true,true));
    }
}
