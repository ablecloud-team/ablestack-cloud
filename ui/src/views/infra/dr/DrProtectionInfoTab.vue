<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<template>
  <div class="cross-dr-protection-info">
    <div class="cross-dr-tab-toolbar">
      <span class="cross-dr-protection-info__cache">
        {{ $t('label.dr.protection.view.generated') }}: {{ generated || '-' }}
      </span>
      <dr-status-pill :status="projectionState || 'UNKNOWN'" />
    </div>

    <a-alert
      v-if="initialSyncPending"
      type="info"
      show-icon
      :message="$t('message.dr.initial.sync.pending')"
      :description="$t('message.dr.initial.sync.pending.detail')" />

    <a-alert
      v-if="displayLastError"
      type="warning"
      show-icon
      :message="$t('message.dr.protection.view.stale')"
      :description="displayLastError" />

    <a-alert
      v-if="plan.projectionintegritystate === 'INCONSISTENT'"
      type="error"
      show-icon
      :message="$t('message.dr.projection.integrity.failed')"
      :description="$t('message.dr.projection.integrity.failed.detail', {
        code: plan.projectionintegritycode || '-',
        sequence: plan.projectionintegritysequence || '-'
      })" />

    <a-alert
      v-if="cycleDataCopied && !cycleTargetDurable"
      type="warning"
      show-icon
      :message="$t('message.dr.cycle.uncommitted')" />

    <a-alert
      v-if="nbdRecoveryRequired"
      type="error"
      show-icon
      :message="$t('message.dr.nbd.recovery.required')"
      :description="nbdRecoveryMessage" />

    <a-alert
      v-if="runtimeReconciling"
      class="cross-dr-terminal-alert"
      type="info"
      show-icon
      :message="$t('message.dr.runtime.reconciliation')"
      :description="$t('message.dr.runtime.reconciliation.detail')" />

    <section class="cross-dr-protection-info__section">
      <dr-plan-overview
        :plan="protectionPlan"
        :sourceSite="sourceSite"
        :targetSite="targetSite"
        :currentRun="currentRun"
        :runtime="currentProtectionRuntime"
        :showDetails="false"
        :showProtectionSummary="true" />
    </section>

    <section v-if="hasTestCheckpointEvidence" class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.test.checkpoint.validation') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.test.checkpoint.sequence')">
          {{ currentProtectionRuntime.testcheckpointsequence || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.test.checkpoint.lease')">
          <dr-status-pill :status="currentProtectionRuntime.checkpointleasestate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.test.checkpoint.seal')">
          <dr-status-pill :status="currentProtectionRuntime.testcheckpointsealstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.test.checkpoint.integrity')">
          <dr-status-pill :status="currentProtectionRuntime.testcheckpointintegritystate || 'PENDING'" />
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section v-if="hasCutoverState" class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.cutover.authority') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.operating.side')">
          <dr-status-pill :status="protectionPlan.operatingside || protectionPlan.activeside || 'SOURCE'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.protection.phase')">
          <dr-status-pill :status="protectionPlan.protectionphase || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.cloud.promotion.state')">
          <dr-status-pill :status="protectionPlan.cloudpromotionstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.engine.ack.state')">
          <dr-status-pill :status="protectionPlan.engineackstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.cutover.commit.state')">
          <dr-status-pill :status="protectionPlan.cutovercommitstate || 'NOT_SUBMITTED'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.boot.validation.state')">
          <dr-status-pill :status="protectionPlan.cutoverbootvalidationstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.cutover.completed.at')">
          {{ protectionPlan.cutovercompletedat || '-' }}
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section v-if="failbackSession && failbackSession.state" class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.failback.lifecycle') }}</h3>
      <a-alert
        v-if="terminalPublicationPending"
        class="cross-dr-terminal-alert"
        type="info"
        show-icon
        :message="$t('message.dr.terminal.publication.pending')"
        :description="$t('message.dr.terminal.publication.pending.detail')" />
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.failback.phase')">
          <dr-status-pill :status="failbackSession.state" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.acceptance.state')">
          <dr-status-pill :status="failbackSession.acceptancestate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item v-if="hasFailbackFailureMetadata" :label="$t('label.dr.failback.failure.phase')">
          {{ failbackSession.failurephase || '-' }}
        </a-descriptions-item>
        <a-descriptions-item v-if="hasFailbackFailureMetadata" :label="$t('label.dr.failback.failed.component')">
          {{ failbackSession.failedcomponent || '-' }}
        </a-descriptions-item>
        <a-descriptions-item v-if="hasFailbackFailureMetadata" :label="$t('label.dr.failback.driver.exit.code')">
          {{ failbackSession.driverexitcode === undefined || failbackSession.driverexitcode === null ? '-' : failbackSession.driverexitcode }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.worker.alive')">
          {{ failbackSession.workerpidalive === true ? $t('label.yes') : failbackSession.workerpidalive === false ? $t('label.no') : '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.baseline.file.state')">
          <dr-status-pill :status="failbackSession.baselinefilestate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.checkpoint')">
          {{ failbackSession.checkpointsequence || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.replication.direction')">
          {{ failbackSession.replicationdirection || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.provider.pair')">
          {{ failbackSession.providerpair || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.baseline.generation')">
          {{ failbackSession.baselinegeneration || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.baseline.state')">
          <dr-status-pill :status="failbackSession.baselinestate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.tracker.state')">
          <dr-status-pill :status="failbackSession.trackerstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.writer.state')">
          <dr-status-pill :status="failbackSession.writerstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.target.write.verified')">
          {{ failbackSession.targetwritten === true && failbackSession.writeverified === true ? $t('label.yes') : $t('label.no') }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.guest.compatibility.state')">
          <dr-status-pill :status="failbackSession.guestcompatibilitystate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.target.power')">
          <dr-status-pill :status="failbackSession.targetpowerstate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.source.power')">
          <dr-status-pill :status="failbackSession.sourcepowerstate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.boot.validation.state')">
          <dr-status-pill :status="failbackSession.bootvalidationstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.engine.ack.state')">
          <dr-status-pill :status="failbackSession.engineackstate || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.post.checkpoint')">
          {{ failbackSession.postfailbackcheckpointsequence || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.protection.resume.state')">
          <dr-status-pill :status="failbackProtectionResumeState" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.required.post.checkpoint')">
          {{ failbackSession.requiredpostfailbackcheckpointsequence || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.protection.resume.verified.at')">
          {{ failbackSession.protectionresumeverifiedat || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.commit.outcome')">
          <dr-status-pill :status="failbackSession.commitoutcome || 'PENDING'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.scheduler.generation')">
          {{ generationPair(failbackSession.schedulergeneration, failbackSession.schedulerackgeneration) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.scheduler.state')">
          <dr-status-pill :status="failbackSession.schedulerstate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.rollback.state')">
          <dr-status-pill :status="failbackSession.rollbackstate || 'NONE'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.failback.commit.verified.at')">
          {{ failbackSession.commitverifiedat || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.completed')">
          {{ failbackSession.completedat || '-' }}
        </a-descriptions-item>
      </a-descriptions>
      <a-alert
        v-if="canonicalFailbackFailure"
        class="cross-dr-terminal-alert"
        type="error"
        show-icon
        :message="canonicalFailbackFailure.code"
        :description="canonicalFailbackFailure.message" />
    </section>

    <section class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.replication.activity') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.state')">
          <dr-status-pill :status="replicationActivityState" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.current.cycle')">
          {{ activeCycleLabel }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.requested.mode')">
          {{ displaySyncCycle.requestedmode || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.effective.mode')">
          {{ displaySyncCycle.effectivemode || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.changed.bytes')">
          {{ formatBytes(displaySyncCycle.changedbytes) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.worker.liveness')">
          <dr-status-pill :status="displayWorkerLiveness" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.transfer.payload.bytes')">
          {{ formatBytes(displayTransferPayloadBytes) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.latest.completed.cycle')">
          {{ latestCompletedCycleSequence }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.freshness.state')">
          <dr-status-pill :status="protectionPlan.freshnessstate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.last.target.durable')">
          {{ protectionPlan.lasttargetdurableat || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.scheduler.next.run.at')">
          {{ protectionPlan.schedulernextrunat || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.scheduler.execution.budget')">
          {{ formatSeconds(protectionPlan.schedulerexecutionbudgetseconds) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.scheduler.cycle.wall.duration')">
          {{ formatSeconds(protectionPlan.schedulercyclewalldurationseconds) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.teardown.state')">
          <dr-status-pill :status="currentNbdTeardownState" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.quarantined.devices')">
          {{ currentProtectionRuntime.nbdquarantineddevicecount || 0 }}
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section v-if="hasNbdTeardownEvidence" class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.nbd.teardown') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.nbd.teardown.state')">
          <dr-status-pill :status="latestCompletedSyncCycle.nbdteardownstate || currentNbdTeardownState" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.teardown.duration')">
          {{ formatDuration(latestCompletedSyncCycle.nbdteardowndurationms) }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.source.devices')">
          {{ latestCompletedSyncCycle.nbdsourcedevicecount ?? '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.target.devices')">
          {{ latestCompletedSyncCycle.nbdtargetdevicecount ?? '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.teardown.completed.at')">
          {{ latestCompletedSyncCycle.nbdteardowncompletedat || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.nbd.teardown.error')">
          {{ latestCompletedSyncCycle.nbdteardownerrorcode || (!placementRecoveryActive && currentProtectionRuntime.nbdteardownerrorcode) || '-' }}
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.protection.topology') }}</h3>
      <dr-topology :plan="plan" :sourceSite="sourceSite" :targetSite="targetSite" />
    </section>

    <section class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.control.coordination') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.control.protocol')">
          {{ protectionPlan.runtimecontrolprotocolversion || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.control.readiness')">
          <dr-status-pill :status="runtimeControlReady ? 'READY' : 'NOT_READY'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.control.state')">
          <dr-status-pill :status="protectionPlan.runtimecontrolstate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.replication.cycle.state')">
          <dr-status-pill :status="protectionPlan.runtimecyclestate || protectionPlan.currentcyclestate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.control.generation')">
          {{ protectionPlan.runtimecontrolgeneration || '-' }} / {{ protectionPlan.runtimecontrolackgeneration || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.transition.state')">
          <dr-status-pill :status="protectionPlan.runtimetransitionstate || 'IDLE'" />
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section v-if="hasCycleCommitState" class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.cycle.commit') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.data.commit.state')">
          <dr-status-pill :status="cycleCommitState" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.cycle.retry.mode')">
          {{ cycleRetryMode }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.data.copied')">
          <dr-status-pill :status="cycleDataCopied ? 'READY' : 'NOT_READY'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.metadata.committed')">
          <dr-status-pill :status="cycleMetadataCommitted ? 'READY' : 'NOT_READY'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.target.durable')">
          <dr-status-pill :status="cycleTargetDurable ? 'READY' : 'NOT_READY'" />
        </a-descriptions-item>
        <a-descriptions-item v-if="cycleFailedComponent" :label="$t('label.dr.failed.component')">
          {{ cycleFailedComponent }}
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section v-if="hasSourceSnapshotState" class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.source.snapshot.lifecycle') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.state')">
          <dr-status-pill :status="plan.runtimesourcesnapshotlifecyclestate || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.source.snapshot.cleanup.required')">
          <dr-status-pill :status="plan.runtimesourcesnapshotcleanuprequired ? 'REQUIRED' : 'CLEAN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.source.snapshot.active.reference')">
          {{ plan.runtimesourcesnapshotrefpresent ? plan.runtimesourcesnapshotname : '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.source.snapshot.last.reference')">
          {{ plan.runtimesourcesnapshotlastref || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.source.snapshot.cleaned.at')">
          {{ formatEpoch(plan.runtimesourcesnapshotcleanedatepochms) }}
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.reverse.evidence') }}</h3>
      <a-descriptions size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.reverse.origin.checkpoint')">
          {{ protectionPlan.reverseorigincheckpointsequence || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.reverse.origin.ref')">
          {{ protectionPlan.reverseorigincheckpointref || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.reverse.verification.method')">
          {{ protectionPlan.reverseverificationmethod || $t('label.dr.evidence.unavailable') }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.reverse.readback')">
          {{ protectionPlan.reversereadbackverified === true ? $t('label.yes') : protectionPlan.reversereadbackverified === false ? $t('label.dr.readback.not.performed') : $t('label.dr.evidence.unavailable') }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.reverse.readback.bytes')">
          {{ protectionPlan.reversereadbackverifiedbytes == null ? '-' : protectionPlan.reversereadbackverifiedbytes }}
        </a-descriptions-item>
      </a-descriptions>
    </section>

    <section class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.latest.durable.checkpoint') }}</h3>
      <a-descriptions v-if="latestCompletedCheckpoint && latestCompletedCheckpoint.id" size="small" :column="2" bordered>
        <a-descriptions-item :label="$t('label.dr.engine.checkpoint.sequence')">
          {{ latestCompletedCheckpoint.checkpointsequence || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">
          <dr-status-pill :status="latestCompletedCheckpoint.state || 'UNKNOWN'" />
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.last.source.checkpoint')">
          {{ latestCompletedCheckpoint.sourcecreated || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.dr.last.target.durable')">
          {{ latestCompletedCheckpoint.targetreadyat || '-' }}
        </a-descriptions-item>
      </a-descriptions>
      <a-empty v-else :description="$t('message.dr.no.completed.checkpoint')" />
    </section>

    <section class="cross-dr-protection-info__section">
      <h3>{{ $t('label.dr.replica') }}</h3>
      <a-table
        size="small"
        :columns="columns"
        :dataSource="replicas"
        :rowKey="record => record.uuid || record.id"
        :pagination="false">
        <template #bodyCell="{ column, record, text }">
          <template v-if="column.key === 'state' || column.key === 'powerstate'">
            <dr-status-pill :status="text || 'UNKNOWN'" />
          </template>
          <template v-else-if="column.key === 'targetvmname'">
            {{ text || record.targetexternalref || '-' }}
          </template>
        </template>
      </a-table>
    </section>
  </div>
</template>

<script>
import DrPlanOverview from '@/views/infra/dr/DrPlanOverview.vue'
import DrStatusPill from '@/components/dr/DrStatusPill.vue'
import DrTopology from '@/components/dr/DrTopology.vue'
import { resolveDrPlanState, resolveDrTestCleanupState } from '@/utils/dr/planState'

export default {
  name: 'DrProtectionInfoTab',
  components: { DrPlanOverview, DrStatusPill, DrTopology },
  props: {
    plan: { type: Object, required: true },
    sourceSite: { type: Object, default: () => ({}) },
    targetSite: { type: Object, default: () => ({}) },
    currentRun: { type: Object, default: () => ({}) },
    latestOperationRun: { type: Object, default: () => ({}) },
    currentSyncCycle: { type: Object, default: () => ({}) },
    latestCompletedSyncCycle: { type: Object, default: () => ({}) },
    currentProtectionRuntime: { type: Object, default: () => ({}) },
    failbackSession: { type: Object, default: () => ({}) },
    replicas: { type: Array, default: () => [] },
    latestCompletedCheckpoint: { type: Object, default: () => ({}) },
    generated: { type: String, default: '' },
    projectionState: { type: String, default: '' },
    lastError: { type: String, default: '' }
  },
  data () {
    return {
      columns: [
        { key: 'targetvmname', title: this.$t('label.dr.target.vm'), dataIndex: 'targetvmname' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'powerstate', title: this.$t('label.dr.power.state'), dataIndex: 'powerstate' },
        { key: 'hypervisortype', title: this.$t('label.hypervisor'), dataIndex: 'hypervisortype' },
        { key: 'activeside', title: this.$t('label.ftctl.active.side'), dataIndex: 'activeside' },
        { key: 'created', title: this.$t('label.created'), dataIndex: 'created' }
      ]
    }
  },
  computed: {
    initialSyncPending () {
      return String(this.plan.state || '').toUpperCase() === 'NEW' &&
        !this.currentRun.id &&
        !this.currentSyncCycle.id &&
        !this.latestCompletedCheckpoint.id &&
        this.replicas.length === 0
    },
    hasTestCheckpointEvidence () {
      return Boolean(this.currentProtectionRuntime.testcheckpointsequence ||
        this.currentProtectionRuntime.testcheckpointsealstate ||
        this.currentProtectionRuntime.testcheckpointintegritystate)
    },
    protectionPlan () {
      const merged = Object.assign({}, this.plan, this.currentProtectionRuntime)
      const action = String(this.currentRun.runtype || this.currentRun.action || this.currentRun.type || '').toUpperCase()
      const runState = String(this.currentRun.state || '').toUpperCase()
      if (action === 'FAILBACK' && runState === 'SUCCEEDED') {
        merged.state = this.plan.state || 'READY'
        merged.runtimestate = 'READY'
        merged.runtimestep = 'target-checkpoint-ready'
        merged.runtimeprogress = 100
        merged.runtimefailbackphase = 'COMPLETED'
        merged.runtimecloudlifecyclestate = 'COMPLETED'
        merged.runtimetransferactivitystate = 'IDLE'
        merged.runtimeimmediatecyclepending = false
        merged.runtimeterminalauthoritative = true
      }
      return merged
    },
    terminalPublicationPending () {
      return this.currentRun.terminalpublicationpending === true ||
        String(this.currentRun.workerstate || '').toUpperCase() === 'TERMINAL_PENDING'
    },
    failbackProtectionResumeState () {
      if (this.failbackSession.protectionresumeverifiedat) return 'READY'
      if (this.failbackSession.protectionresumerequestedat) return 'VERIFYING'
      return 'PENDING'
    },
    failbackTerminalSucceeded () {
      return String(this.failbackSession.state || '').toUpperCase() === 'COMPLETED' &&
        !this.failbackSession.errorcode
    },
    hasFailbackFailureMetadata () {
      if (this.failbackTerminalSucceeded) return false
      return Boolean(this.failbackSession.failurephase || this.failbackSession.failedcomponent ||
        this.failbackSession.errorcode || this.failbackSession.errormessage ||
        (this.failbackSession.driverexitcode !== undefined && this.failbackSession.driverexitcode !== null))
    },
    canonicalFailbackFailure () {
      if (this.terminalPublicationPending) {
        return null
      }
      if (this.failbackTerminalSucceeded) {
        return null
      }
      const liveness = String(this.currentRun.workerlivenessstate || this.currentProtectionRuntime.workerlivenessstate || '').toUpperCase()
      const transfer = String(this.currentRun.transferactivitystate || this.currentProtectionRuntime.transferactivitystate || '').toUpperCase()
      if (this.runtimeReconciling || liveness === 'ALIVE' || ['COPYING', 'VERIFYING'].includes(transfer)) {
        return null
      }
      const runState = String(this.currentRun.state || '').toUpperCase()
      const runCode = this.currentRun.runtimeerrorcode || this.currentRun.errorcode
      if (runCode || ['FAILED', 'ERROR', 'ABORTED'].includes(runState)) {
        return {
          code: runCode || this.$t('label.error'),
          message: this.currentRun.errormessage || this.lastError || ''
        }
      }
      if (!this.currentRun.id && this.failbackSession.errorcode) {
        return {
          code: this.failbackSession.errorcode,
          message: this.failbackSession.errormessage || ''
        }
      }
      return null
    },
    hasCutoverState () {
      return Boolean(this.protectionPlan.currentcutoversessionid ||
        String(this.protectionPlan.authorityside || this.protectionPlan.operatingside ||
          this.protectionPlan.activeside || '').toUpperCase() === 'TARGET')
    },
    replicationActivityState () {
      if (this.placementRecoveryActive) return 'WAITING_SOURCE'
      if (this.currentRun && this.currentRun.id) {
        return this.currentRun.state || 'RUNNING'
      }
      if (this.currentSyncCycle && this.currentSyncCycle.id) {
        return this.currentSyncCycle.state || 'RUNNING'
      }
      return this.currentProtectionRuntime.replicationactivity || 'IDLE'
    },
    activeCycleLabel () {
      if (!this.currentSyncCycle || !this.currentSyncCycle.id) {
        return '-'
      }
      return `#${this.currentSyncCycle.sequence || '-'} / ${this.currentSyncCycle.state || 'UNKNOWN'}`
    },
    hasActiveSyncCycle () {
      return !this.placementRecoveryActive && Boolean(this.currentSyncCycle && this.currentSyncCycle.id)
    },
    displaySyncCycle () {
      return this.hasActiveSyncCycle ? this.currentSyncCycle : (this.latestCompletedSyncCycle || {})
    },
    latestCompletedCycleSequence () {
      return this.latestCompletedSyncCycle.canonicalsequence ||
        this.latestCompletedSyncCycle.sequence || '-'
    },
    displayTransferPayloadBytes () {
      if (this.hasActiveSyncCycle) {
        return this.currentProtectionRuntime.transferpayloadbytes ??
          this.currentRun.transferpayloadbytes ?? this.currentSyncCycle.transferpayloadbytes
      }
      return this.latestCompletedSyncCycle.transferpayloadbytes ??
        this.currentProtectionRuntime.transferpayloadbytes
    },
    displayWorkerLiveness () {
      if (this.placementRecoveryActive) return 'WAITING_SOURCE'
      if (!this.hasActiveSyncCycle && !this.currentRun.id) return 'IDLE'
      return this.currentProtectionRuntime.workerlivenessstate ||
        this.currentRun.workerlivenessstate || 'UNKNOWN'
    },
    runtimeControlReady () {
      const protocol = Number(this.protectionPlan.runtimecontrolprotocolversion)
      const generation = Number(this.protectionPlan.runtimecontrolgeneration)
      const acknowledged = Number(this.protectionPlan.runtimecontrolackgeneration)
      return protocol >= 2 && Number.isFinite(generation) && Number.isFinite(acknowledged) && acknowledged >= generation
    },
    hasCycleCommitState () {
      return Boolean(this.displaySyncCycle.id || this.plan.datacommitstate ||
        this.plan.cycleretrymode || this.plan.datacopied !== undefined)
    },
    hasSourceSnapshotState () {
      return Boolean(this.plan.runtimesourcesnapshotlifecyclestate || this.plan.runtimesourcesnapshotlastref)
    },
    cycleDataCopied () {
      return this.hasActiveSyncCycle ? this.plan.datacopied === true : this.completedCycleDurable
    },
    cycleMetadataCommitted () {
      return this.hasActiveSyncCycle ? this.plan.metadatacommitted === true : this.completedCycleDurable
    },
    cycleTargetDurable () {
      return this.hasActiveSyncCycle ? this.plan.targetdurable === true : this.completedCycleDurable
    },
    cycleCommitState () {
      return this.displaySyncCycle.commitstate || this.plan.datacommitstate || 'UNKNOWN'
    },
    cycleRetryMode () {
      return this.hasActiveSyncCycle ? (this.plan.cycleretrymode || '-') : '-'
    },
    cycleFailedComponent () {
      if (!this.hasActiveSyncCycle && this.completedCycleDurable) return ''
      return this.plan.failedcomponent || this.displaySyncCycle.failedcomponent || ''
    },
    completedCycleDurable () {
      if (this.hasActiveSyncCycle) return false
      const cycleState = String(this.latestCompletedSyncCycle.state || '').toUpperCase()
      const commitState = String(this.latestCompletedSyncCycle.commitstate || '').toUpperCase()
      return ['READY', 'COMPLETED', 'SUCCEEDED'].includes(cycleState) &&
        ['LOCAL_DURABLE', 'TARGET_DURABLE', 'DURABLE', 'COMMITTED'].includes(commitState)
    },
    currentNbdTeardownState () {
      if (this.placementRecoveryActive && this.latestCompletedSyncCycle.nbdteardownstate) {
        return this.latestCompletedSyncCycle.nbdteardownstate
      }
      return this.currentProtectionRuntime.nbdteardownstate ||
        this.currentSyncCycle.nbdteardownstate ||
        this.latestCompletedSyncCycle.nbdteardownstate ||
        'UNKNOWN'
    },
    nbdRecoveryRequired () {
      return String(this.currentNbdTeardownState).toUpperCase() === 'QUARANTINED' ||
        Number(this.currentProtectionRuntime.nbdquarantineddevicecount || 0) > 0
    },
    nbdRecoveryMessage () {
      return this.currentProtectionRuntime.nbdteardownerrormessage ||
        this.currentProtectionRuntime.nbdteardownerrorcode ||
        this.$t('message.dr.nbd.recovery.required.detail')
    },
    runtimeReconciling () {
      const state = String(this.currentProtectionRuntime.reconciliationstate || this.plan.reconciliationstate || '').toUpperCase()
      return ['RECONCILING', 'DEAD_CONFIRMING'].includes(state) || this.currentRun.reconciliationrequired === true
    },
    placementRecoveryActive () {
      const resolved = resolveDrPlanState(this.protectionPlan, this.currentRun)
      const schedulerHealth = String(this.currentProtectionRuntime.schedulerhealthstate ||
        this.currentProtectionRuntime.schedulerhealth || this.protectionPlan.schedulerhealth || '').toUpperCase()
      const runtimeError = String(this.currentProtectionRuntime.runtimeerrorcode ||
        this.currentProtectionRuntime.errorcode || this.protectionPlan.runtimeerrorcode ||
        this.protectionPlan.errorcode || '').toUpperCase()
      return ['WAITING_SOURCE_RECOVERY', 'RECOVERY_PENDING', 'RECOVERING'].includes(resolved) ||
        schedulerHealth === 'WAITING_SOURCE' ||
        ['DR_SOURCE_SITE_UNAVAILABLE', 'DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE',
          'DR_QCOW2_OFFLINE_SOURCE_BUSY'].includes(runtimeError)
    },
    displayLastError () {
      if (resolveDrTestCleanupState(this.protectionPlan, this.currentRun)) {
        return ''
      }
      if (this.placementRecoveryActive && String(this.lastError).includes('SOURCE_HARDWARE_CHANGED')) {
        return ''
      }
      return this.lastError
    },
    hasNbdTeardownEvidence () {
      return Boolean(this.currentProtectionRuntime.nbdteardownstate ||
        this.latestCompletedSyncCycle.nbdteardownstate)
    }
  },
  methods: {
    generationPair (generation, acknowledgedGeneration) {
      const requested = generation === null || generation === undefined ? '-' : generation
      const acknowledged = acknowledgedGeneration === null || acknowledgedGeneration === undefined
        ? '-' : acknowledgedGeneration
      return `${requested} / ${acknowledged}`
    },
    formatBytes (value) {
      const numeric = Number(value)
      if (!Number.isFinite(numeric) || numeric < 0) return '-'
      if (numeric < 1024) return `${numeric} B`
      const units = ['KiB', 'MiB', 'GiB', 'TiB']
      let size = numeric
      let unit = -1
      do {
        size /= 1024
        unit += 1
      } while (size >= 1024 && unit < units.length - 1)
      return `${size.toFixed(size >= 10 ? 1 : 2)} ${units[unit]}`
    },
    formatEpoch (value) {
      const numeric = Number(value)
      if (!Number.isFinite(numeric) || numeric <= 0) return '-'
      return new Date(numeric).toLocaleString()
    },
    formatDuration (value) {
      const numeric = Number(value)
      if (!Number.isFinite(numeric) || numeric < 0) return '-'
      if (numeric < 1000) return `${numeric} ms`
      return `${(numeric / 1000).toFixed(numeric < 10000 ? 2 : 1)} s`
    },
    formatSeconds (value) {
      const numeric = Number(value)
      if (!Number.isFinite(numeric) || numeric < 0) return '-'
      if (numeric < 60) return `${Math.round(numeric)}s`
      if (numeric < 3600) return `${Math.round(numeric / 60)}m`
      return `${Math.round(numeric / 3600)}h`
    }
  }
}
</script>

<style lang="less">
.cross-dr-protection-info {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.cross-dr-protection-info__cache {
  color: var(--cross-dr-text-secondary, rgba(0, 0, 0, 0.45));
  font-size: 12px;
}

.cross-dr-protection-info__section {
  min-width: 0;
  padding-top: 4px;
  border-top: 1px solid var(--cross-dr-border, #e8e8e8);
}

.cross-dr-protection-info__section h3 {
  margin: 10px 0 14px;
  color: var(--cross-dr-text, rgba(0, 0, 0, 0.85));
  font-size: 14px;
  font-weight: 600;
}

body.dark-mode .cross-dr-protection-info {
  --cross-dr-border: rgba(255, 255, 255, 0.12);
  --cross-dr-text: rgba(255, 255, 255, 0.86);
  --cross-dr-text-secondary: rgba(255, 255, 255, 0.58);
}
</style>
