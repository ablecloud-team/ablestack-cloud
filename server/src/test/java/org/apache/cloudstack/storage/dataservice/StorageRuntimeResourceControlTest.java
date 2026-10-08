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
package org.apache.cloudstack.storage.dataservice;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Provider;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
public class StorageRuntimeResourceControlTest {
 private StorageServiceRuntimeUpgradeManagerImpl manager;
 private StorageService service;
 private StorageServiceRuntimeUpgradeVO upgrade;
 private StorageServiceRuntimeUpgradeDao upgrades;
 @Before public void setup() {
  manager=new StorageServiceRuntimeUpgradeManagerImpl();service=mock(StorageService.class);
  upgrade=new StorageServiceRuntimeUpgradeVO();ReflectionTestUtils.setField(upgrade,"id",21L);upgrade.setProgress(70);
  upgrades=mock(StorageServiceRuntimeUpgradeDao.class);when(upgrades.update(anyLong(),any())).thenReturn(true);
  ReflectionTestUtils.setField(manager,"operationControlService",(Provider<StorageService>)()->service);
  ReflectionTestUtils.setField(manager,"upgradeDao",upgrades);
  when(service.beginRuntimeOperationControl(21L,false)).thenReturn("forward");
  when(service.beginRuntimeOperationControl(21L,true)).thenReturn("rollback");
 }
 @Test public void fullImplementationAdvertisesLinkedButLegacyDefaultIsFailClosed() throws Exception {
  Assert.assertTrue(manager.operationControlLinked());
  Assert.assertTrue(StorageServiceRuntimeUpgradeManager.class.getMethod("operationControlLinked").isDefault());
  StorageServiceRuntimeUpgradeManager legacy=mock(StorageServiceRuntimeUpgradeManager.class,CALLS_REAL_METHODS);
  Assert.assertFalse(legacy.operationControlLinked());
 }
 @Test public void acquireOccursOnceAndEveryEffectIncludingAutomaticRollbackRenewsSameScope() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);scope.beforeEffect();scope.beforeEffect();scope.terminal("ROLLED_BACK");
  InOrder order=inOrder(service);order.verify(service).beginRuntimeOperationControl(21L,false);
  order.verify(service,times(2)).verifyManagedOperationControl("forward");order.verify(service).finishManagedOperationControl("forward","ROLLED_BACK");
  verify(service,never()).beginRuntimeOperationControl(21L,true);
 }
 @Test public void explicitRollbackUsesItsDistinctDurableIntent() {
  var scope=manager.beginRuntimeResourceScope(upgrade,true);scope.beforeEffect();scope.terminal("ROLLED_BACK");
  verify(service).verifyManagedOperationControl("rollback");verify(service).finishManagedOperationControl("rollback","ROLLED_BACK");
 }
 @Test public void newOptOutDoesNotInventLeaseVerifyOrRelease() {
  when(service.beginRuntimeOperationControl(21L,false)).thenReturn(null);
  var scope=manager.beginRuntimeResourceScope(upgrade,false);scope.beforeEffect();scope.terminal("COMPLETE");
  verify(service,never()).verifyManagedOperationControl(any());verify(service,never()).finishManagedOperationControl(any(),any());
 }
 @Test public void cancellationBeforeEffectClosesCancelledWithoutRecoveryPromotion() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);
  var error=new StorageOperationCancelledException("cancelled");
  doThrow(error).when(service).verifyManagedOperationControl("forward");
  Assert.assertThrows(StorageOperationCancelledException.class,scope::beforeEffect);scope.failed(error);
  Assert.assertFalse(scope.hasEffects());verify(service).finishManagedOperationControl("forward","CANCELLED");
 }
 @Test public void blockedBoundaryBeforeEffectRemainsBlocked() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);
  var error=new CloudRuntimeException("lease not available");doThrow(error).when(service).verifyManagedOperationControl("forward");
  Assert.assertThrows(CloudRuntimeException.class,scope::beforeEffect);scope.failed(error);
  verify(service).finishManagedOperationControl("forward","BLOCKED");
 }
 @Test public void postEffectFailureRetainsRecoveryAndDoesNotProjectFailedOrCompleted() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);scope.beforeEffect();
  manager.failRuntimeControlled(upgrade,scope,new CloudRuntimeException("lost readback"));
  Assert.assertEquals(StorageServiceRuntimeUpgradeVO.State.MANUAL_RECOVERY,upgrade.getState());
  Assert.assertEquals("RECOVERY_REQUIRED",upgrade.getPhase());Assert.assertNull(upgrade.getCompleted());
  verify(service).finishManagedOperationControl("forward","RECOVERY_REQUIRED");
  verify(service,never()).finishManagedOperationControl("forward","COMPLETE");
 }
 @Test public void releaseFailurePreventsDbSuccessProjectionAndHoldsRecovery() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);scope.beforeEffect();AtomicBoolean projected=new AtomicBoolean(false);
  var error=new CloudRuntimeException("release unverified");doThrow(error).when(service).finishManagedOperationControl("forward","COMPLETE");
  Assert.assertThrows(CloudRuntimeException.class,()->manager.finishRuntimeResourceScope(scope,"COMPLETE",()->{projected.set(true);return true;}));
  Assert.assertFalse(projected.get());manager.failRuntimeControlled(upgrade,scope,error);
  Assert.assertEquals(StorageServiceRuntimeUpgradeVO.State.MANUAL_RECOVERY,upgrade.getState());
  verify(service).finishManagedOperationControl("forward","RECOVERY_REQUIRED");
 }
 @Test public void releaseAndTerminalHappenBeforeSuccessfulDbProjection() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);scope.beforeEffect();
  Assert.assertTrue(manager.finishRuntimeResourceScope(scope,"COMPLETE",()->{verify(service).finishManagedOperationControl("forward","COMPLETE");return true;}));
 }
 @Test public void recoveryCleanupFailureIsSuppressedWithoutFalseFailedTerminal() {
  var scope=manager.beginRuntimeResourceScope(upgrade,false);scope.beforeEffect();var error=new CloudRuntimeException("activation uncertain");
  doThrow(new CloudRuntimeException("recovery observer unavailable")).when(service).finishManagedOperationControl("forward","RECOVERY_REQUIRED");
  manager.failRuntimeControlled(upgrade,scope,error);Assert.assertEquals(1,error.getSuppressed().length);
  Assert.assertEquals(StorageServiceRuntimeUpgradeVO.State.MANUAL_RECOVERY,upgrade.getState());Assert.assertNull(upgrade.getCompleted());
 }
}
