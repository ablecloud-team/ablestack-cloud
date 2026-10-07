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

package com.cloud.kubernetes.cluster;

import java.util.Map;
import com.cloud.configuration.Resource;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesCreateResourceLimitsTest {
 private KubernetesClusterManagerImpl manager;
 private Account owner;
 @Before public void setup() {
  manager=new KubernetesClusterManagerImpl();
  manager.resourceLimitService=Mockito.mock(ResourceLimitService.class);
  manager.serviceOfferingDao=Mockito.mock(ServiceOfferingDao.class);
  owner=Mockito.mock(Account.class);
  ServiceOfferingVO compute=Mockito.mock(ServiceOfferingVO.class);
  Mockito.when(compute.getCpu()).thenReturn(4);Mockito.when(compute.getRamSize()).thenReturn(8192);
  Mockito.when(manager.serviceOfferingDao.findById(1L)).thenReturn(compute);
  ServiceOfferingVO etcd=Mockito.mock(ServiceOfferingVO.class);
  Mockito.when(etcd.getCpu()).thenReturn(1);Mockito.when(etcd.getRamSize()).thenReturn(2048);
  Mockito.when(manager.serviceOfferingDao.findById(2L)).thenReturn(etcd);
 }
 @Test public void aggregatesEveryRoleAgainstSameProjectOwner() throws Exception {
  manager.ensureResourceLimitsForCreate(owner,Map.of("ETCD",2L),Map.of("CONTROL",1L,"WORKER",2L,"ETCD",3L),1L,40L);
  Mockito.verify(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.user_vm,6L);
  Mockito.verify(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.cpu,15L);
  Mockito.verify(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.memory,30720L);
  Mockito.verify(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.volume,6L);
  Mockito.verify(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.primary_storage,240L*(1L<<30));
 }
 @Test public void memoryQuotaFailureStopsBeforeVolumeChecks() throws Exception {
  Mockito.doThrow(new CloudRuntimeException("project memory quota exceeded")).when(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.memory,24576L);
  try{manager.ensureResourceLimitsForCreate(owner,Map.of(),Map.of("CONTROL",1L,"WORKER",2L),1L,40L);fail("quota must reject request");}
  catch(CloudRuntimeException e){assertTrue(e.getMessage().contains("3 VMs"));assertTrue(e.getMessage().contains("project memory quota exceeded"));}
  Mockito.verify(manager.resourceLimitService,Mockito.never()).checkResourceLimit(Mockito.eq(owner),Mockito.eq(Resource.ResourceType.volume),Mockito.anyLong());
 }
 @Test public void storageQuotaFailureIsNotIgnored() throws Exception {
  Mockito.doThrow(new CloudRuntimeException("project primary quota exceeded")).when(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.primary_storage,120L*(1L<<30));
  try{manager.ensureResourceLimitsForCreate(owner,Map.of(),Map.of("CONTROL",1L,"WORKER",2L),1L,40L);fail("storage quota must reject request");}
  catch(CloudRuntimeException e){assertTrue(e.getMessage().contains("project primary quota exceeded"));}
 }
 @Test public void unknownTemplateDefaultSizeIsStillCheckedDuringVmAllocation() throws Exception {
  manager.ensureResourceLimitsForCreate(owner,Map.of(),Map.of("CONTROL",1L,"WORKER",2L),1L,null);
  Mockito.verify(manager.resourceLimitService,Mockito.never()).checkResourceLimit(Mockito.eq(owner),Mockito.eq(Resource.ResourceType.primary_storage),Mockito.anyLong());
  Mockito.verify(manager.resourceLimitService).checkResourceLimit(owner,Resource.ResourceType.volume,3L);
 }
 @Test public void overflowCannotWrapIntoAnAllowedQuota() {
  try{manager.ensureResourceLimitsForCreate(owner,Map.of(),Map.of("CONTROL",Long.MAX_VALUE),1L,40L);fail("overflow accepted");}
  catch(InvalidParameterValueException e){assertTrue(e.getMessage().contains("numeric range"));}
  Mockito.verifyNoInteractions(manager.resourceLimitService);
 }
}
