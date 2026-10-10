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

package org.apache.cloudstack.storage.dataservice;

import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;

public class SmbNetworkPolicyTest {
    @Test public void normalizesNetworksAndDeduplicatesCommaInput() {
        java.util.List<SmbNetworkPolicy.Source> rules=SmbNetworkPolicy.normalize(null,"10.1.1.9/24, 10.1.1.0/24, 10.1.1.9",false);
        Assert.assertEquals(2,rules.size());Assert.assertEquals("10.1.1.0/24",rules.get(0).principal);
        Assert.assertEquals(StorageServiceInstance.PrincipalType.IP_ADDRESS,rules.get(1).type);
    }
    @Test public void ipv6RequiresVerifiedCapabilityAndCanonicalizesNetwork() {
        Assert.assertThrows(InvalidParameterValueException.class,()->SmbNetworkPolicy.normalize(null,"2001:db8::1/64",false));
        Assert.assertEquals("2001:db8::/64",SmbNetworkPolicy.normalize("CIDR","2001:0db8::1/64",true).get(0).principal);
    }
    @Test public void rejectsHostnamesWildcardsInjectionAndInvalidPrefixes() {
        for (String invalid:new String[] {"host.example","*","ALL","10.1.1.9\nhosts deny = ALL","10.1.1.9/33","2001:db8::1/129","10.1.1.9/","10.1.1.9,","999.1.1.1","fe80::1%eth0"}) {
            Assert.assertThrows(invalid,InvalidParameterValueException.class,()->SmbNetworkPolicy.normalize(null,invalid,true));
        }
    }
    @Test public void accountPrincipalTypesCannotBecomeNetworkGrants() {
        Assert.assertThrows(InvalidParameterValueException.class,()->SmbNetworkPolicy.normalize("LOCAL_USER","10.1.1.9",false));
        Assert.assertThrows(InvalidParameterValueException.class,()->SmbNetworkPolicy.normalize("IP_ADDRESS","10.1.1.9/24",false));
    }
}
