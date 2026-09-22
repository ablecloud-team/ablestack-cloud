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
  <a-spin :spinning="loading">
    <div class="cross-dr-page cross-dr-vm-plan-view">
      <div class="cross-dr-tab-toolbar">
        <router-link
          v-if="associations.length === 1"
          :to="{ path: '/drplan/' + associations[0].planid }">
          <a-button type="primary" size="small">
            <template #icon><BranchesOutlined /></template>
            {{ $t('label.dr.plan.open') }}
          </a-button>
        </router-link>
        <router-link v-else :to="{ path: '/drplan' }">
          <a-button type="primary" size="small">
            <template #icon><BranchesOutlined /></template>
            {{ $t('label.dr.plan.list.open') }}
          </a-button>
        </router-link>
        <a-button size="small" :loading="loading" @click="$emit('refresh')">
          <template #icon><ReloadOutlined /></template>
          {{ $t('label.refresh') }}
        </a-button>
      </div>
      <a-alert type="info" show-icon :message="$t('message.dr.vm.local.scope')" />

      <a-alert
        v-if="view.relationconflict"
        type="error"
        show-icon
        :message="$t('message.dr.vm.relationship.conflict')" />

      <a-alert
        v-if="loadError"
        type="error"
        show-icon
        :message="$t('message.dr.vm.view.load.failed')" />

      <div v-if="!view.configured" class="cross-dr-vm-empty">
        <a-alert
          type="info"
          show-icon
          :message="$t('message.dr.vm.not.managed.here')" />
      </div>

      <template v-else>
        <section
          v-for="association in associations"
          :key="association.planid + ':' + association.relationshiprole"
          class="cross-dr-vm-association">
        <header class="cross-dr-vm-association__header">
          <div class="cross-dr-heading">
            <span :class="['cross-dr-vm-role', roleClass(association.relationshiprole)]">
              {{ relationshipLabel(association.relationshiprole) }}
            </span>
            <router-link class="cross-dr-vm-plan-link" :to="{ path: '/drplan/' + association.planid }">
              {{ association.planname || association.planid }}
            </router-link>
          </div>
          <div class="cross-dr-vm-association__states">
            <span class="cross-dr-vm-authority">{{ authorityLabel(association.authorityrole) }}</span>
            <dr-status-pill :status="association.protectionstate || association.planstate" />
          </div>
        </header>

        <div class="cross-dr-vm-topology">
          <div :class="endpointClass(association, 'SOURCE')">
            <div class="cross-dr-vm-endpoint__role">{{ $t('label.dr.source.vm') }}</div>
            <div class="cross-dr-vm-endpoint__name">{{ association.sourcevmname || association.sourcevmid || '-' }}</div>
            <div class="cross-dr-vm-endpoint__meta">{{ association.sourcesitename || association.sourcesiteid || '-' }}</div>
            <dr-status-pill v-if="association.sourcevmstate" :status="association.sourcevmstate" />
          </div>

          <ArrowRightOutlined class="cross-dr-vm-topology__arrow" />

          <div :class="endpointClass(association, 'TARGET')">
            <div class="cross-dr-vm-endpoint__role">{{ targetRoleLabel(association.relationshiprole) }}</div>
            <div class="cross-dr-vm-endpoint__name">{{ association.targetvmname || association.targetvmid || $t('label.dr.target.pending') }}</div>
            <div class="cross-dr-vm-endpoint__meta">{{ association.targetsitename || association.targetsiteid || '-' }}</div>
            <dr-status-pill :status="association.targetmaterializationstate || 'PENDING'" />
          </div>
        </div>

        <div class="cross-dr-overview__kpis">
          <dr-rpo-kpi
            :label="$t('label.dr.target.rpo')"
            :seconds="association.rpoageseconds"
            :targetSeconds="association.rposeconds" />
          <div class="cross-dr-kpi">
            <div class="cross-dr-kpi__label">{{ $t('label.dr.replication.activity') }}</div>
            <div class="cross-dr-kpi__status"><dr-status-pill :status="association.replicationactivity || 'UNKNOWN'" /></div>
            <div class="cross-dr-kpi__meta">{{ association.direction ? $t(directionLabel(association.direction)) : '-' }}</div>
          </div>
          <div class="cross-dr-kpi">
            <div class="cross-dr-kpi__label">{{ $t('label.dr.last.target.durable.at') }}</div>
            <div class="cross-dr-kpi__value cross-dr-kpi__value--small">{{ association.lasttargetdurableat || '-' }}</div>
            <div class="cross-dr-kpi__meta">{{ $t('label.dr.freshness') }}: <dr-status-pill :status="association.freshnessstate || 'UNKNOWN'" /></div>
          </div>
        </div>

        <div v-if="association.latestrunid" class="cross-dr-vm-latest-run">
          <span class="cross-dr-vm-latest-run__label">{{ $t('label.dr.latest.operation') }}</span>
          <span>{{ association.latestruntype || '-' }}</span>
          <dr-status-pill :status="association.latestrunstate" />
        </div>

        <footer class="cross-dr-vm-association__footer">
          <span>{{ $t('label.dr.persisted.at') }}: {{ association.dataupdatedat || '-' }}</span>
        </footer>
        </section>
      </template>
    </div>
  </a-spin>
</template>

<script>
import DrRpoKpi from '@/components/dr/DrRpoKpi.vue'
import DrStatusPill from '@/components/dr/DrStatusPill.vue'

export default {
  name: 'DrPlanVmTab',
  emits: ['refresh'],
  components: {
    DrRpoKpi,
    DrStatusPill
  },
  props: {
    view: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    },
    loadError: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    associations () {
      return this.view.association || []
    }
  },
  methods: {
    relationshipLabel (role) {
      const labels = {
        SOURCE: 'label.dr.vm.role.source',
        RECOVERY_TARGET: 'label.dr.vm.role.recovery.target',
        TEST_TARGET: 'label.dr.vm.role.test.target'
      }
      return this.$t(labels[role] || 'label.dr.vm.role.unknown')
    },
    authorityLabel (role) {
      const labels = {
        ACTIVE: 'label.dr.vm.authority.active',
        STANDBY: 'label.dr.vm.authority.standby',
        TEST_ISOLATED: 'label.dr.vm.authority.test'
      }
      return this.$t(labels[role] || 'label.dr.vm.authority.unknown')
    },
    targetRoleLabel (role) {
      return this.$t(role === 'TEST_TARGET' ? 'label.dr.test.vm' : 'label.dr.target.vm')
    },
    directionLabel (direction) {
      const labels = {
        KVM_TO_KVM: 'label.dr.direction.kvm.to.kvm',
        KVM_TO_VMWARE: 'label.dr.direction.kvm.to.vmware',
        VMWARE_TO_VMWARE: 'label.dr.direction.vmware.to.vmware',
        VMWARE_TO_KVM: 'label.dr.direction.vmware.to.kvm'
      }
      return labels[String(direction || '').toUpperCase()] || direction
    },
    roleClass (role) {
      return `cross-dr-vm-role--${String(role || 'unknown').toLowerCase().replace(/_/g, '-')}`
    },
    endpointClass (association, side) {
      const viewed = (side === 'SOURCE' && association.relationshiprole === 'SOURCE') ||
        (side === 'TARGET' && ['RECOVERY_TARGET', 'TEST_TARGET'].includes(association.relationshiprole))
      return ['cross-dr-vm-endpoint', viewed ? 'cross-dr-vm-endpoint--viewed' : '']
    }
  }
}
</script>

<style lang="less">
.cross-dr-vm-plan-view {
  color: var(--cross-dr-text);
}

.cross-dr-vm-empty {
  width: 100%;
}

.cross-dr-vm-association {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--cross-dr-border);
  border-radius: 6px;
  background: var(--cross-dr-surface);
}

.cross-dr-vm-association__header,
.cross-dr-vm-association__footer,
.cross-dr-vm-association__states,
.cross-dr-vm-latest-run {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cross-dr-vm-association__header,
.cross-dr-vm-association__footer {
  justify-content: space-between;
}

.cross-dr-vm-role {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  border: 1px solid var(--cross-dr-info-border);
  border-radius: 4px;
  background: var(--cross-dr-info-bg);
  color: var(--cross-dr-info-text);
  font-size: 12px;
  font-weight: 600;
}

.cross-dr-vm-role--recovery-target {
  border-color: var(--cross-dr-warning-border);
  background: var(--cross-dr-warning-bg);
  color: var(--cross-dr-warning-text);
}

.cross-dr-vm-role--test-target {
  border-color: var(--cross-dr-success-border);
  background: var(--cross-dr-success-bg);
  color: var(--cross-dr-success-text);
}

.cross-dr-vm-plan-link,
.cross-dr-vm-endpoint__name {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 600;
}

.cross-dr-vm-authority,
.cross-dr-vm-association__footer,
.cross-dr-vm-latest-run__label,
.cross-dr-vm-endpoint__role,
.cross-dr-vm-endpoint__meta {
  color: var(--cross-dr-text-secondary);
  font-size: 12px;
}

.cross-dr-vm-topology {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  align-items: stretch;
  gap: 10px;
}

.cross-dr-vm-endpoint {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--cross-dr-border);
  border-radius: 6px;
  background: var(--cross-dr-surface-muted);
}

.cross-dr-vm-endpoint--viewed {
  border-color: var(--cross-dr-info-border);
  box-shadow: inset 3px 0 0 var(--cross-dr-info-border);
}

.cross-dr-vm-topology__arrow {
  align-self: center;
  color: var(--cross-dr-text-secondary);
}

.cross-dr-vm-latest-run {
  min-height: 36px;
  padding: 7px 10px;
  border-top: 1px solid var(--cross-dr-border);
  border-bottom: 1px solid var(--cross-dr-border);
}

@media (max-width: 720px) {
  .cross-dr-vm-topology {
    grid-template-columns: minmax(0, 1fr);
  }

  .cross-dr-vm-topology__arrow {
    justify-self: center;
    transform: rotate(90deg);
  }

  .cross-dr-vm-association__header,
  .cross-dr-vm-association__footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
