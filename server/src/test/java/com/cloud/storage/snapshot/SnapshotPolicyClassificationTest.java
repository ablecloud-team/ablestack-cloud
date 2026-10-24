// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage.snapshot;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotPolicyDao;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SnapshotPolicyClassificationTest {
    private final SnapshotManagerImpl manager = new SnapshotManagerImpl();
    private final SnapshotPolicyDao policies = mock(SnapshotPolicyDao.class);
    private final SnapshotDetailsDao details = mock(SnapshotDetailsDao.class);
    private final VolumeInfo volume = mock(VolumeInfo.class);
    private final SnapshotPolicyVO policy = mock(SnapshotPolicyVO.class);

    @Before
    public void setup() {
        ReflectionTestUtils.setField(manager, "_snapshotPolicyDao", policies);
        ReflectionTestUtils.setField(manager, "snapshotDetailsDao", details);
        when(volume.getId()).thenReturn(10L);
        when(volume.getAccountId()).thenReturn(12L);
        when(volume.getDomainId()).thenReturn(14L);
        when(policy.getId()).thenReturn(8L);
        when(policy.getUuid()).thenReturn("policy-uuid");
        when(policy.getVolumeId()).thenReturn(10L);
        when(policy.getAccountId()).thenReturn(12L);
        when(policy.getDomainId()).thenReturn(14L);
        when(policies.findById(8L)).thenReturn(policy);
    }

    @Test
    public void realPolicyEightKeepsEachInterval() {
        Snapshot.Type[] expected = {Snapshot.Type.HOURLY, Snapshot.Type.DAILY, Snapshot.Type.WEEKLY, Snapshot.Type.MONTHLY};
        for (short interval = 0; interval < expected.length; interval++) {
            when(policy.getInterval()).thenReturn(interval);
            assertEquals(expected[interval], manager.getSnapshotType(8L, manager.resolveSnapshotPolicy(8L, volume)));
        }
        assertTrue(Snapshot.INTERNAL_POLICY_ID < 0);
        assertEquals(8, Snapshot.Type.INTERNAL.ordinal());
    }

    @Test
    public void internalIntentDoesNotConsultPositivePolicyNamespace() {
        assertNull(manager.resolveSnapshotPolicy(Snapshot.INTERNAL_POLICY_ID, volume));
        assertEquals(Snapshot.Type.INTERNAL, manager.getSnapshotType(Snapshot.INTERNAL_POLICY_ID, null));
        verifyNoInteractions(policies);
    }

    @Test
    public void defaultAndZeroRemainManual() {
        assertNull(manager.resolveSnapshotPolicy(null, volume));
        assertNull(manager.resolveSnapshotPolicy(Snapshot.MANUAL_POLICY_ID, volume));
        assertEquals(Snapshot.Type.MANUAL, manager.getSnapshotType(null, null));
        assertEquals(Snapshot.Type.MANUAL, manager.getSnapshotType(0L, null));
        verifyNoInteractions(policies);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void missingPolicyIsRejected() {
        manager.resolveSnapshotPolicy(100L, volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void unknownNegativeIntentIsRejected() {
        manager.resolveSnapshotPolicy(-2L, volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void differentVolumeIsRejected() {
        when(policy.getVolumeId()).thenReturn(99L);
        manager.resolveSnapshotPolicy(8L, volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void differentOwnerIsRejected() {
        when(policy.getAccountId()).thenReturn(99L);
        manager.resolveSnapshotPolicy(8L, volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void differentDomainIsRejected() {
        when(policy.getDomainId()).thenReturn(99L);
        manager.resolveSnapshotPolicy(8L, volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void invalidIntervalIsRejected() {
        when(policy.getInterval()).thenReturn((short) 99);
        manager.resolveSnapshotPolicy(8L, volume);
    }

    @Test
    public void policyProvenanceSurvivesPolicyDeletion() {
        SnapshotVO snapshot = mock(SnapshotVO.class);
        when(snapshot.getId()).thenReturn(55L);
        manager.recordSnapshotCreationSource(snapshot, Snapshot.Type.HOURLY, policy);
        verify(details).addDetail(55L, "snapshot.creation.intent", "POLICY", false);
        verify(details).addDetail(55L, "snapshot.policy.id", "8", false);
        verify(details).addDetail(55L, "snapshot.policy.uuid", "policy-uuid", false);
        verify(details).addDetail(55L, "snapshot.policy.interval", "HOURLY", false);
    }
}
