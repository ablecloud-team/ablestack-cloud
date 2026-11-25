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
package org.apache.cloudstack.backup.netbackup;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupDetailVO;
import org.apache.cloudstack.backup.BackupVO;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.db.GlobalLock;
import com.google.gson.Gson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NetBackupChainRetentionTest {
    private final AblestackNetBackupClient client = mock(AblestackNetBackupClient.class);
    private final BackupDao backups = mock(BackupDao.class);
    private final BackupDetailsDao details = mock(BackupDetailsDao.class);
    private final Map<String, BackupVO> points = new HashMap<>();
    private final Map<String, Map<Integer, Instant>> expirations = new LinkedHashMap<>();
    private final Instant early = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(86400);
    private final Instant later = early.plusSeconds(7 * 86400);
    private final NetBackupChainRetention protection = new NetBackupChainRetention(client, backups, details);
    private final GlobalLock lock = mock(GlobalLock.class);
    private MockedStatic<GlobalLock> locks;

    @Before public void setup() {
        locks = mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        when(lock.lock(1)).thenReturn(true);
        when(backups.findByUuid(anyString())).thenAnswer(call -> points.get(call.getArgument(0)));
        when(backups.listByVmId(1L, 10L)).thenAnswer(call -> new java.util.ArrayList<Backup>(points.values()));
        when(details.listDetailsForResourceIdsAndKey(anyList(), eq(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY))).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            List<BackupDetailVO> receipts = new java.util.ArrayList<>();
            for (BackupVO point : points.values()) {
                String value = point.getDetail(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY);
                if (ids.contains(point.getId()) && value != null) {
                    receipts.add(new BackupDetailVO(point.getId(), ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, value, false));
                }
            }
            return receipts;
        });
        when(client.imageRetention(any(), anyBoolean(), anyString())).thenAnswer(call -> {
            ThirdPartyBackupManifest.Artifact artifact = call.getArgument(0);
            Map<Integer, Instant> copies = expirations.get(artifact.externalId);
            if (copies == null) { throw new CloudRuntimeException("Missing image"); }
            return new AblestackNetBackupClient.ImageRetention(artifact.externalId, "host", copies);
        });
        when(client.extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any())).thenAnswer(call -> {
            ThirdPartyBackupManifest.Artifact artifact = call.getArgument(0);
            Instant target = call.getArgument(4);
            expirations.get(artifact.externalId).put(call.getArgument(3), target.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            return new AblestackNetBackupClient.ExpireImageResult(true, null);
        });
        when(client.restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any())).thenAnswer(this::restoreCopy);
    }

    @After public void closeLocks() { locks.close(); }

    @Test public void fullPreparationDoesNotReadOrExtendAnyPreviousChain() {
        point(1, null, early);
        BackupVO nextFull = point(2, null, later);
        ThirdPartyBackupManifest m = manifest(nextFull);
        m.getOwnedArtifacts().forEach(artifact -> { artifact.completed = false; artifact.externalId = null; });
        m.setComplete(false);
        nextFull.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, m.toJson());
        protection.protect(nextFull, 43200, false);
        verify(client, never()).imageRetention(any(), anyBoolean(), anyString());
        verify(client, never()).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void completedReceiptDoesNotNeedPollingButUnverifiedAndForeignReceiptsDo() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        assertTrue(NetBackupChainRetention.needsReconciliation(child));
        protection.protect(child, 0, true);
        assertFalse(NetBackupChainRetention.needsReconciliation(child));
        NetBackupChainRetention.State state = state(child);
        state.state = "WAITING";
        child.getDetails().put(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, new Gson().toJson(state));
        assertTrue(NetBackupChainRetention.needsReconciliation(child));
        state.state = "COMPLETED"; state.backupUuid = "foreign-backup";
        child.getDetails().put(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, new Gson().toJson(state));
        assertTrue(NetBackupChainRetention.needsReconciliation(child));
    }

    @Test public void concurrentProtectionDefersWithoutCallingNetBackup() {
        BackupVO full = point(1, null, early);
        when(lock.lock(1)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> protection.protect(full, 0, true));
        verify(client, never()).imageRetention(any(), anyBoolean(), anyString());
        verify(lock, never()).unlock();
        verify(lock).releaseRef();
    }

    @Test public void anotherBackupCannotOverlapAnUnconfirmedRequestForTheSameFull() {
        BackupVO full = point(1, null, early);
        BackupVO first = point(2, full, later);
        BackupVO second = point(3, full, later.plusSeconds(86400));
        doReturn(new AblestackNetBackupClient.ExpireImageResult(false, "ticket-1"))
                .when(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertThrows(CloudRuntimeException.class, () -> protection.protect(first, 0, true));
        assertThrows(CloudRuntimeException.class, () -> protection.protect(second, 0, true));
        verify(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertTrue(state(second).reason.contains(first.getUuid()));
    }

    @Test public void allFullIntermediateAndMetadataCopiesOutliveTheLatestIncremental() {
        BackupVO full = point(1, null, early);
        BackupVO intermediate = point(2, full, early.plusSeconds(86400));
        BackupVO leaf = point(3, intermediate, later);
        protection.protect(leaf, 0, true);
        for (Map<Integer, Instant> copies : expirations.values()) { assertTrue(copies.values().stream().allMatch(later::equals)); }
        assertEquals("COMPLETED", state(leaf).state);
        verify(client, times(8)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void longerParentRetentionIsNeverShortenedOrCopiedBackIntoTheChild() {
        BackupVO full = point(1, null, later.plusSeconds(86400));
        BackupVO child = point(2, full, later);
        protection.protect(child, 0, true);
        verify(client, never()).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertEquals(later.toString(), state(child).protectedUntil);
    }

    @Test public void repeatedReconciliationUsesAbsoluteDatesWithoutExtendingForever() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        protection.protect(child, 0, true);
        protection.protect(child, 0, true);
        verify(client, times(4)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertEquals(later.toString(), state(child).protectedUntil);
    }

    @Test public void dailyEightPointChainsKeepTheirNativeRetentionWhenTheNextFullStarts() {
        Instant firstDay = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (int retentionDays : new int[]{14, 60}) {
            long firstId = retentionDays * 100L;
            BackupVO full = point(firstId, null, firstDay.plusSeconds(retentionDays * 86400L));
            protection.protect(full, 0, true);
            java.util.List<BackupVO> chain = new java.util.ArrayList<>();
            chain.add(full);
            BackupVO previous = full;
            for (int day = 1; day < 8; day++) {
                Instant nativeDate = firstDay.plusSeconds((retentionDays + day) * 86400L);
                BackupVO child = point(firstId + day, previous, nativeDate);
                protection.protect(child, 0, true);
                chain.add(child);
                for (BackupVO required : chain) { assertExpirations(required, nativeDate); }
                assertEquals(nativeDate.toString(), state(child).protectedUntil);
                state(child).copies.values().forEach(copy -> assertEquals(nativeDate.toString(), copy.original));
                previous = child;
            }
            Instant lastRequired = firstDay.plusSeconds((retentionDays + 7) * 86400L);
            BackupVO nextFull = point(firstId + 8, null, firstDay.plusSeconds((retentionDays + 8) * 86400L));
            protection.protect(nextFull, 0, true);
            org.mockito.Mockito.clearInvocations(client);
            for (int recheck = 0; recheck < 3; recheck++) {
                protection.protect(previous, 0, true);
                protection.protect(full, 0, true);
            }
            verify(client, never()).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
            for (BackupVO required : chain) { assertExpirations(required, lastRequired); }
            state(full).copies.values().forEach(copy ->
                    assertEquals(firstDay.plusSeconds(retentionDays * 86400L).toString(), copy.original));
            assertExpirations(nextFull, firstDay.plusSeconds((retentionDays + 8) * 86400L));
        }
    }

    @Test public void approvedTicketResumesWithoutCreatingAnotherApprovalRequest() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        doReturn(new AblestackNetBackupClient.ExpireImageResult(false, "ticket-1"))
                .when(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        verify(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertTrue(state(child).reason.contains("ticket-1"));
        expirations.values().forEach(copies -> copies.replaceAll((copy, date) -> later));
        protection.protect(child, 0, true);
        assertEquals("COMPLETED", state(child).state);
        assertTrue(state(child).pending.isEmpty());
    }

    @Test public void responseLossPreservesIntentAndDoesNotResubmitIt() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        doThrow(new CloudRuntimeException("Response lost"))
                .when(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        verify(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertEquals("WAITING", state(child).state);
        assertFalse(state(child).pending.isEmpty());
    }

    @Test public void definiteRejectionCanBeRetriedAfterPermissionsAreRepaired() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        doThrow(new AblestackNetBackupClient.RetentionUpdateRejected("HTTP 403"))
                .when(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        assertTrue(state(child).pending.isEmpty());
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        verify(client, times(2)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void successResponseWithoutCatalogChangeDoesNotCompleteProtection() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        doReturn(new AblestackNetBackupClient.ExpireImageResult(true, null))
                .when(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        assertEquals("WAITING", state(child).state);
        assertFalse(state(child).pending.isEmpty());
    }

    @Test public void secondPrecisionCatalogCompletesPreparationWithoutMovingItsDeadlineOnRetry() {
        BackupVO full = point(1, null, Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS), 2);
        protection.protect(full, 43200, false);
        String deadline = state(full).preparationUntil;
        assertEquals(0, Instant.parse(deadline).getNano());
        assertEquals("COMPLETED", state(full).state);
        protection.protect(full, 43200, false);
        assertEquals(deadline, state(full).preparationUntil);
        verify(client, times(6)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void twoHourPolicyIsNotExtendedByTwelveHourPhaseTimeouts() {
        Instant policyExpiration = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(7200);
        BackupVO full = point(1, null, policyExpiration, 2);
        protection.protect(full, 43200, false);
        assertEquals(policyExpiration.toString(), state(full).protectedUntil);
        assertEquals("COMPLETED", state(full).state);
        verify(client, never()).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void legacyTruncatedPreparationIsRepairedBeforeContinuingTheBackup() {
        BackupVO full = point(1, null, early);
        Instant target = later.plusMillis(537);
        legacyPending(full, full, target, null);
        NetBackupChainRetention.State saved = state(full);
        saved.preparationUntil = target.toString();
        full.getDetails().put(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, new Gson().toJson(saved));
        protection.protect(full, 43200, false);
        assertTrue(Instant.parse(state(full).preparationUntil).isBefore(Instant.now().plusSeconds(1801)));
        assertEquals(later.plusSeconds(1).toString(), state(full).protectedUntil);
        assertEquals("COMPLETED", state(full).state);
        assertTrue(state(full).pending.isEmpty());
        expirations.values().forEach(copies -> assertTrue(copies.values().stream().allMatch(later.plusSeconds(1)::equals)));
        protection.protect(full, 43200, false);
        verify(client, times(4)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void legacyTruncatedPendingKeepsItsOriginalBoundDuringFinalization() {
        BackupVO full = point(1, null, early);
        legacyPending(full, full, later.plusMillis(355), null);
        protection.protect(full, 0, true);
        assertEquals(later.plusSeconds(1).toString(), state(full).protectedUntil);
        assertTrue(state(full).pending.isEmpty());
        assertEquals("COMPLETED", state(full).state);
    }

    @Test public void anotherBackupsTruncatedRequestIsRepairedBeforeProtectingTheSharedFull() {
        BackupVO full = point(1, null, early);
        BackupVO first = point(2, full, early);
        BackupVO second = point(3, full, early);
        legacyPending(first, full, later.plusMillis(35), null);
        protection.protect(second, 0, true);
        assertEquals(early.toString(), state(second).protectedUntil);
        assertEquals("COMPLETED", state(second).state);
        protection.protect(first, 0, true);
        assertTrue(state(first).pending.isEmpty());
        assertEquals("COMPLETED", state(first).state);
    }

    @Test public void legacyPrecisionRecoveryDoesNotResubmitAnUnapprovedTicketOrAnEarlierCatalogDate() {
        for (String ticket : new String[]{null, "ticket-1", "MPA_TICKET_UNAVAILABLE"}) {
            BackupVO full = point(points.size() + 1, null, early);
            legacyPending(full, full, later.plusMillis(467), ticket);
            if (ticket == null) {
                String id = manifest(full).getCurrentArtifacts().get(0).externalId;
                expirations.get(id).put(1, later.minusSeconds(1));
            }
            assertThrows(CloudRuntimeException.class, () -> protection.protect(full, 0, true));
            assertEquals("WAITING", state(full).state);
            assertFalse(state(full).pending.isEmpty());
        }
        verify(client, never()).extendImageRetention(any(), anyBoolean(), anyString(), eq(1), any());
    }

    private void legacyPending(BackupVO requester, BackupVO imageOwner, Instant target, String ticket) {
        String id = manifest(imageOwner).getCurrentArtifacts().get(0).externalId;
        expirations.get(id).put(1, target.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        NetBackupChainRetention.Pending pending = new NetBackupChainRetention.Pending();
        pending.target = target.toString();
        pending.ticket = ticket;
        NetBackupChainRetention.State saved = new NetBackupChainRetention.State();
        saved.backupUuid = requester.getUuid();
        saved.state = "WAITING";
        saved.protectedUntil = target.toString();
        saved.pending.put(id + ":1", pending);
        requester.getDetails().put(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, new Gson().toJson(saved));
    }

    @Test public void missingInventoryFailsBeforeUpdatingAnyOtherImages() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        expirations.remove(manifest(child).getMetadata().externalId);
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        verify(client, never()).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void foreignVmOwnershipIsRejectedBeforeExternalCalls() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        full.setVmId(999L);
        assertThrows(CloudRuntimeException.class, () -> protection.protect(child, 0, true));
        verify(client, never()).imageRetention(any(), anyBoolean(), anyString());
    }

    @Test public void equalVmIdsOutsideTheBoxedLongCacheAreTheSameOwner() {
        BackupVO full = point(1, null, early);
        BackupVO child = point(2, full, later);
        full.setVmId(Long.valueOf("336"));
        child.setVmId(Long.valueOf("336"));
        when(backups.listByVmId(1L, 336L)).thenAnswer(call -> new java.util.ArrayList<Backup>(points.values()));
        protection.protect(child, 0, true);
        assertEquals("COMPLETED", state(child).state);
        assertExpirations(full, later);
    }

    @Test public void preparationPinsAncestorsEvenBeforeAnyNewImageExists() {
        BackupVO full = point(1, null, Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        BackupVO child = point(2, full, later);
        child.setStatus(Backup.Status.BackingUp);
        ThirdPartyBackupManifest m = manifest(child);
        m.getOwnedArtifacts().forEach(artifact -> { artifact.completed = false; artifact.externalId = null; });
        m.setComplete(false);
        child.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, m.toJson());
        protection.protect(child, 43200, false);
        String until = state(child).preparationUntil;
        protection.protect(child, 43200, false);
        assertEquals(until, state(child).preparationUntil);
        assertTrue(Instant.parse(until).isAfter(Instant.now().plusSeconds(1700)));
        assertTrue(Instant.parse(until).isBefore(Instant.now().plusSeconds(1801)));
        verify(client, times(4)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void multiVolumePreparationUsesABoundedRenewableLeaseBeforeAnyChildImageExists() {
        BackupVO full = point(1, null, Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS), 2);
        BackupVO child = point(2, full, later, 2);
        child.setStatus(Backup.Status.BackingUp);
        ThirdPartyBackupManifest m = manifest(child);
        m.getOwnedArtifacts().forEach(artifact -> { artifact.completed = false; artifact.externalId = null; });
        m.setComplete(false);
        child.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, m.toJson());
        Instant before = Instant.now();
        protection.protect(child, 43200, false);
        Instant deadline = Instant.parse(state(child).preparationUntil);
        assertFalse(deadline.isBefore(before.plusSeconds(NetBackupChainRetention.LEASE_SECONDS)));
        assertTrue(deadline.isBefore(Instant.now().plusSeconds(NetBackupChainRetention.LEASE_SECONDS + 1)));
        verify(client, times(6)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void completionRestoresTheNativeDatesRecordedBeforeAnyExtension() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant original = now.plusSeconds(300);
        BackupVO full = point(1, null, original);
        full.setStatus(Backup.Status.BackingUp);
        doAnswer(call -> {
            ThirdPartyBackupManifest.Artifact artifact = call.getArgument(0);
            String key = artifact.externalId + ":" + call.getArgument(3);
            assertEquals(original.toString(), state(full).copies.get(key).original);
            assertTrue(state(full).pending.containsKey(key));
            Instant target = call.getArgument(4);
            expirations.get(artifact.externalId).put(call.getArgument(3), target);
            return new AblestackNetBackupClient.ExpireImageResult(true, null);
        }).when(client).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        at(now).protect(full, 43200, false);
        assertExpirations(full, now.plusSeconds(NetBackupChainRetention.LEASE_SECONDS));
        at(now.plusSeconds(60)).protect(full, 0, true);
        assertExpirations(full, original);
        assertFalse(state(full).leaseActive);
        assertEquals(null, state(full).preparationUntil);
        assertEquals(original.toString(), state(full).protectedUntil);
        verify(client, times(4)).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void aLongRunningBackupRenewsItsLeaseWithoutReplacingItsNativeDates() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        at(now.plusSeconds(120)).protect(full, 43200, false);
        verify(client, times(4)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        at(now.plusSeconds(1250)).protect(full, 43200, false);
        assertExpirations(full, now.plusSeconds(1250 + NetBackupChainRetention.LEASE_SECONDS));
        state(full).copies.values().forEach(copy -> assertEquals(now.plusSeconds(300).toString(), copy.original));
        verify(client, times(8)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
    }

    @Test public void restartUsesTheDurableOriginalDatesWhenCompletingTheBackup() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        NetBackupChainRetention restarted = at(now.plusSeconds(60));
        restarted.protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(300));
        assertTrue(state(full).pending.isEmpty());
    }

    @Test public void completionKeepsTheLaterNativeExpirationOfAnotherValidIncremental() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        BackupVO laterChild = point(2, full, now.plusSeconds(1500));
        at(now).protect(laterChild, 0, true);
        BackupVO child = point(3, full, now.plusSeconds(900));
        child.setStatus(Backup.Status.BackingUp);
        at(now).protect(child, 43200, false);
        assertExpirations(full, now.plusSeconds(1800));
        at(now.plusSeconds(60)).protect(child, 0, true);
        assertExpirations(full, now.plusSeconds(1500));
        assertExpirations(laterChild, now.plusSeconds(1500));
        assertExpirations(child, now.plusSeconds(900));
    }

    @Test public void aRestoreKeepsTemporaryProtectionUntilItsCleanupIsConfirmed() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        full.getDetails().put(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL, "restore-1");
        full.getDetails().put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
        at(now.plusSeconds(60)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(1800));
        verify(client, never()).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
        full.getDetails().put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
        at(now.plusSeconds(120)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(300));
    }

    @Test public void longRunningRestoreRenewsImagesPastNativeExpiryAndReleasesOnlyAfterCleanup() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.getDetails().put(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL, "restore-1");
        full.getDetails().put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
        assertTrue(NetBackupChainRetention.needsReconciliation(full));
        at(now).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(1800));
        at(now.plusSeconds(1250)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(3050));
        state(full).copies.values().forEach(copy -> assertEquals(now.plusSeconds(300).toString(), copy.original));
        full.getDetails().put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
        at(now.plusSeconds(1260)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(1560));
        assertFalse(state(full).leaseActive);
    }

    @Test public void queuedRestorePlanProtectsImagesBeforeHostDispatchAndJobTracking() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(60));
        full.getDetails().put(ThirdPartyBackupRestore.PLAN_KEY, "{\"jobId\":\"queued-restore\"}");
        full.getDetails().put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
        at(now).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(1800));
        assertTrue(state(full).leaseActive);
        assertTrue(state(full).nextCheckAt > 0);
    }

    @Test public void aRegisteredChildWithoutAConfirmedLeaseCannotAuthorizeShorteningItsParent() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        BackupVO child = point(2, full, now.plusSeconds(900));
        child.setStatus(Backup.Status.BackingUp);
        at(now.plusSeconds(60)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(1800));
        child.setStatus(Backup.Status.Failed);
        at(now.plusSeconds(90)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(1800));
        at(now.plusSeconds(120)).release(child);
        at(now.plusSeconds(120)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(300));
    }

    @Test public void aLegacyReceiptNeverAuthorizesShorteningAnUnknownOriginalDate() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant observed = now.plusSeconds(3 * 86400);
        BackupVO full = point(1, null, observed);
        NetBackupChainRetention.State legacy = new NetBackupChainRetention.State();
        legacy.version = 1;
        legacy.backupUuid = full.getUuid();
        legacy.state = "COMPLETED";
        legacy.protectedUntil = observed.toString();
        full.getDetails().put(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, new Gson().toJson(legacy));
        at(now).protect(full, 0, true);
        assertExpirations(full, observed);
        assertTrue(state(full).legacy);
        state(full).copies.values().forEach(copy -> assertFalse(copy.restorable));
        verify(client, never()).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void manualExpirationChangesArePreservedInsteadOfBeingRolledBack() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        ThirdPartyBackupManifest.Artifact root = manifest(full).getCurrentArtifacts().get(0);
        expirations.get(root.externalId).put(1, now.plusSeconds(7200));
        at(now.plusSeconds(60)).protect(full, 0, true);
        assertEquals(now.plusSeconds(7200), expirations.get(root.externalId).get(1));
        assertTrue(state(full).copies.get(root.externalId + ":1").externallyChanged);
        verify(client, times(3)).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void loweringRetentionManuallyDoesNotAuthorizeMoldToOverwriteTheOperator() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        ThirdPartyBackupManifest.Artifact root = manifest(full).getCurrentArtifacts().get(0);
        expirations.get(root.externalId).put(1, now.plusSeconds(120));
        assertThrows(CloudRuntimeException.class, () -> at(now.plusSeconds(60)).protect(full, 0, true));
        assertEquals(now.plusSeconds(120), expirations.get(root.externalId).get(1));
        verify(client, times(4)).extendImageRetention(any(), anyBoolean(), anyString(), anyInt(), any());
        verify(client, never()).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void aLostRestorationResponseCannotBeMistakenForConfirmationOfAShorterDate() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        doThrow(new CloudRuntimeException("Response lost"))
                .when(client).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
        assertThrows(CloudRuntimeException.class, () -> at(now.plusSeconds(60)).protect(full, 0, true));
        assertThrows(CloudRuntimeException.class, () -> at(now.plusSeconds(120)).protect(full, 0, true));
        assertExpirations(full, now.plusSeconds(1800));
        assertFalse(state(full).pending.isEmpty());
        verify(client).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void anAppliedRestorationIsRecoveredAfterResponseLossWithoutRepeatingTheWrite() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            AblestackNetBackupClient.ExpireImageResult result = restoreCopy(call);
            if (lost.getAndSet(false)) { throw new CloudRuntimeException("Response lost after applying the date"); }
            return result;
        }).when(client).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
        assertThrows(CloudRuntimeException.class, () -> at(now.plusSeconds(60)).protect(full, 0, true));
        at(now.plusSeconds(120)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(300));
        assertTrue(state(full).pending.isEmpty());
        verify(client, times(4)).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void restorationApprovalSurvivesRestartWithoutCreatingAnotherTicket() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(300));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        java.util.concurrent.atomic.AtomicBoolean approval = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> approval.get() ? new AblestackNetBackupClient.ExpireImageResult(false, "restore-ticket") : restoreCopy(call))
                .when(client).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
        assertThrows(CloudRuntimeException.class, () -> at(now.plusSeconds(60)).protect(full, 0, true));
        assertThrows(CloudRuntimeException.class, () -> at(now.plusSeconds(90)).protect(full, 0, true));
        verify(client).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
        String key = state(full).pending.keySet().iterator().next();
        int split = key.lastIndexOf(':');
        expirations.get(key.substring(0, split)).put(Integer.parseInt(key.substring(split + 1)), now.plusSeconds(300));
        approval.set(false);
        at(now.plusSeconds(120)).protect(full, 0, true);
        assertExpirations(full, now.plusSeconds(300));
        assertTrue(state(full).pending.isEmpty());
        verify(client, times(4)).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    @Test public void anElapsedNativeDateGetsOneFixedGracePeriodWithoutRollingForwardOnRetry() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        BackupVO full = point(1, null, now.plusSeconds(60));
        full.setStatus(Backup.Status.BackingUp);
        at(now).protect(full, 43200, false);
        at(now.plusSeconds(120)).protect(full, 0, true);
        Instant release = now.plusSeconds(120 + NetBackupChainRetention.RECHECK_SECONDS);
        assertExpirations(full, release);
        at(now.plusSeconds(180)).protect(full, 0, true);
        assertExpirations(full, release);
        verify(client, times(4)).restoreImageRetention(any(), anyBoolean(), anyString(), anyInt(), any(), any());
    }

    private NetBackupChainRetention at(Instant now) {
        return new NetBackupChainRetention(client, backups, details, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void assertExpirations(Backup backup, Instant expected) {
        for (ThirdPartyBackupManifest.Artifact artifact : manifest(backup).getOwnedArtifacts()) {
            expirations.get(artifact.externalId).values().forEach(expiration -> assertEquals(expected, expiration));
        }
    }

    private AblestackNetBackupClient.ExpireImageResult restoreCopy(InvocationOnMock call) {
        ThirdPartyBackupManifest.Artifact artifact = call.getArgument(0);
        Instant expected = call.getArgument(4);
        Instant target = call.getArgument(5);
        assertEquals(expected, expirations.get(artifact.externalId).get(call.getArgument(3)));
        expirations.get(artifact.externalId).put(call.getArgument(3), target);
        return new AblestackNetBackupClient.ExpireImageResult(true, null);
    }

    private BackupVO point(long id, BackupVO parent, Instant expiration) {
        return point(id, parent, expiration, 1);
    }

    private BackupVO point(long id, BackupVO parent, Instant expiration, int volumeCount) {
        BackupVO backup = new BackupVO();
        ReflectionTestUtils.setField(backup, "id", id);
        backup.setVmId(10L);
        backup.setZoneId(1L);
        backup.setStatus(Backup.Status.BackedUp);
        backup.setDetails(new HashMap<>());
        String path = "/mnt/staging/ablestack-netbackup/i-2-10-VM/point-" + id;
        List<Backup.VolumeInfo> volumes = new java.util.ArrayList<>();
        for (int index = 0; index < volumeCount; index++) {
            volumes.add(new Backup.VolumeInfo("volume-" + index, "disk-" + index + ".raw",
                    index == 0 ? Volume.Type.ROOT : Volume.Type.DATADISK, 100L << 30, (long) index, null, null, null));
        }
        ThirdPartyBackupManifest m = ThirdPartyBackupManifest.create("ablestack-netbackup", backup.getUuid(), "i-2-10-VM", "point-" + id,
                parent == null ? "FULL" : "INCREMENTAL", "host", path, "RBD_DIFF",
                volumes,
                parent == null ? null : manifest(parent).toJson(), parent == null ? null : manifest(parent).getTimestamp());
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = backup.getUuid(); metadata.path = path; m.setMetadata(metadata);
        int index = 0;
        for (ThirdPartyBackupManifest.Artifact artifact : m.getOwnedArtifacts()) {
            artifact.completed = true; artifact.sourceHost = "host"; artifact.jobId = String.valueOf(id);
            artifact.externalId = "host_" + id + "_" + index++;
            expirations.put(artifact.externalId, new LinkedHashMap<>(Map.of(1, expiration, 2, expiration)));
        }
        m.setComplete(true);
        backup.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, m.toJson());
        backup.getDetails().put("netbackup.transfer.mode", "USER_BACKUP");
        backup.getDetails().put("netbackup.policy.name", "host-policy");
        points.put(backup.getUuid(), backup);
        return backup;
    }

    private ThirdPartyBackupManifest manifest(Backup backup) {
        return ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
    }

    private NetBackupChainRetention.State state(Backup backup) {
        return new Gson().fromJson(backup.getDetail(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY), NetBackupChainRetention.State.class);
    }
}
