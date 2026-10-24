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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
public class StorageConfigurationPreflightTest {
    @Test public void publicIntentIsStrictAndCannotCarrySecretsOrExecuteArbitraryCommands() {
        for (String input : new String[]{"{\"api\":\"deleteVirtualMachine\"}","{\"api\":\"updateStorageNfsExport\",\"password\":\"secret\"}","{\"api\":\"updateStorageNfsExport\",\"recursive\":\"false\"}"}) {
            try { StorageServiceConfigurationPreflight.parseIntent(input); fail("unsafe intent accepted"); }
            catch (InvalidParameterValueException expected) { }
        }
        assertEquals("updateStorageNfsExport",StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageNfsExport\"}").get("api").getAsString());
    }
    @Test public void blockedOrUnknownObservationCannotBeAcknowledgedIntoSuccess() {
        JsonObject report=JsonParser.parseString("{\"status\":\"BLOCKED\",\"checks\":[{\"code\":\"DRAIN_REQUIRED\",\"blocking\":true}],\"fingerprint\":\"same\"}").getAsJsonObject();
        try { StorageServiceConfigurationPreflight.requireReady(report,"same"); fail(); }
        catch (StorageServiceConfigurationPreflight.Rejected expected) { assertSame(report,expected.report); }
        report.addProperty("status","UNKNOWN");
        try { StorageServiceConfigurationPreflight.requireReady(report,"same"); fail(); }
        catch (StorageServiceConfigurationPreflight.Rejected expected) { }
    }
    @Test public void interruptingImpactRequiresCurrentAckAndReadyWithoutImpactIsCompatible() {
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"checks\":[],\"fingerprint\":\"current\",\"impact\":{\"ackrequired\":true}}").getAsJsonObject();
        try { StorageServiceConfigurationPreflight.requireReady(report,"stale"); fail(); }
        catch (StorageServiceConfigurationPreflight.Rejected expected) { assertEquals("BLOCKED",report.get("status").getAsString()); }
        report.addProperty("status","READY"); report.add("checks",new com.google.gson.JsonArray());
        StorageServiceConfigurationPreflight.requireReady(report,"current");
    }
    @Test public void runtimeReadyIsNotAnActiveWriter() {
        StorageServiceRuntimeUpgradeVO ready=mock(StorageServiceRuntimeUpgradeVO.class);
        when(ready.getState()).thenReturn(StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY);
        assertFalse(StorageServiceConfigurationPreflight.runtimeWriting(ready));
        when(ready.getState()).thenReturn(StorageServiceRuntimeUpgradeVO.State.RUNNING);
        when(ready.getPhase()).thenReturn("ACTIVATING");
        assertTrue(StorageServiceConfigurationPreflight.runtimeWriting(ready));
        when(ready.getState()).thenReturn(StorageServiceRuntimeUpgradeVO.State.OBSERVATION_PENDING);
        when(ready.getPhase()).thenReturn("OBSERVATION_PENDING");
        assertTrue(StorageServiceConfigurationPreflight.runtimeWriting(ready));
    }
    @Test public void readOnlyProbeReturnsUnknownOnTransportLossWithoutMutatingDaos() {
        StorageFileShareDao shares=mock(StorageFileShareDao.class);
        StorageBlockTargetDao targets=mock(StorageBlockTargetDao.class);
        StorageAccessRuleDao rules=mock(StorageAccessRuleDao.class);
        VolumeDao volumes=mock(VolumeDao.class);
        StorageServiceRuntimeUpgradeDao upgrades=mock(StorageServiceRuntimeUpgradeDao.class);
        StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(1L);when(instance.getVmId()).thenReturn(2L);when(instance.getUuid()).thenReturn("instance");
        when(instance.getActiveConfigurationOperationId()).thenReturn(null);
        when(shares.listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.NFS)).thenReturn(Collections.emptyList());
        when(targets.listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.NFS)).thenReturn(Collections.emptyList());
        when(guest.dispatch(any())).thenThrow(new CloudRuntimeException("transport lost"));
        StorageServiceConfigurationPreflight service=new StorageServiceConfigurationPreflight(mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao.class),shares,targets,rules,volumes,upgrades,guest,v->{throw new AssertionError("unexpected volume");});
        JsonObject result=service.observe(instance,StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"createStorageNfsExport\"}"),null);
        assertEquals("UNKNOWN",result.get("status").getAsString());
        verify(shares).listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.NFS);
        verify(targets).listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.NFS);
        verifyNoMoreInteractions(shares,targets,rules,volumes);
    }
    @Test public void existingOperationRemainsReadableWhenQgaIsDisconnected() throws Exception {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
        StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
        when(guest.dispatch(any())).thenThrow(new CloudRuntimeException("QGA unavailable"));
        Field field=StorageServiceManagerImpl.class.getDeclaredField("guestCommandDispatcher");field.setAccessible(true);field.set(manager,guest);
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(1L);when(instance.getVmId()).thenReturn(2L);when(instance.getUuid()).thenReturn("instance");
        when(instance.getActiveConfigurationOperationId()).thenReturn(7L);
        StorageServiceConfigurationOperationVO op=new StorageServiceConfigurationOperationVO();
        Field id=StorageServiceConfigurationOperationVO.class.getDeclaredField("id");id.setAccessible(true);id.set(op,7L);
        op.setState("RUNNING");op.setPhase("APPLYING");
        Method method=StorageServiceManagerImpl.class.getDeclaredMethod("configurationResponse",StorageServiceInstanceVO.class,StorageServiceConfigurationOperationVO.class);method.setAccessible(true);
        JsonObject wire=new com.google.gson.Gson().toJsonTree(method.invoke(manager,instance,op)).getAsJsonObject();
        assertEquals("RUNNING",wire.get("state").getAsString());
        assertFalse(wire.getAsJsonObject("observation").get("observationAvailable").getAsBoolean());
        assertFalse(wire.get("canresume").getAsBoolean());
    }    @Test public void acknowledgementMetadataCannotTurnAnAcceptedRetryIntoAnotherBusinessRequest() throws Exception {
        org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd cmd=
            new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd();
        Field ack=org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd.class.getDeclaredField("impactAck");
        ack.setAccessible(true);ack.set(cmd,"first");
        String hash=StorageServiceConfigurationCoordinator.requestHash(cmd);
        ack.set(cmd,"refreshed");
        assertEquals(hash,StorageServiceConfigurationCoordinator.requestHash(cmd));
    }
    @Test public void configuredProtocolPortsAreObservedWithoutMixingOtherProtocols() {
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao protocols=mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao.class);
        StorageServiceProtocolVO nfs=mock(StorageServiceProtocolVO.class);
        when(nfs.getProtocol()).thenReturn(StorageServiceInstance.Protocol.NFS);when(nfs.getPort()).thenReturn(3049);
        when(nfs.getConfigJson()).thenReturn("{\"listenerGroups\":[{\"port\":8449}]}");
        when(protocols.listByInstanceId(1L)).thenReturn(Collections.singletonList(nfs));
        StorageFileShareDao shares=mock(StorageFileShareDao.class);StorageBlockTargetDao targets=mock(StorageBlockTargetDao.class);
        StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
        when(guest.dispatch(any())).thenAnswer(call -> {
            StorageServiceGuestCommand command=call.getArgument(0);
            return new StorageServiceGuestCommandResult(true,"",command.getOperation().contains("capability")
                ? "{\"configurationPreflightVersion\":1}" : "{\"status\":\"READY\",\"checks\":[]}");
        });
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);when(instance.getId()).thenReturn(1L);
        when(instance.getVmId()).thenReturn(2L);when(instance.getUuid()).thenReturn("instance");when(instance.getActiveConfigurationOperationId()).thenReturn(null);
        StorageServiceConfigurationPreflight service=new StorageServiceConfigurationPreflight(protocols,shares,targets,mock(StorageAccessRuleDao.class),
            mock(VolumeDao.class),mock(StorageServiceRuntimeUpgradeDao.class),guest,v->{});
        JsonObject result=service.observe(instance,StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"createStorageNfsExport\"}"),null);
        assertEquals("[3049,8449]",result.getAsJsonObject("intent").getAsJsonArray("ports").toString());
    }

}
