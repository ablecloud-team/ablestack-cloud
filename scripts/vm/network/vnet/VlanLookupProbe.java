// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
public class VlanLookupProbe {
 public static void main(String[] a) throws Exception {
  Class<?> c=Class.forName("com.cloud.hypervisor.kvm.resource.LibvirtComputingResource");
  Object resource=c.getConstructor().newInstance();
  java.lang.reflect.Method m=c.getDeclaredMethod("getBroadcastUriFromBridge",String.class);m.setAccessible(true);
  Object value=m.invoke(resource,a[0]);
  if(!("vlan://"+a[1]).equals(value))throw new AssertionError(a[0]+" returned "+value);
  System.exit(0);
 }
}