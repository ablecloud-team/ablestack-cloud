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
  <a-spin :spinning="storageService.initialLoading">
    <a-tabs
      class="storage-service-tabs"
      :activeKey="currentTab"
      tabPosition="top"
      :animated="false"
      @change="handleChangeTab">
      <a-tab-pane :tab="$t('label.details')" key="details">
        <DetailsTab :resource="dataResource" :loading="loading" />
        <div v-if="hasStorageServiceApi" class="storage-service storage-service--overview">
          <h3 class="storage-service__section-title">{{ $t('label.storage.service.overview') }}</h3>
          <a-alert
            v-if="!storageService.instance && !storageService.loading"
            class="storage-service__alert"
            type="warning"
            show-icon
            :message="$t('message.storage.service.no.mirror')"
            :description="$t('message.storage.service.no.mirror.description')" />
          <template v-if="storageService.instance">
            <dl class="storage-detail-list">
              <div class="storage-detail-list__row">
                <dt>{{ $t('label.storage.service.active.services') }}</dt>
                <dd>
                  <template v-if="activeServiceTypes.length > 0">
                    <a-tag v-for="service in activeServiceTypes" :key="service" color="blue">{{ service }}</a-tag>
                  </template>
                  <template v-else>-</template>
                </dd>
              </div>
            </dl>
          </template>
        </div>
      </a-tab-pane>

      <a-tab-pane v-if="hasStorageServiceApi" tab="NFS" key="nfs">
        <div class="storage-service storage-service--protocol" :class="{ 'storage-service--wide': protocolWideLayout }">
          <div class="storage-protocol-topbar">
            <protocol-header protocol="NFS" />
            <a-space v-if="storageService.instance" class="protocol-toolbar protocol-toolbar--right" wrap>
              <a-button @click="toggleProtocolWideLayout">
                <template #icon><FullscreenExitOutlined v-if="protocolWideLayout" /><FullscreenOutlined v-else /></template>
                {{ protocolWideLayout ? $t('label.storage.service.default.view') : $t('label.storage.service.wide.view') }}
              </a-button>
              <a-button type="primary" @click="openActionModal('enableProtocol', { protocol: 'NFS' })">
                <template #icon><PoweroffOutlined /></template>
                {{ $t('label.storage.service.enable.protocol') }}
              </a-button>
              <a-button danger @click="openActionModal('deleteEndpoint', { protocol: 'NFS' })">
                <template #icon><DeleteOutlined /></template>
                {{ $t('label.storage.service.delete.endpoint') }}
              </a-button>
              <a-button @click="openActionModal('nfsExport')">
                <template #icon><PlusOutlined /></template>
                {{ $t('label.storage.service.create.nfs.export') }}
              </a-button>
              <a-button :loading="storageService.refreshing" @click="fetchStorageServiceData">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.refresh') }}
              </a-button>
            </a-space>
          </div>
          <template v-if="storageService.instance">
            <a-alert
              v-if="storageIdentityDrift"
              class="storage-service__alert"
              type="warning"
              show-icon
              :message="$t('message.storage.service.nic.identity.drift')"
              :description="storageIdentityWarning" />
            <div v-if="canRepairStorageIdentity" class="storage-identity-repair-action">
              <a-button size="small" :loading="identityRepair.loading" @click="openStorageIdentityRepair">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.nic.identity.repair') }}
              </a-button>
            </div>
            <div class="storage-protocol-grid">
              <section class="storage-panel storage-panel--connection">
                <div class="storage-panel__title">{{ $t('label.storage.service.connection.info') }}</div>
                <p class="storage-panel__description">{{ $t('message.storage.service.nfs.connection.generic') }}</p>
                <div v-for="command in nfsConnectionCommands" :key="command" class="command-line command-line--copyable">
                  {{ command }}
                </div>
              </section>
              <section class="storage-panel storage-panel--status">
                <div class="storage-panel__title">{{ $t('label.storage.service.status.summary') }}</div>
                <dl class="storage-kv storage-kv--compact">
                  <dt>{{ $t('label.storage.service.endpoint') }}</dt>
                  <dd><ellipsis-text :value="serviceEndpointSummary || '-'" /></dd>
                  <dt>{{ $t('label.storage.service.monitor.cache') }}</dt>
                  <dd>
                    <a-tag :color="monitorCacheColor">{{ monitorCacheLabel }}</a-tag>
                  </dd>
                  <dt>{{ $t('label.storage.service.last.refresh') }}</dt>
                  <dd><ellipsis-text :value="monitorCacheTimestamp" /></dd>
                </dl>
              </section>
            </div>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.listener.groups') }}</h4>
                  <p>{{ $t('message.storage.service.listener.groups.table', { protocol: 'NFS' }) }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="protocolListenerColumns"
                :dataSource="nfsListenerRows"
                :pagination="false"
                :scroll="{ x: 1260 }"
                :locale="storageTableLocale('message.storage.service.no.listeners')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger :disabled="!record.canDelete" :title="record.deleteDisabledReason || ''" @click="openDeleteConfirm('protocolListener', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <a-alert
              v-if="nfsFailedExportMessages.length"
              class="storage-service__alert"
              type="error"
              show-icon
              :message="$t('message.storage.service.nfs.initial.setup.failed')">
              <template #description>
                <ul class="storage-service-error-list">
                  <li v-for="message in nfsFailedExportMessages" :key="message">{{ message }}</li>
                </ul>
              </template>
            </a-alert>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.nfs.exports') }}</h4>
                  <p>{{ $t('message.storage.service.nfs.exports.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nfsExportColumns"
                :dataSource="nfsExportRows"
                :pagination="false"
                :scroll="{ x: 1580 }"
                :locale="storageTableLocale('message.storage.service.no.nfs.exports')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" @click="openActionModal('editNfsExport', record)">
                          <template #icon><EditOutlined /></template>
                          {{ $t('label.edit') }}
                        </a-button>
                        <a-button size="small" @click="openActionModal('resizeShare', { id: record.id })">
                          <template #icon><ExpandAltOutlined /></template>
                          {{ $t('label.storage.service.resize.file.share') }}
                        </a-button>
                        <a-button size="small" danger @click="openDeleteModal('nfsExport', record)">
                          <template #icon><DeleteOutlined /></template>
                          {{ $t('label.delete') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.access.rules') }}</h4>
                  <p>{{ $t('message.storage.service.nfs.acls.table') }}</p>
                </div>
                <a-space class="storage-section-actions">
                  <a-button @click="openActionModal('nfsAcl')">
                    <template #icon><SafetyCertificateOutlined /></template>
                    {{ $t('label.storage.service.create.nfs.acl') }}
                  </a-button>
                </a-space>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nfsAclColumns"
                :dataSource="nfsAclRows"
                :pagination="false"
                :scroll="{ x: 1360 }"
                :locale="storageTableLocale('message.storage.service.no.access.rules')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space v-if="!record.implicit" class="storage-table-actions__space">
                        <a-button size="small" @click="openActionModal('editNfsAcl', record)">
                          <template #icon><EditOutlined /></template>
                          {{ $t('label.edit') }}
                        </a-button>
                        <a-button size="small" danger @click="openDeleteModal('nfsAcl', record)">
                          <template #icon><DeleteOutlined /></template>
                          {{ $t('label.delete') }}
                        </a-button>
                      </a-space>
                      <span v-else class="storage-table-actions__empty">-</span>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.backing.volumes') }}</h4>
                  <p>{{ $t('message.storage.service.nfs.volumes.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nfsVolumeColumns"
                :dataSource="nfsVolumeRows"
                :pagination="false"
                :scroll="{ x: 1490 }"
                :locale="storageTableLocale('message.storage.service.no.backing.volumes')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" :disabled="!record.resizeAllowed" :title="record.resizeDisabledReason || ''" @click="openActionModal('resizeBackingVolume', record)">
                          <template #icon><ExpandAltOutlined /></template>
                          {{ $t('label.storage.service.resize.volume') }}
                        </a-button>
                        <a-button size="small" danger :disabled="!record.detachAllowed" @click="openActionModal('detachBackingVolume', record)">
                          <template #icon><DisconnectOutlined /></template>
                          {{ $t('label.storage.service.detach.backing.volume') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.sessions') }}</h4>
                  <p>{{ $t('message.storage.service.nfs.sessions.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nfsSessionColumns"
                :dataSource="nfsSessionRows"
                :pagination="false"
                :scroll="{ x: 1160 }"
                :locale="storageTableLocale('message.storage.service.no.sessions')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger @click="openActionModal('disconnectSession', record)">
                        <template #icon><DisconnectOutlined /></template>
                        {{ $t('label.storage.service.disconnect.session') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>
          </template>
        </div>
      </a-tab-pane>

      <a-tab-pane v-if="hasStorageServiceApi" tab="SMB" key="smb">
        <div class="storage-service storage-service--protocol" :class="{ 'storage-service--wide': protocolWideLayout }">
          <div class="storage-protocol-topbar">
            <protocol-header protocol="SMB" />
            <a-space v-if="storageService.instance" class="protocol-toolbar protocol-toolbar--right" wrap>
              <a-button @click="toggleProtocolWideLayout">
                <template #icon><FullscreenExitOutlined v-if="protocolWideLayout" /><FullscreenOutlined v-else /></template>
                {{ protocolWideLayout ? $t('label.storage.service.default.view') : $t('label.storage.service.wide.view') }}
              </a-button>
              <a-button type="primary" @click="openActionModal('enableProtocol', { protocol: 'SMB' })">
                <template #icon><PoweroffOutlined /></template>
                {{ $t('label.storage.service.enable.protocol') }}
              </a-button>
              <a-button @click="openActionModal('smbShare')">
                <template #icon><PlusOutlined /></template>
                {{ $t('label.storage.service.create.smb.share') }}
              </a-button>
              <a-button v-if="!isSmbAdConfigured" @click="openActionModal('adJoin')">
                <template #icon><LinkOutlined /></template>
                {{ $t('label.storage.service.join.ad.domain') }}
              </a-button>
              <a-button v-else :loading="actionLoading.adStatus" @click="checkAdDomainStatus">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.check.ad.domain.status') }}
              </a-button>
              <a-button :loading="storageService.refreshing" @click="fetchStorageServiceData">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.refresh') }}
              </a-button>
            </a-space>
          </div>
          <template v-if="storageService.instance">
            <a-alert
              v-if="storageIdentityDrift"
              class="storage-service__alert"
              type="warning"
              show-icon
              :message="$t('message.storage.service.nic.identity.drift')"
              :description="storageIdentityWarning" />
            <div v-if="canRepairStorageIdentity" class="storage-identity-repair-action">
              <a-button size="small" :loading="identityRepair.loading" @click="openStorageIdentityRepair">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.nic.identity.repair') }}
              </a-button>
            </div>
            <a-alert
              v-if="smbSetupIncomplete"
              class="storage-service__alert"
              type="warning"
              show-icon
              :message="$t('message.storage.service.smb.setup.incomplete')" />
            <div class="storage-protocol-grid">
              <section class="storage-panel storage-panel--connection">
                <div class="storage-panel__title">{{ $t('label.storage.service.connection.info') }}</div>
                <p class="storage-panel__description">{{ $t('message.storage.service.smb.connection.generic') }}</p>
                <div v-for="command in smbConnectionCommands" :key="command" class="command-line command-line--copyable">
                  {{ command }}
                </div>
              </section>
              <section class="storage-panel storage-panel--status">
                <div class="storage-panel__title">{{ $t('label.storage.service.status.summary') }}</div>
                <dl class="storage-kv storage-kv--compact">
                  <dt>{{ $t('label.storage.service.endpoint') }}</dt>
                  <dd><ellipsis-text :value="smbEndpoint" code /></dd>
                  <dt>{{ $t('label.storage.service.smb.identity.mode') }}</dt>
                  <dd><ellipsis-text :value="smbIdentityMode" /></dd>
                  <dt>{{ $t('label.storage.service.domain.join.state') }}</dt>
                  <dd><ellipsis-text :value="smbDomainState" /></dd>
                  <dt>{{ $t('label.storage.service.domain.health.state') }}</dt>
                  <dd><ellipsis-text :value="smbDomainHealthState" /></dd>
                  <dt>{{ $t('label.storage.service.domain.trust.state') }}</dt>
                  <dd><ellipsis-text :value="smbTrustVerifiedLabel" /></dd>
                  <dt v-if="smbDomainErrorSummary">{{ $t('label.storage.service.domain.join.error') }}</dt>
                  <dd v-if="smbDomainErrorSummary">
                    <a-tag color="red"><ellipsis-text :value="smbDomainErrorSummary" /></a-tag>
                  </dd>
                  <dt>{{ $t('label.storage.service.daemon.state') }}</dt>
                  <dd><ellipsis-text :value="smbDaemonState" /></dd>
                  <dt>{{ $t('label.storage.service.monitor.cache') }}</dt>
                  <dd>
                    <a-tag :color="monitorCacheColor">{{ monitorCacheLabel }}</a-tag>
                  </dd>
                  <dt>{{ $t('label.storage.service.last.refresh') }}</dt>
                  <dd><ellipsis-text :value="monitorCacheTimestamp" /></dd>
                </dl>
              </section>
            </div>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.listener.groups') }}</h4>
                  <p>{{ $t('message.storage.service.listener.groups.table', { protocol: 'SMB' }) }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="protocolListenerColumns"
                :dataSource="smbListenerRows"
                :pagination="false"
                :scroll="{ x: 1260 }"
                :locale="storageTableLocale('message.storage.service.no.listeners')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger :disabled="!record.canDelete" :title="record.deleteDisabledReason || ''" @click="openDeleteConfirm('protocolListener', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.smb.shares') }}</h4>
                  <p>{{ $t('message.storage.service.smb.shares.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="smbShareColumns"
                :dataSource="smbShareRows"
                :pagination="false"
                :scroll="{ x: 1720 }"
                :locale="storageTableLocale('message.storage.service.no.smb.shares')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" @click="openActionModal('editSmbShare', record)">
                        <template #icon><EditOutlined /></template>
                        {{ $t('label.edit') }}
                      </a-button>
                      <a-button size="small" @click="openActionModal('resizeShare', { id: record.id })">
                        <template #icon><ExpandAltOutlined /></template>
                        {{ $t('label.storage.service.resize.file.share') }}
                      </a-button>
                      <a-button size="small" danger @click="openDeleteModal('smbShare', record)">
                          <template #icon><DeleteOutlined /></template>
                          {{ $t('label.delete') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.smb.access.accounts') }}</h4>
                  <p>{{ $t('message.storage.service.smb.acls.table') }}</p>
                </div>
                <a-space class="storage-section-actions">
                  <a-button @click="openActionModal('smbAcl')">
                    <template #icon><SafetyCertificateOutlined /></template>
                    {{ $t('label.storage.service.create.smb.acl') }}
                  </a-button>
                </a-space>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="smbAclColumns"
                :dataSource="smbAclRows"
                :pagination="false"
                :scroll="{ x: 1230 }"
                :locale="storageTableLocale('message.storage.service.no.access.rules')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" @click="openActionModal('editSmbAcl', record)">
                        <template #icon><EditOutlined /></template>
                        {{ $t('label.edit') }}
                      </a-button>
                      <a-button size="small" danger @click="openDeleteModal('smbAcl', record)">
                          <template #icon><DeleteOutlined /></template>
                          {{ $t('label.delete') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.smb.identity') }}</h4>
                  <p>{{ $t('message.storage.service.smb.identity.table') }}</p>
                </div>
                <a-space class="storage-section-actions" wrap>
                  <a-button v-if="!isSmbAdConfigured" size="small" @click="openActionModal('adJoin')">
                    <template #icon><LinkOutlined /></template>
                    {{ $t('label.storage.service.join.ad.domain') }}
                  </a-button>
                  <a-button v-if="isSmbAdConfigured" size="small" :loading="actionLoading.adStatus" @click="checkAdDomainStatus">
                    <template #icon><SafetyCertificateOutlined /></template>
                    {{ $t('label.storage.service.check.ad.domain.status') }}
                  </a-button>
                  <a-button v-if="isSmbAdConfigured" size="small" @click="openActionModal('adRejoin')">
                    <template #icon><ReloadOutlined /></template>
                    {{ $t('label.storage.service.rejoin.ad.domain') }}
                  </a-button>
                  <a-button v-if="isSmbAdConfigured" size="small" danger @click="openActionModal('adLeave')">
                    <template #icon><DisconnectOutlined /></template>
                    {{ $t('label.storage.service.leave.ad.domain') }}
                  </a-button>
                </a-space>
              </div>
              <dl class="storage-detail-list">
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.smb.identity.mode') }}</dt>
                  <dd><ellipsis-text :value="smbIdentityMode" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.ad.domain') }}</dt>
                  <dd><ellipsis-text :value="smbDomainName" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.workgroup') }}</dt>
                  <dd><ellipsis-text :value="smbWorkgroup" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.domain.join.state') }}</dt>
                  <dd><ellipsis-text :value="smbDomainState" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.domain.health.state') }}</dt>
                  <dd><ellipsis-text :value="smbDomainHealthState" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.domain.trust.state') }}</dt>
                  <dd><ellipsis-text :value="smbTrustVerifiedLabel" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.dns.servers') }}</dt>
                  <dd><ellipsis-text :value="smbDnsServers" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.smb.realm') }}</dt>
                  <dd><ellipsis-text :value="smbRealm" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.smb.netbios.name') }}</dt>
                  <dd><ellipsis-text :value="smbNetbiosName" /></dd>
                </div>
                <div class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.organizational.unit') }}</dt>
                  <dd><ellipsis-text :value="smbOrganizationalUnit" /></dd>
                </div>
                <div v-if="smbDomainErrorSummary" class="storage-detail-list__row">
                  <dt>{{ $t('label.storage.service.domain.join.error') }}</dt>
                  <dd><ellipsis-text :value="smbDomainErrorSummary" /></dd>
                </div>
              </dl>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.backing.volumes') }}</h4>
                  <p>{{ $t('message.storage.service.smb.volumes.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="smbVolumeColumns"
                :dataSource="smbVolumeRows"
                :pagination="false"
                :scroll="{ x: 1490 }"
                :locale="storageTableLocale('message.storage.service.no.backing.volumes')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" :disabled="!record.resizeAllowed" :title="record.resizeDisabledReason || ''" @click="openActionModal('resizeBackingVolume', record)">
                        <template #icon><ExpandAltOutlined /></template>
                        {{ $t('label.storage.service.resize.volume') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.sessions') }}</h4>
                  <p>{{ $t('message.storage.service.smb.sessions.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="smbSessionColumns"
                :dataSource="smbSessionRows"
                :pagination="false"
                :scroll="{ x: 1320 }"
                :locale="storageTableLocale('message.storage.service.no.sessions')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger @click="openActionModal('disconnectSession', record)">
                        <template #icon><DisconnectOutlined /></template>
                        {{ $t('label.storage.service.disconnect.session') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>
          </template>
        </div>
      </a-tab-pane>

      <a-tab-pane v-if="hasStorageServiceApi" tab="iSCSI" key="iscsi">
        <div class="storage-service storage-service--protocol" :class="{ 'storage-service--wide': protocolWideLayout }">
          <div class="storage-protocol-topbar">
            <protocol-header protocol="ISCSI" />
            <a-space v-if="storageService.instance" class="protocol-toolbar protocol-toolbar--right" wrap>
              <a-button @click="toggleProtocolWideLayout">
                <template #icon><FullscreenExitOutlined v-if="protocolWideLayout" /><FullscreenOutlined v-else /></template>
                {{ protocolWideLayout ? $t('label.storage.service.default.view') : $t('label.storage.service.wide.view') }}
              </a-button>
              <a-button type="primary" @click="openActionModal('enableProtocol', { protocol: 'ISCSI' })">
                <template #icon><PoweroffOutlined /></template>
                {{ $t('label.storage.service.enable.protocol') }}
              </a-button>
              <a-button @click="openActionModal('iscsiTarget')">
                <template #icon><PlusOutlined /></template>
                {{ $t('label.storage.service.create.iscsi.target') }}
              </a-button>
              <a-button @click="openActionModal('iscsiAcl')">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.create.iscsi.acl') }}
              </a-button>
              <a-button :loading="storageService.refreshing" @click="fetchStorageServiceData">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.refresh') }}
              </a-button>
            </a-space>
          </div>
          <template v-if="storageService.instance">
            <a-alert
              v-if="storageIdentityDrift"
              class="storage-service__alert"
              type="warning"
              show-icon
              :message="$t('message.storage.service.nic.identity.drift')"
              :description="storageIdentityWarning" />
            <div v-if="canRepairStorageIdentity" class="storage-identity-repair-action">
              <a-button size="small" :loading="identityRepair.loading" @click="openStorageIdentityRepair">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.nic.identity.repair') }}
              </a-button>
            </div>
            <div class="storage-protocol-grid">
              <section class="storage-panel storage-panel--connection">
                <div class="storage-panel__title">{{ $t('label.storage.service.connection.info') }}</div>
                <p class="storage-panel__description">{{ $t('message.storage.service.iscsi.connection.generic') }}</p>
                <div v-for="command in iscsiConnectionCommands" :key="command" class="command-line command-line--copyable">
                  {{ command }}
                </div>
              </section>
              <section class="storage-panel storage-panel--status">
                <div class="storage-panel__title">{{ $t('label.storage.service.status.summary') }}</div>
                <dl class="storage-kv storage-kv--compact">
                  <dt>{{ $t('label.storage.service.endpoint') }}</dt>
                  <dd><ellipsis-text :value="iscsiEndpointSummary" code /></dd>
                  <dt>{{ $t('label.storage.service.monitor.cache') }}</dt>
                  <dd><a-tag :color="monitorCacheColor">{{ monitorCacheLabel }}</a-tag></dd>
                  <dt>{{ $t('label.storage.service.last.refresh') }}</dt>
                  <dd><ellipsis-text :value="monitorCacheTimestamp" /></dd>
                </dl>
              </section>
            </div>
            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.listener.groups') }}</h4>
                  <p>{{ $t('message.storage.service.listener.groups.table', { protocol: 'iSCSI' }) }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="protocolListenerColumns"
                :dataSource="iscsiListenerRows"
                :pagination="false"
                :scroll="{ x: 1260 }"
                :locale="storageTableLocale('message.storage.service.no.listeners')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger :disabled="!record.canDelete" :title="record.deleteDisabledReason || ''" @click="openDeleteConfirm('protocolListener', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>
            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.iscsi.targets') }}</h4>
                  <p>{{ $t('message.storage.service.iscsi.targets.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="iscsiTargetColumns"
                :dataSource="iscsiTargetRows"
                :pagination="false"
                :scroll="{ x: 1500 }"
                :locale="storageTableLocale('message.storage.service.no.iscsi.targets')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" @click="openActionModal('editIscsiTarget', record)">
                        <template #icon><EditOutlined /></template>
                        {{ $t('label.edit') }}
                      </a-button>
                      <a-button size="small" danger @click="openDeleteConfirm('iscsiTarget', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.access.rules') }}</h4>
                  <p>{{ $t('message.storage.service.iscsi.acls.table') }}</p>
                </div>
                <a-space class="storage-section-actions">
                  <a-button @click="openActionModal('iscsiAcl')">
                    <template #icon><SafetyCertificateOutlined /></template>
                    {{ $t('label.storage.service.create.iscsi.acl') }}
                  </a-button>
                </a-space>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="iscsiAclColumns"
                :dataSource="iscsiAclRows"
                :pagination="false"
                :scroll="{ x: 1380 }"
                :locale="storageTableLocale('message.storage.service.no.access.rules')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" @click="openActionModal('editIscsiAcl', record)">
                        <template #icon><EditOutlined /></template>
                        {{ $t('label.edit') }}
                      </a-button>
                      <a-button size="small" danger @click="openDeleteConfirm('iscsiAcl', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.backing.volumes') }}</h4>
                  <p>{{ $t('message.storage.service.iscsi.volumes.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="iscsiVolumeColumns"
                :dataSource="iscsiVolumeRows"
                :pagination="false"
                :scroll="{ x: 1480 }"
                :locale="storageTableLocale('message.storage.service.no.backing.volumes')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" :disabled="!record.resizeAllowed" :title="record.resizeDisabledReason || ''" @click="openActionModal('resizeBackingVolume', record)">
                        <template #icon><ExpandAltOutlined /></template>
                        {{ $t('label.storage.service.resize.volume') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.sessions') }}</h4>
                  <p>{{ $t('message.storage.service.iscsi.sessions.table') }}</p>
                </div>
              </div>
              <a-alert
                v-if="iscsiSessionRuntimeWarning"
                class="storage-service__alert"
                type="warning"
                show-icon
                :message="iscsiSessionRuntimeWarning" />
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="iscsiSessionColumns"
                :dataSource="iscsiSessionRows"
                :pagination="false"
                :scroll="{ x: 1810 }"
                :locale="storageTableLocale('message.storage.service.no.sessions')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'authVerification'">
                    <a-tag
                      :color="iscsiAuthVerificationColor(record.authVerification)"
                      :title="record.authVerificationTooltip || ''">
                      {{ record.authVerificationLabel }}
                    </a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger @click="openActionModal('disconnectSession', record)">
                        <template #icon><DisconnectOutlined /></template>
                        {{ $t('label.storage.service.disconnect.session') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>
          </template>
        </div>
      </a-tab-pane>

      <a-tab-pane v-if="hasStorageServiceApi" tab="NVMe-oF" key="nvmeof">
        <div class="storage-service storage-service--protocol" :class="{ 'storage-service--wide': protocolWideLayout }">
          <div class="storage-protocol-topbar">
            <protocol-header protocol="NVME_OF" />
            <a-space v-if="storageService.instance" class="protocol-toolbar protocol-toolbar--right" wrap>
              <a-button @click="toggleProtocolWideLayout">
                <template #icon><FullscreenExitOutlined v-if="protocolWideLayout" /><FullscreenOutlined v-else /></template>
                {{ protocolWideLayout ? $t('label.storage.service.default.view') : $t('label.storage.service.wide.view') }}
              </a-button>
              <a-button type="primary" @click="openActionModal('enableProtocol', { protocol: 'NVME_OF' })">
                <template #icon><PoweroffOutlined /></template>
                {{ $t('label.storage.service.enable.protocol') }}
              </a-button>
              <a-button @click="openActionModal('nvmePrepare')">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.storage.service.prepare.nvmeof') }}
              </a-button>
              <a-button @click="openActionModal('nvmeSubsystem')">
                <template #icon><PlusOutlined /></template>
                {{ $t('label.storage.service.create.nvme.subsystem') }}
              </a-button>
              <a-button @click="openActionModal('nvmeNamespace')">
                <template #icon><PlusOutlined /></template>
                {{ $t('label.storage.service.create.nvme.namespace') }}
              </a-button>
              <a-button @click="openActionModal('nvmeHostAcl')">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.create.nvme.host.acl') }}
              </a-button>
              <a-button :loading="storageService.refreshing" @click="fetchStorageServiceData">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.refresh') }}
              </a-button>
            </a-space>
          </div>
          <template v-if="storageService.instance">
            <a-alert
              v-if="storageIdentityDrift"
              class="storage-service__alert"
              type="warning"
              show-icon
              :message="$t('message.storage.service.nic.identity.drift')"
              :description="storageIdentityWarning" />
            <div v-if="canRepairStorageIdentity" class="storage-identity-repair-action">
              <a-button size="small" :loading="identityRepair.loading" @click="openStorageIdentityRepair">
                <template #icon><SafetyCertificateOutlined /></template>
                {{ $t('label.storage.service.nic.identity.repair') }}
              </a-button>
            </div>
            <a-alert
              v-if="!nvmeDhChapSupported"
              class="storage-service__alert"
              type="warning"
              show-icon
              :message="nvmeDhChapUnsupportedMessage" />
            <div class="storage-protocol-grid">
              <section class="storage-panel storage-panel--connection">
                <div class="storage-panel__title">{{ $t('label.storage.service.connection.info') }}</div>
                <p class="storage-panel__description">{{ $t('message.storage.service.nvme.connection.generic') }}</p>
                <div v-for="command in nvmeConnectionCommands" :key="command" class="command-line command-line--copyable">
                  {{ command }}
                </div>
              </section>
              <section class="storage-panel storage-panel--status">
                <div class="storage-panel__title">{{ $t('label.storage.service.status.summary') }}</div>
                <dl class="storage-kv storage-kv--compact">
                  <dt>{{ $t('label.storage.service.endpoint') }}</dt>
                  <dd><ellipsis-text :value="nvmeEndpointSummary" code /></dd>
                  <dt>{{ $t('label.storage.service.monitor.cache') }}</dt>
                  <dd><a-tag :color="monitorCacheColor">{{ monitorCacheLabel }}</a-tag></dd>
                  <dt>{{ $t('label.storage.service.last.refresh') }}</dt>
                  <dd><ellipsis-text :value="monitorCacheTimestamp" /></dd>
                  <dt>{{ $t('label.storage.service.dhchap.support') }}</dt>
                  <dd><a-tag :color="nvmeDhChapSupported ? 'green' : 'orange'">{{ nvmeDhChapSupported ? $t('label.supported') : $t('label.unsupported') }}</a-tag></dd>
                </dl>
              </section>
            </div>
            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.nvme.listener.groups') }}</h4>
                  <p>{{ $t('message.storage.service.nvme.listener.groups.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nvmeListenerColumns"
                :dataSource="nvmeListenerRows"
                :pagination="false"
                :scroll="{ x: 1360 }"
                :locale="storageTableLocale('message.storage.service.no.nvme.listeners')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button size="small" danger :disabled="!record.canDelete" :title="record.deleteDisabledReason || ''" @click="openDeleteConfirm('nvmeListener', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.nvme.subsystems') }}</h4>
                  <p>{{ $t('message.storage.service.nvme.subsystems.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nvmeSubsystemColumns"
                :dataSource="nvmeSubsystemRows"
                :pagination="false"
                :scroll="{ x: 1690 }"
                :locale="storageTableLocale('message.storage.service.no.nvme.subsystems')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'hostPolicyLabel'">
                    <a-tag :color="record.hostPolicyColor || 'default'">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" @click="openActionModal('editNvmeSubsystem', record)">
                          <template #icon><EditOutlined /></template>
                          {{ $t('label.edit') }}
                        </a-button>
                        <a-button size="small" danger :disabled="!record.canDelete" :title="record.deleteDisabledReason || ''" @click="openDeleteConfirm('nvmeSubsystem', record)">
                          <template #icon><DeleteOutlined /></template>
                          {{ $t('label.delete') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.nvme.namespaces') }}</h4>
                  <p>{{ $t('message.storage.service.nvme.namespaces.table') }}</p>
                </div>
                <a-space class="storage-section-actions">
                  <a-button @click="openActionModal('nvmeNamespace')">
                    <template #icon><PlusOutlined /></template>
                    {{ $t('label.storage.service.create.nvme.namespace') }}
                  </a-button>
                </a-space>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nvmeNamespaceColumns"
                :dataSource="nvmeNamespaceRows"
                :pagination="false"
                :scroll="{ x: 1690 }"
                :locale="storageTableLocale('message.storage.service.no.nvme.namespaces')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'hostPolicyLabel'">
                    <a-tag :color="record.hostPolicyColor || 'default'">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" @click="openActionModal('editNvmeNamespace', record)">
                          <template #icon><EditOutlined /></template>
                          {{ $t('label.edit') }}
                        </a-button>
                        <a-button size="small" danger @click="openDeleteConfirm('nvmeNamespace', record)">
                          <template #icon><DeleteOutlined /></template>
                          {{ $t('label.delete') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.backing.volumes') }}</h4>
                  <p>{{ $t('message.storage.service.nvme.volumes.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nvmeVolumeColumns"
                :dataSource="nvmeVolumeRows"
                :pagination="false"
                :scroll="{ x: 1730 }"
                :locale="storageTableLocale('message.storage.service.no.backing.volumes')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-space class="storage-table-actions__space">
                        <a-button size="small" :disabled="!record.resizeAllowed" :title="record.resizeDisabledReason || ''" @click="openActionModal('resizeBackingVolume', record)">
                          <template #icon><ExpandAltOutlined /></template>
                          {{ $t('label.storage.service.resize.volume') }}
                        </a-button>
                        <a-button size="small" danger :disabled="!record.detachAllowed" :title="record.detachDisabledReason || ''" @click="openActionModal('detachBackingVolume', record)">
                          <template #icon><DisconnectOutlined /></template>
                          {{ $t('label.storage.service.detach.backing.volume') }}
                        </a-button>
                      </a-space>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.access.rules') }}</h4>
                  <p>{{ $t('message.storage.service.nvme.acls.table') }}</p>
                </div>
                <a-space class="storage-section-actions">
                  <a-button @click="openActionModal('nvmeHostAcl')">
                    <template #icon><SafetyCertificateOutlined /></template>
                    {{ $t('label.storage.service.create.nvme.host.acl') }}
                  </a-button>
                </a-space>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nvmeAclColumns"
                :dataSource="nvmeAclRows"
                :pagination="false"
                :scroll="{ x: 1300 }"
                :locale="storageTableLocale('message.storage.service.no.access.rules')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="record.policyRow ? 'green' : runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'policySource'">
                    <a-tag :color="record.policyRow ? 'green' : 'blue'">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <span v-if="record.policyRow" class="storage-muted-text">{{ $t('label.storage.service.inherited.policy') }}</span>
                    <a-space v-else class="storage-row-actions">
                      <a-button size="small" @click="openActionModal('editNvmeHostAcl', record)" :disabled="record.subsystemAllowAnyHost">
                        <template #icon><EditOutlined /></template>
                        {{ $t('label.edit') }}
                      </a-button>
                      <a-button size="small" danger @click="openDeleteConfirm('nvmeHostAcl', record)">
                        <template #icon><DeleteOutlined /></template>
                        {{ $t('label.delete') }}
                      </a-button>
                    </a-space>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>

            <section class="storage-table-section">
              <div class="storage-table-section__header">
                <div>
                  <h4>{{ $t('label.storage.service.sessions') }}</h4>
                  <p>{{ $t('message.storage.service.nvme.sessions.table') }}</p>
                </div>
              </div>
              <a-table
                class="storage-data-table"
                size="small"
                rowKey="key"
                :columns="nvmeSessionColumns"
                :dataSource="nvmeSessionRows"
                :pagination="false"
                :scroll="{ x: 2700 }"
                :locale="storageTableLocale('message.storage.service.no.sessions')">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'state'">
                    <a-tag :color="runtimeColor(record)">{{ storageCellValue(record, column) }}</a-tag>
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <div class="storage-table-actions">
                      <a-button
                        size="small"
                        danger
                        :disabled="!record.canDisconnect"
                        :title="record.canDisconnect ? $t('label.storage.service.disconnect.session') : record.disconnectDisabledReason"
                        @click="openActionModal('disconnectSession', record)">
                        <template #icon><DisconnectOutlined /></template>
                        {{ $t('label.storage.service.disconnect.session') }}
                      </a-button>
                    </div>
                  </template>
                  <template v-else>
                    <ellipsis-text :value="storageCellValue(record, column)" :code="column.code" />
                  </template>
                </template>
              </a-table>
            </section>
          </template>
        </div>
      </a-tab-pane>

      <a-tab-pane :tab="$t('label.networks')" key="nics" v-if="'listNics' in $store.getters.apis"><NicsTab :resource="vm"/></a-tab-pane>
      <a-tab-pane v-if="$store.getters.features.instancesdisksstatsretentionenabled" :tab="$t('label.volume.metrics')" key="volumestats"><StatsTab :resource="volume" :resourceType="'Volume'"/></a-tab-pane>
      <a-tab-pane :tab="$t('label.metrics')" key="vmstats"><StatsTab :resource="vm"/></a-tab-pane>
      <a-tab-pane :tab="$t('label.events')" key="events" v-if="'listEvents' in $store.getters.apis"><events-tab :resource="resource" resourceType="SharedFS" :loading="loading" /></a-tab-pane>
    </a-tabs>

    <a-modal
:visible="actionModal.visible"
:title="actionModalTitle"
:confirmLoading="actionModal.loading"
:maskClosable="false"
:width="860"
:centered="true"
:okText="actionModalOkText"
:cancelText="$t('label.cancel')"
:okButtonProps="actionModalOkButtonProps"
wrapClassName="storage-service-action-modal"
@ok="submitActionModal"
@cancel="closeActionModal">
      <div class="storage-modal-body"><a-form layout="vertical">
        <div v-if="actionModal.type === 'enableProtocol'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.protocol')" :tooltip="$t('message.storage.service.protocol.help')" />
            </template>
            <a-select v-model:value="forms.enableProtocol.protocol">
              <a-select-option value="NFS">NFS</a-select-option>
              <a-select-option value="SMB">SMB</a-select-option>
              <a-select-option value="ISCSI">iSCSI</a-select-option>
              <a-select-option value="NVME_OF">NVMe-oF</a-select-option>
            </a-select>
          </a-form-item>
          <a-alert
            v-if="isEnableProtocolNfsDualMode"
            type="info"
            show-icon
            class="storage-service-inline-alert"
            :message="$t('message.storage.service.nfs.dual.mode.endpoint.locked')" />
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.listen.ip.mode')" :tooltip="$t('message.storage.service.listen.ip.mode.help')" />
            </template>
            <a-radio-group v-model:value="forms.enableProtocol.listenipmode">
              <a-radio value="EXISTING">{{ $t('label.storage.service.listen.ip.existing') }}</a-radio>
              <a-radio value="NEW">{{ $t('label.storage.service.listen.ip.new') }}</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="forms.enableProtocol.listenipmode === 'EXISTING'" required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.listen.ip')" :tooltip="$t('message.storage.service.listen.ip.help')" />
            </template>
            <a-select v-model:value="forms.enableProtocol.listenip" show-search optionFilterProp="label">
              <a-select-option v-for="nic in serviceListenIps" :key="nic.key" :value="nic.ipaddress" :label="nic.label">
                {{ nic.label }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-else required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.new.listen.ip')" :tooltip="$t('message.storage.service.listen.ip.same.cidr')" />
            </template>
            <a-input v-model:value="forms.enableProtocol.listenip" placeholder="10.10.254.20" />
          </a-form-item>
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.port')" :tooltip="forms.enableProtocol.protocol === 'NFS' ? $t('message.storage.service.nfs.port.help') : $t('message.storage.service.protocol.port.help')" />
            </template>
            <a-input-number
              v-model:value="forms.enableProtocol.port"
              class="storage-input-number storage-fixed-value"
              :min="1"
              :max="65535"
              :disabled="isEnableProtocolNfsDualMode" />
          </a-form-item>
          <a-alert
            v-if="enableProtocolListenerConflictMessage"
            type="warning"
            show-icon
            class="storage-service-inline-alert"
            :message="enableProtocolListenerConflictMessage" />
          <a-alert
            v-if="enableProtocolListenerCoveredMessage"
            type="info"
            show-icon
            class="storage-service-inline-alert"
            :message="enableProtocolListenerCoveredMessage" />
          <a-form-item v-if="forms.enableProtocol.protocol === 'NFS'" required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.nfs.protocol.mode')" :tooltip="$t('message.storage.service.nfs.protocol.mode.help')" />
            </template>
            <a-input :value="nfsProtocolModeLabel(forms.enableProtocol.protocolmode)" readonly class="storage-fixed-value" />
          </a-form-item>
        </div>
        <div v-if="actionModal.type === 'nfsExport' || actionModal.type === 'editNfsExport'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.name')" :tooltip="$t('message.storage.service.nfs.export.name.help')" />
            </template>
            <a-input v-model:value="forms.nfsExport.name" />
          </a-form-item>
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.internal.path')" :tooltip="$t('message.storage.service.nfs.backing.path.help')" />
            </template>
            <a-input v-model:value="forms.nfsExport.path" />
          </a-form-item>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.backing.volume') }}</div>
            <a-form-item required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.volume.mode')" :tooltip="$t('message.storage.service.volume.mode.help')" />
              </template>
            <a-radio-group v-model:value="forms.nfsExport.volumemode">
                <a-radio value="CURRENT">{{ $t('label.storage.service.current.volume') }}</a-radio>
                <a-radio value="EXISTING">{{ $t('label.storage.service.existing.volume.select') }}</a-radio>
                <a-radio value="NEW">{{ $t('label.storage.service.new.volume') }}</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item
              v-if="forms.nfsExport.volumemode === 'CURRENT'"
              required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.current.backing.volume')" :tooltip="$t('message.storage.service.current.volume.select.help')" />
              </template>
              <a-select
                v-model:value="forms.nfsExport.volumeid"
                :loading="volumeLoading"
                show-search
                optionFilterProp="label">
                <a-select-option v-for="volume in currentBackingVolumes" :key="volume.id" :value="volume.id" :label="formatCurrentBackingVolumeOption(volume)">
                  {{ formatCurrentBackingVolumeOption(volume) }}
                </a-select-option>
              </a-select>
              <div v-if="selectedCurrentBackingVolume" class="storage-action-summary-box storage-action-summary-box--compact">
                <div class="storage-action-summary-row">
                  <span>{{ $t('label.storage.service.mount.path') }}</span>
                  <code>{{ currentBackingVolumeMountPath(selectedCurrentBackingVolume) || '-' }}</code>
                </div>
                <div class="storage-action-summary-row">
                  <span>{{ $t('label.storage.service.attached.export') }}</span>
                  <code>{{ currentBackingVolumeExportSummary(selectedCurrentBackingVolume) }}</code>
                </div>
              </div>
            </a-form-item>
            <volume-select
              v-if="forms.nfsExport.volumemode === 'EXISTING'"
              v-model:value="forms.nfsExport.volumeid"
              :volumes="availableVolumes"
              :loading="volumeLoading"
              :formatter="formatVolumeOption"
              required
              :tooltip="$t('message.storage.service.existing.volume.select.help')" />
            <a-form-item>
              <a-checkbox v-model:checked="forms.nfsExport.createdirectory">
                {{ $t('label.storage.service.create.directory.if.missing') }}
              </a-checkbox>
            </a-form-item>
            <template v-if="forms.nfsExport.volumemode === 'NEW'">
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.new.volume.name')" :tooltip="$t('message.storage.service.new.volume.name.help')" />
                </template>
                <a-input v-model:value="forms.nfsExport.newvolumename" />
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.diskoffering')" :tooltip="$t('message.storage.service.new.volume.diskoffering.help')" />
                </template>
                <a-select v-model:value="forms.nfsExport.diskofferingid" :loading="diskOfferingLoading" show-search optionFilterProp="label" @change="reconcileNfsNewVolumeStorage">
                  <a-select-option v-for="offering in diskOfferings" :key="offering.id" :value="offering.id" :label="offering.displaytext || offering.name">
                    {{ offering.displaytext || offering.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.primary.storage')" :tooltip="$t('message.storage.service.new.volume.storage.help')" />
                </template>
                <a-select v-model:value="forms.nfsExport.storageid" :loading="storagePoolLoading" show-search optionFilterProp="label">
                  <a-select-option v-for="pool in filteredNfsNewVolumeStoragePools" :key="pool.id" :value="pool.id" :label="storagePoolLabel(pool)">
                    {{ storagePoolLabel(pool) }}
                  </a-select-option>
                </a-select>
                <div v-if="selectedNfsDiskOfferingTags.length" class="storage-field-hint">
                  {{ $t('message.storage.service.primary.storage.tag.filtered', { tags: selectedNfsDiskOfferingTags.join(', ') }) }}
                </div>
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.volume.size.gib')" :tooltip="$t('message.storage.service.volume.size.help')" />
                </template>
                <a-input-number v-model:value="forms.nfsExport.newvolumesize" class="storage-input-number" :min="1" />
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.filesystem')" :tooltip="$t('message.storage.service.new.volume.filesystem.help')" />
                </template>
                <a-select v-model:value="forms.nfsExport.filesystem">
                  <a-select-option value="xfs">XFS</a-select-option>
                  <a-select-option value="ext4">EXT4</a-select-option>
                </a-select>
              </a-form-item>
            </template>
          </section>
          <capacity-input :label="$t('label.storage.service.nfs.export.capacity.limit')" :tooltip="$t('message.storage.service.nfs.quota.help')" v-model:amount="forms.nfsExport.quotaamount" v-model:unit="forms.nfsExport.quotaunit" :units="capacityUnits" />
          <section v-if="isNfsRuntimeDualMode" class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nfs.export.endpoints') }}</div>
            <a-alert
              type="info"
              show-icon
              class="storage-service-inline-alert"
              :message="$t('message.storage.service.nfs.dual.mode.export.exposure')" />
            <div class="storage-action-summary-box storage-action-summary-box--compact">
              <div class="storage-action-summary-row">
                <span>{{ $t('label.storage.service.nfs.exposure.scope') }}</span>
                <code>{{ $t('label.storage.service.service.wide.endpoints') }}</code>
              </div>
              <div class="storage-action-summary-row">
                <span>{{ $t('label.storage.service.listen.ip') }}</span>
                <code>{{ nfsServiceWideEndpointSummary }}</code>
              </div>
            </div>
          </section>
          <section v-else class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nfs.listener.groups') }}</div>
            <a-alert
              type="info"
              show-icon
              class="storage-service-inline-alert"
              :message="$t('message.storage.service.nfs.listener.group.exposure')" />
            <a-form-item required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.nfs.listener.group')" :tooltip="$t('message.storage.service.nfs.listener.group.help')" />
              </template>
              <a-select v-model:value="forms.nfsExport.listenerports" mode="multiple" show-search optionFilterProp="label">
                <a-select-option v-for="group in nfsListenerGroupOptions" :key="group.value" :value="group.value" :label="group.label">
                  {{ group.label }}
                </a-select-option>
              </a-select>
            </a-form-item>
            <div class="storage-action-summary-box storage-action-summary-box--compact">
              <div class="storage-action-summary-row">
                <span>{{ $t('label.storage.service.accessible.endpoints') }}</span>
                <code>{{ formatNfsListenerGroupEndpoints(forms.nfsExport.listenerports) }}</code>
              </div>
            </div>
          </section>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nfs.export.options') }}</div>
            <div class="storage-action-checkbox-grid">
              <a-checkbox v-model:checked="forms.nfsExport.readonly">{{ $t('label.storage.service.permission.readonly') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsExport.rootsquash">{{ $t('label.storage.service.root.squash') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsExport.allsquash">{{ $t('label.storage.service.all.squash') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsExport.sync">{{ $t('label.storage.service.sync') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsExport.secure">
                <a-tooltip :title="$t('message.storage.service.secure.help')">
                  <span>{{ $t('label.storage.service.secure') }}</span>
                </a-tooltip>
              </a-checkbox>
            </div>
            <a-alert
              v-if="forms.nfsExport.rootsquash"
              class="storage-action-context-alert"
              type="info"
              show-icon
              :message="$t('message.storage.service.nfs.posix.permission.help')" />
          </section>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.posix.permission') }}</div>
            <a-row :gutter="12">
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.owner.uid')" :tooltip="$t('message.storage.service.owner.uid.help')" /></template><a-input-number v-model:value="forms.nfsExport.owneruid" class="storage-input-number" :min="0" :max="65535" /></a-form-item></a-col>
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.owner.gid')" :tooltip="$t('message.storage.service.owner.gid.help')" /></template><a-input-number v-model:value="forms.nfsExport.ownergid" class="storage-input-number" :min="0" :max="65535" /></a-form-item></a-col>
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.anon.uid')" :tooltip="$t('message.storage.service.anon.uid.help')" /></template><a-input-number v-model:value="forms.nfsExport.anonuid" class="storage-input-number" :min="0" :max="65535" /></a-form-item></a-col>
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.anon.gid')" :tooltip="$t('message.storage.service.anon.gid.help')" /></template><a-input-number v-model:value="forms.nfsExport.anongid" class="storage-input-number" :min="0" :max="65535" /></a-form-item></a-col>
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.directory.mode')" :tooltip="$t('message.storage.service.directory.mode.help')" /></template><a-input v-model:value="forms.nfsExport.mode" placeholder="0775" /></a-form-item></a-col>
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.recursive.permission')" :tooltip="$t('message.storage.service.recursive.permission.help')" /></template><a-switch v-model:checked="forms.nfsExport.recursivepermission" /></a-form-item></a-col>
            </a-row>
          </section>
        </div>
        <div v-if="actionModal.type === 'nfsAcl' || actionModal.type === 'editNfsAcl'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.export.name')" :tooltip="$t('message.storage.service.export.name.help')" />
            </template>
            <a-select v-model:value="forms.nfsAcl.exportid" show-search optionFilterProp="label" :disabled="actionModal.type === 'editNfsAcl'">
              <a-select-option v-for="share in storageService.nfsExports" :key="share.id" :value="share.id" :label="shareNameLabel(share)">
                {{ shareNameLabel(share) }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <div v-if="selectedNfsAclExport" class="storage-action-summary-box">
            <div class="storage-action-summary-row">
              <span>{{ $t('label.storage.service.internal.path') }}</span>
              <code>{{ selectedNfsAclExport.path || '-' }}</code>
            </div>
            <div class="storage-action-summary-row">
              <span>{{ $t('label.storage.service.client.mount.root') }}</span>
              <code>/{{ shareNameLabel(selectedNfsAclExport) }}</code>
            </div>
          </div>
          <a-alert
            v-if="isNfsRuntimeDualMode"
            type="info"
            show-icon
            class="storage-service-inline-alert"
            :message="$t('message.storage.service.nfs.dual.mode.acl.scope')" />
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.principal')" :tooltip="$t('message.storage.service.allowed.cidrs.help')" />
            </template>
            <a-select
              v-if="actionModal.type !== 'editNfsAcl'"
              v-model:value="forms.nfsAcl.principals"
              mode="tags"
              :tokenSeparators="[',']"
              :placeholder="$t('message.storage.service.allowed.cidrs.placeholder')" />
            <a-input
              v-else
              v-model:value="forms.nfsAcl.principal"
              :placeholder="$t('message.storage.service.allowed.cidrs.single.placeholder')" />
          </a-form-item>
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" />
            </template>
            <a-select v-model:value="forms.nfsAcl.permission">
              <a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option>
              <a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option>
            </a-select>
          </a-form-item>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nfs.access.options') }}</div>
            <div class="storage-action-checkbox-grid">
              <a-checkbox v-model:checked="forms.nfsAcl.rootsquash">{{ $t('label.storage.service.root.squash') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsAcl.allsquash">{{ $t('label.storage.service.all.squash') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsAcl.sync">{{ $t('label.storage.service.sync') }}</a-checkbox>
              <a-checkbox v-model:checked="forms.nfsAcl.secure">
                <a-tooltip :title="$t('message.storage.service.secure.help')">
                  <span>{{ $t('label.storage.service.secure') }}</span>
                </a-tooltip>
              </a-checkbox>
            </div>
          </section>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.squash.user.mapping') }}</div>
            <a-row :gutter="12">
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.anon.uid')" :tooltip="$t('message.storage.service.anon.uid.help')" /></template><a-input-number v-model:value="forms.nfsAcl.anonuid" class="storage-input-number" :min="0" :max="65535" /></a-form-item></a-col>
              <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.anon.gid')" :tooltip="$t('message.storage.service.anon.gid.help')" /></template><a-input-number v-model:value="forms.nfsAcl.anongid" class="storage-input-number" :min="0" :max="65535" /></a-form-item></a-col>
            </a-row>
          </section>
        </div>
        <div v-if="actionModal.type === 'deleteConfirm'" class="storage-action-form storage-action-form--vertical">
          <a-alert
            class="storage-action-delete-alert"
            type="warning"
            show-icon
            :message="$t('message.storage.service.delete.warning')" />
          <div class="storage-action-summary-box">
            <div class="storage-action-summary-row">
              <span>{{ $t('label.type') }}</span>
              <code>{{ deleteTargetTypeLabel }}</code>
            </div>
            <div class="storage-action-summary-row">
              <span>{{ $t('label.name') }}</span>
              <code>{{ actionModal.context?.name || '-' }}</code>
            </div>
          </div>
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.confirmation')" :tooltip="$t('message.storage.service.delete.confirm.tooltip')" />
            </template>
            <a-input v-model:value="forms.deleteConfirm.confirmation" :placeholder="actionModal.context?.name || ''" />
            <div class="field-validation-hint">
              {{ $t('message.storage.service.delete.confirm.input') }}
            </div>
          </a-form-item>
        </div>
        <div v-if="actionModal.type === 'deleteEndpoint'" class="storage-action-form storage-action-form--vertical">
          <a-alert
            class="storage-action-context-alert storage-action-context-alert--danger"
            type="warning"
            show-icon
            :message="$t('message.storage.service.delete.endpoint.warning')" />
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.endpoint')" :tooltip="$t('message.storage.service.delete.endpoint.help')" />
            </template>
            <a-select v-model:value="forms.deleteEndpoint.listenip" show-search optionFilterProp="label">
              <a-select-option v-for="endpoint in removableServiceEndpoints" :key="endpoint.ipaddress" :value="endpoint.ipaddress" :label="endpoint.label">
                {{ endpoint.label }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.storage.service.delete.confirm')" :tooltip="$t('message.storage.service.delete.endpoint.confirm.tooltip')" />
            </template>
            <a-input v-model:value="forms.deleteEndpoint.confirmation" :placeholder="forms.deleteEndpoint.listenip || ''" />
          </a-form-item>
        </div>
        <div v-if="actionModal.type === 'smbShare' || actionModal.type === 'editSmbShare'" class="storage-action-form storage-action-form--vertical">
          <a-form-item>
            <template #label><tooltip-label :title="$t('label.name')" :tooltip="$t('message.storage.service.smb.name.autogenerated')" /></template>
            <a-input v-model:value="forms.smbShare.name" />
          </a-form-item>
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.internal.path')" :tooltip="$t('message.storage.service.smb.internal.path.help')" /></template>
            <a-input v-model:value="forms.smbShare.path" placeholder="/export/smb01" />
          </a-form-item>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.backing.volume') }}</div>
            <a-form-item required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.volume.mode')" :tooltip="$t('message.storage.service.volume.mode.help')" />
              </template>
              <a-radio-group v-model:value="forms.smbShare.volumemode">
                <a-radio value="CURRENT">{{ $t('label.storage.service.current.volume') }}</a-radio>
                <a-radio value="EXISTING">{{ $t('label.storage.service.existing.volume.select') }}</a-radio>
                <a-radio v-if="actionModal.type === 'smbShare'" value="NEW">{{ $t('label.storage.service.new.volume') }}</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item
              v-if="forms.smbShare.volumemode === 'CURRENT'"
              required>
              <template #label>
                <tooltip-label :title="$t('label.storage.service.current.backing.volume')" :tooltip="$t('message.storage.service.current.volume.select.help')" />
              </template>
              <a-select
                v-model:value="forms.smbShare.volumeid"
                :loading="volumeLoading"
                show-search
                optionFilterProp="label">
                <a-select-option v-for="volume in currentBackingVolumes" :key="volume.id" :value="volume.id" :label="formatCurrentBackingVolumeOption(volume)">
                  {{ formatCurrentBackingVolumeOption(volume) }}
                </a-select-option>
              </a-select>
              <div v-if="selectedSmbCurrentBackingVolume" class="storage-action-summary-box storage-action-summary-box--compact">
                <div class="storage-action-summary-row">
                  <span>{{ $t('label.storage.service.mount.path') }}</span>
                  <code>{{ currentBackingVolumeMountPath(selectedSmbCurrentBackingVolume) || '-' }}</code>
                </div>
                <div class="storage-action-summary-row">
                  <span>{{ $t('label.storage.service.attached.export') }}</span>
                  <code>{{ currentBackingVolumeExportSummary(selectedSmbCurrentBackingVolume) }}</code>
                </div>
              </div>
            </a-form-item>
            <volume-select
              v-if="forms.smbShare.volumemode === 'EXISTING'"
              v-model:value="forms.smbShare.volumeid"
              :volumes="availableVolumes"
              :loading="volumeLoading"
              :formatter="formatVolumeOption"
              required
              :tooltip="$t('message.storage.service.existing.volume.select.help')" />
            <a-form-item>
              <a-checkbox v-model:checked="forms.smbShare.createdirectory">
                {{ $t('label.storage.service.create.directory.if.missing') }}
              </a-checkbox>
            </a-form-item>
            <template v-if="forms.smbShare.volumemode === 'NEW'">
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.new.volume.name')" :tooltip="$t('message.storage.service.new.volume.name.help')" />
                </template>
                <a-input v-model:value="forms.smbShare.newvolumename" />
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.diskoffering')" :tooltip="$t('message.storage.service.new.volume.diskoffering.help')" />
                </template>
                <a-select v-model:value="forms.smbShare.diskofferingid" :loading="diskOfferingLoading" show-search optionFilterProp="label" @change="reconcileSmbNewVolumeStorage">
                  <a-select-option v-for="offering in diskOfferings" :key="offering.id" :value="offering.id" :label="offering.displaytext || offering.name">
                    {{ offering.displaytext || offering.name }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.primary.storage')" :tooltip="$t('message.storage.service.new.volume.storage.help')" />
                </template>
                <a-select v-model:value="forms.smbShare.storageid" :loading="storagePoolLoading" show-search optionFilterProp="label">
                  <a-select-option v-for="pool in filteredSmbNewVolumeStoragePools" :key="pool.id" :value="pool.id" :label="storagePoolLabel(pool)">
                    {{ storagePoolLabel(pool) }}
                  </a-select-option>
                </a-select>
                <div v-if="selectedSmbDiskOfferingTags.length" class="storage-field-hint">
                  {{ $t('message.storage.service.primary.storage.tag.filtered', { tags: selectedSmbDiskOfferingTags.join(', ') }) }}
                </div>
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.volume.size.gib')" :tooltip="$t('message.storage.service.volume.size.help')" />
                </template>
                <a-input-number v-model:value="forms.smbShare.newvolumesize" class="storage-input-number" :min="1" />
              </a-form-item>
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.filesystem')" :tooltip="$t('message.storage.service.new.volume.filesystem.help')" />
                </template>
                <a-select v-model:value="forms.smbShare.filesystem">
                  <a-select-option value="xfs">XFS</a-select-option>
                  <a-select-option value="ext4">EXT4</a-select-option>
                </a-select>
              </a-form-item>
            </template>
          </section>
          <capacity-input :label="$t('label.storage.service.smb.share.capacity.limit')" :tooltip="$t('message.storage.service.smb.quota.help')" v-model:amount="forms.smbShare.quotaamount" v-model:unit="forms.smbShare.quotaunit" :units="capacityUnits" />
          <a-form-item>
            <template #label><tooltip-label :title="$t('label.storage.service.cross.protocol.share')" :tooltip="$t('message.storage.service.cross.protocol.share.help')" /></template>
            <a-switch v-model:checked="forms.smbShare.crossprotocol" />
          </a-form-item>
          <a-form-item>
            <template #label><tooltip-label :title="$t('label.storage.service.directory.mode')" :tooltip="$t('message.storage.service.directory.mode.help')" /></template>
            <a-input v-model:value="forms.smbShare.directorymode" placeholder="0770" />
          </a-form-item>
          <a-space wrap>
            <a-checkbox v-model:checked="forms.smbShare.readonly">{{ $t('label.storage.service.permission.readonly') }}</a-checkbox>
            <a-checkbox v-model:checked="forms.smbShare.browseable">{{ $t('label.storage.service.browseable') }}</a-checkbox>
            <a-checkbox v-model:checked="forms.smbShare.guestok">{{ $t('label.storage.service.guest.access') }}</a-checkbox>
          </a-space>
        </div>
        <div v-if="actionModal.type === 'smbAcl' || actionModal.type === 'editSmbAcl'" class="storage-action-form storage-action-form--vertical">
          <a-row :gutter="16">
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.share')" :tooltip="$t('message.storage.service.share.help')" /></template><a-select v-model:value="forms.smbAcl.shareid" show-search optionFilterProp="label"><a-select-option v-for="share in storageService.smbShares" :key="share.id" :value="share.id" :label="shareLabel(share)">{{ shareLabel(share) }}</a-select-option></a-select></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.principal')" :tooltip="$t('message.storage.service.smb.principal.help')" /></template><a-input v-model:value="forms.smbAcl.principal" /></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.principal.type')" :tooltip="$t('message.storage.service.principal.type.help')" /></template><a-select v-model:value="forms.smbAcl.principaltype"><a-select-option value="LOCAL_USER">{{ $t('label.storage.service.local.user') }}</a-select-option><a-select-option value="LOCAL_GROUP">{{ $t('label.storage.service.local.group') }}</a-select-option><a-select-option value="AD_USER">{{ $t('label.storage.service.ad.user') }}</a-select-option><a-select-option value="AD_GROUP">{{ $t('label.storage.service.ad.group') }}</a-select-option></a-select></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" /></template><a-select v-model:value="forms.smbAcl.permission"><a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option><a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option><a-select-option value="ADMIN">{{ $t('label.admin') }}</a-select-option></a-select></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.local.user.password')" :tooltip="$t('message.storage.service.smb.local.password.help')" /></template><a-input-password v-model:value="forms.smbAcl.password" autocomplete="new-password" /></a-form-item></a-col>
          </a-row>
        </div>
        <div v-if="actionModal.type === 'adJoin' || actionModal.type === 'adRejoin'" class="storage-action-form storage-action-form--vertical">
          <a-alert
            class="storage-service__alert"
            type="info"
            show-icon
            :message="actionModal.type === 'adRejoin' ? $t('message.storage.service.ad.rejoin.help') : $t('message.storage.service.smb.password.sensitive')" />
          <a-row :gutter="16">
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.ad.domain')" :tooltip="$t('message.storage.service.ad.domain.help')" /></template><a-input v-model:value="forms.adJoin.domainname" /></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.username')" :tooltip="$t('message.storage.service.ad.username.help')" /></template><a-input v-model:value="forms.adJoin.username" /></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item required><template #label><tooltip-label :title="$t('label.password')" :tooltip="$t('message.storage.service.ad.password.help')" /></template><a-input-password v-model:value="forms.adJoin.password" autocomplete="new-password" /></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.dns.servers')" :tooltip="$t('message.storage.service.dns.servers.help')" /></template><a-input v-model:value="forms.adJoin.dnsservers" /></a-form-item></a-col>
          </a-row>
        </div>
        <div v-if="actionModal.type === 'adLeave'" class="storage-action-form storage-action-form--vertical">
          <a-alert
            class="storage-service__alert"
            type="warning"
            show-icon
            :message="$t('message.storage.service.ad.leave.warning')" />
          <a-row :gutter="16">
            <a-col :xs="24" :md="24">
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.storage.service.ad.domain')" :tooltip="$t('message.storage.service.ad.leave.confirm.help')" />
                </template>
                <a-input :value="currentSmbDomainConfirmation" disabled />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24">
              <a-form-item required>
                <template #label>
                  <tooltip-label :title="$t('label.confirmation')" :tooltip="$t('message.storage.service.ad.leave.confirm.help')" />
                </template>
                <a-input v-model:value="forms.adLeave.confirmation" :placeholder="currentSmbDomainConfirmation" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :md="24"><a-form-item><template #label><tooltip-label :title="$t('label.username')" :tooltip="$t('message.storage.service.ad.leave.username.help')" /></template><a-input v-model:value="forms.adLeave.username" /></a-form-item></a-col>
            <a-col :xs="24" :md="24"><a-form-item><template #label><tooltip-label :title="$t('label.password')" :tooltip="$t('message.storage.service.ad.leave.password.help')" /></template><a-input-password v-model:value="forms.adLeave.password" autocomplete="new-password" /></a-form-item></a-col>
          </a-row>
        </div>
        <div v-if="actionModal.type === 'iscsiTarget' || actionModal.type === 'editIscsiTarget'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.target.iqn')" :tooltip="$t('message.storage.service.target.iqn.help')" /></template>
            <a-input v-model:value="forms.iscsiTarget.targetname" />
          </a-form-item>
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.lun')" :tooltip="$t('message.storage.service.lun.help')" /></template>
            <a-input v-model:value="forms.iscsiTarget.lun" />
          </a-form-item>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.backing.volume') }}</div>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.volume.mode')" :tooltip="$t('message.storage.service.volume.mode.help')" /></template>
              <a-radio-group v-model:value="forms.iscsiTarget.volumemode" :disabled="actionModal.type === 'editIscsiTarget'">
                <a-radio value="CURRENT">{{ $t('label.storage.service.current.volume') }}</a-radio>
                <a-radio value="EXISTING">{{ $t('label.storage.service.existing.volume.select') }}</a-radio>
                <a-radio value="NEW">{{ $t('label.storage.service.new.volume') }}</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item v-if="forms.iscsiTarget.volumemode === 'CURRENT'" required>
              <template #label><tooltip-label :title="$t('label.storage.service.iscsi.current.block.volume')" :tooltip="$t('message.storage.service.iscsi.current.block.volume.help')" /></template>
              <a-select v-model:value="forms.iscsiTarget.volumeid" :loading="volumeLoading" show-search optionFilterProp="label" :disabled="actionModal.type === 'editIscsiTarget'">
                <a-select-option v-for="volume in currentIscsiBlockVolumes" :key="volume.id" :value="volume.id" :label="formatCurrentBackingVolumeOption(volume)">
                  {{ formatCurrentBackingVolumeOption(volume) }}
                </a-select-option>
              </a-select>
            </a-form-item>
            <volume-select
              v-if="forms.iscsiTarget.volumemode === 'EXISTING'"
              v-model:value="forms.iscsiTarget.volumeid"
              :volumes="availableVolumes"
              :loading="volumeLoading"
              :formatter="formatVolumeOption"
              required
              :tooltip="$t('message.storage.service.existing.volume.select.help')" />
            <template v-if="forms.iscsiTarget.volumemode === 'NEW'">
              <a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.new.volume.name')" :tooltip="$t('message.storage.service.new.volume.name.help')" /></template><a-input v-model:value="forms.iscsiTarget.newvolumename" /></a-form-item>
              <a-form-item required><template #label><tooltip-label :title="$t('label.diskoffering')" :tooltip="$t('message.storage.service.new.volume.diskoffering.help')" /></template><a-select v-model:value="forms.iscsiTarget.diskofferingid" :loading="diskOfferingLoading" show-search optionFilterProp="label" @change="reconcileIscsiNewVolumeStorage"><a-select-option v-for="offering in diskOfferings" :key="offering.id" :value="offering.id" :label="offering.displaytext || offering.name">{{ offering.displaytext || offering.name }}</a-select-option></a-select></a-form-item>
              <a-form-item required><template #label><tooltip-label :title="$t('label.primary.storage')" :tooltip="$t('message.storage.service.new.volume.storage.help')" /></template><a-select v-model:value="forms.iscsiTarget.storageid" :loading="storagePoolLoading" show-search optionFilterProp="label"><a-select-option v-for="pool in filteredIscsiNewVolumeStoragePools" :key="pool.id" :value="pool.id" :label="storagePoolLabel(pool)">{{ storagePoolLabel(pool) }}</a-select-option></a-select></a-form-item>
              <a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.volume.size.gib')" :tooltip="$t('message.storage.service.volume.size.help')" /></template><a-input-number v-model:value="forms.iscsiTarget.newvolumesize" class="storage-input-number" :min="1" /></a-form-item>
            </template>
          </section>
          <a-alert class="storage-action-alert" type="info" show-icon :message="$t('message.storage.service.iscsi.block.only.help')" />
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.iscsi.listener.groups') }}</div>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.iscsi.listener.group')" :tooltip="$t('message.storage.service.iscsi.listener.group.help')" /></template>
              <a-select v-model:value="forms.iscsiTarget.listenerports" mode="multiple" show-search optionFilterProp="label">
                <a-select-option v-for="group in iscsiListenerGroupOptions" :key="group.value" :value="group.value" :label="group.label">{{ group.label }}</a-select-option>
              </a-select>
            </a-form-item>
            <div class="storage-action-summary-box storage-action-summary-box--compact">
              <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.accessible.endpoints') }}</span><code>{{ formatIscsiListenerGroupEndpoints(forms.iscsiTarget.listenerports) }}</code></div>
            </div>
          </section>
        </div>
        <div v-if="actionModal.type === 'iscsiAcl' || actionModal.type === 'editIscsiAcl'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.iscsi.target')" :tooltip="$t('message.storage.service.iscsi.target.help')" /></template>
            <a-select v-model:value="forms.iscsiAcl.targetid" show-search optionFilterProp="label" :disabled="actionModal.type === 'editIscsiAcl'">
              <a-select-option v-for="target in storageService.iscsiTargets" :key="target.id" :value="target.id" :label="targetLabel(target)">
                {{ target.targetname || target.targetName || target.name }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <div v-if="selectedIscsiAclTarget" class="storage-action-summary-box storage-action-summary-box--compact">
            <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.target.iqn') }}</span><code>{{ selectedIscsiAclTarget.targetname || selectedIscsiAclTarget.targetName || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.lun') }}</span><code>{{ selectedIscsiAclTargetLuns }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.backing.volume') }}</span><code>{{ selectedIscsiAclVolumeLabel }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.accessible.endpoints') }}</span><code>{{ selectedIscsiAclEndpointLabel }}</code></div>
          </div>
          <a-alert class="storage-action-alert" type="info" show-icon :message="$t('message.storage.service.iscsi.acl.target.scoped')" />
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.allowed.initiator.iqn')" :tooltip="$t('message.storage.service.allowed.initiator.iqn.help')" /></template>
            <a-input v-model:value="forms.iscsiAcl.initiatoriqn" :placeholder="$t('message.storage.service.allowed.initiator.iqn.placeholder')" />
          </a-form-item>
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.permission')" :tooltip="$t('message.storage.service.permission.help')" /></template>
            <a-select v-model:value="forms.iscsiAcl.permission">
              <a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option>
              <a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option>
            </a-select>
          </a-form-item>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.chap.authentication') }}</div>
            <a-form-item>
              <template #label><tooltip-label :title="$t('label.storage.service.chap.enabled')" :tooltip="$t('message.storage.service.chap.enabled.help')" /></template>
              <a-switch v-model:checked="forms.iscsiAcl.chapenabled" />
            </a-form-item>
            <a-alert
              v-if="!forms.iscsiAcl.chapenabled"
              class="storage-action-alert"
              type="info"
              show-icon
              :message="$t('message.storage.service.iscsi.chap.disabled.help')" />
            <template v-if="forms.iscsiAcl.chapenabled">
              <a-form-item required>
                <template #label><tooltip-label :title="$t('label.storage.service.chap.username')" :tooltip="$t('message.storage.service.chap.username.help')" /></template>
                <a-input v-model:value="forms.iscsiAcl.chapusername" />
              </a-form-item>
              <a-form-item required>
                <template #label><tooltip-label :title="$t('label.storage.service.chap.secret')" :tooltip="$t('message.storage.service.chap.secret.help')" /></template>
                <a-input-password v-model:value="forms.iscsiAcl.chapsecret" autocomplete="new-password" />
              </a-form-item>
            </template>
          </section>
        </div>
        <div v-if="actionModal.type === 'nvmePrepare'" class="storage-action-form storage-action-form--vertical">
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nvme.prepare.runtime') }}</div>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.engine')" :tooltip="$t('message.storage.service.nvme.engine.help')" /></template>
              <a-select v-model:value="forms.nvmePrepare.engine">
                <a-select-option value="KERNEL_NVMET">KERNEL_NVMET</a-select-option>
                <a-select-option value="SPDK">SPDK</a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.transport')" :tooltip="$t('message.storage.service.nvme.transport.help')" /></template>
              <a-select v-model:value="forms.nvmePrepare.transport">
                <a-select-option value="tcp">tcp</a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item>
              <template #label><tooltip-label :title="$t('label.storage.service.validate.only')" :tooltip="$t('message.storage.service.validate.only.help')" /></template>
              <a-switch v-model:checked="forms.nvmePrepare.validateonly" />
            </a-form-item>
          </section>
        </div>
        <div v-if="actionModal.type === 'nvmeSubsystem' || actionModal.type === 'editNvmeSubsystem'" class="storage-action-form storage-action-form--vertical">
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nvme.subsystem.settings') }}</div>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.subsystem.nqn')" :tooltip="$t('message.storage.service.subsystem.nqn.help')" /></template>
              <a-input v-model:value="forms.nvmeSubsystem.subsystemnqn" />
            </a-form-item>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.engine')" :tooltip="$t('message.storage.service.nvme.engine.help')" /></template>
              <a-select v-model:value="forms.nvmeSubsystem.engine">
                <a-select-option value="KERNEL_NVMET">KERNEL_NVMET</a-select-option>
                <a-select-option value="SPDK">SPDK</a-select-option>
              </a-select>
            </a-form-item>
            <div class="storage-action-policy-box">
              <div class="storage-action-policy-box__title">{{ $t('label.storage.service.nvme.host.policy') }}</div>
              <div class="storage-action-policy-box__row">
                <tooltip-label :title="$t('label.storage.service.allow.any.host')" :tooltip="$t('message.storage.service.allow.any.host.help')" />
                <a-switch v-model:checked="forms.nvmeSubsystem.allowanyhost" />
              </div>
            </div>
          </section>
        </div>
        <div v-if="actionModal.type === 'nvmeNamespace' || actionModal.type === 'editNvmeNamespace'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.nvme.subsystem')" :tooltip="$t('message.storage.service.nvme.subsystem.help')" /></template>
            <a-select v-model:value="forms.nvmeNamespace.subsystemid" show-search optionFilterProp="label" :disabled="actionModal.type === 'editNvmeNamespace'">
              <a-select-option v-for="target in nvmeSubsystemTargets" :key="target.id" :value="target.id" :label="targetLabel(target)">
                {{ targetLabel(target) }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-alert
            v-if="selectedNvmeNamespaceHostPolicyMessage"
            class="storage-service-inline-alert"
            show-icon
            :type="selectedNvmeNamespaceHostPolicyType"
            :message="selectedNvmeNamespaceHostPolicyMessage" />
          <a-form-item required>
            <template #label><tooltip-label :title="$t('label.storage.service.namespace.id')" :tooltip="$t('message.storage.service.nvme.namespace.id.help')" /></template>
            <a-input v-model:value="forms.nvmeNamespace.namespaceid" />
          </a-form-item>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.backing.volume') }}</div>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.volume.mode')" :tooltip="$t('message.storage.service.volume.mode.help')" /></template>
              <a-radio-group v-model:value="forms.nvmeNamespace.volumemode" :disabled="actionModal.type === 'editNvmeNamespace'">
                <a-radio value="CURRENT">{{ $t('label.storage.service.current.volume') }}</a-radio>
                <a-radio value="EXISTING">{{ $t('label.storage.service.existing.volume.select') }}</a-radio>
                <a-radio value="NEW">{{ $t('label.storage.service.new.volume') }}</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item v-if="forms.nvmeNamespace.volumemode === 'CURRENT'" required>
              <template #label><tooltip-label :title="$t('label.storage.service.current.backing.volume')" :tooltip="$t('message.storage.service.current.volume.select.help')" /></template>
              <a-select v-model:value="forms.nvmeNamespace.volumeid" :loading="volumeLoading" show-search optionFilterProp="label" :disabled="actionModal.type === 'editNvmeNamespace'">
                <a-select-option v-for="volume in currentNvmeBlockVolumes" :key="volume.id" :value="volume.id" :label="formatCurrentBackingVolumeOption(volume)">
                  {{ formatCurrentBackingVolumeOption(volume) }}
                </a-select-option>
              </a-select>
            </a-form-item>
            <volume-select
              v-if="forms.nvmeNamespace.volumemode === 'EXISTING'"
              v-model:value="forms.nvmeNamespace.volumeid"
              :volumes="availableVolumes"
              :loading="volumeLoading"
              :formatter="formatVolumeOption"
              required
              :tooltip="$t('message.storage.service.existing.volume.select.help')" />
            <template v-if="forms.nvmeNamespace.volumemode === 'NEW'">
              <a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.new.volume.name')" :tooltip="$t('message.storage.service.new.volume.name.help')" /></template><a-input v-model:value="forms.nvmeNamespace.newvolumename" /></a-form-item>
              <a-form-item required><template #label><tooltip-label :title="$t('label.diskoffering')" :tooltip="$t('message.storage.service.new.volume.diskoffering.help')" /></template><a-select v-model:value="forms.nvmeNamespace.diskofferingid" :loading="diskOfferingLoading" show-search optionFilterProp="label" @change="reconcileNvmeNewVolumeStorage"><a-select-option v-for="offering in diskOfferings" :key="offering.id" :value="offering.id" :label="offering.displaytext || offering.name">{{ offering.displaytext || offering.name }}</a-select-option></a-select></a-form-item>
              <a-form-item required><template #label><tooltip-label :title="$t('label.primary.storage')" :tooltip="$t('message.storage.service.new.volume.storage.help')" /></template><a-select v-model:value="forms.nvmeNamespace.storageid" :loading="storagePoolLoading" show-search optionFilterProp="label"><a-select-option v-for="pool in filteredNvmeNewVolumeStoragePools" :key="pool.id" :value="pool.id" :label="storagePoolLabel(pool)">{{ storagePoolLabel(pool) }}</a-select-option></a-select></a-form-item>
              <a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.volume.size.gib')" :tooltip="$t('message.storage.service.volume.size.help')" /></template><a-input-number v-model:value="forms.nvmeNamespace.newvolumesize" class="storage-input-number" :min="1" /></a-form-item>
            </template>
          </section>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nvme.listener.groups') }}</div>
            <a-alert type="info" show-icon class="storage-service-inline-alert" :message="$t('message.storage.service.nvme.listener.group.help')" />
            <a-form-item required>
              <template #label><tooltip-label :title="storageLabel('label.storage.service.listener.ports', '수신 포트 그룹')" :tooltip="$t('message.storage.service.nvme.listener.group.help')" /></template>
              <a-select v-model:value="forms.nvmeNamespace.listenerports" mode="multiple" show-search optionFilterProp="label">
                <a-select-option v-for="group in nvmeListenerGroupOptions" :key="group.value" :value="group.value" :label="group.label">
                  <div class="storage-listener-option">
                    <span>{{ group.label }}</span>
                    <small>{{ group.endpoints }}</small>
                  </div>
                </a-select-option>
              </a-select>
            </a-form-item>
            <div class="storage-action-summary-box storage-action-summary-box--compact">
              <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.nvme.namespace.port.groups') }}</span><code>{{ formatNvmeListenerGroupLabel(forms.nvmeNamespace.listenerports) }}</code></div>
              <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.nvme.namespace.endpoints') }}</span><code>{{ formatNvmeListenerGroupEndpoints(forms.nvmeNamespace.listenerports) }}</code></div>
            </div>
          </section>
        </div>
        <div v-if="['nvmeHostAcl', 'editNvmeHostAcl'].includes(actionModal.type)" class="storage-action-form storage-action-form--vertical">
          <a-alert
            v-if="!nvmeDhChapSupported"
            class="storage-service__alert"
            type="warning"
            show-icon
            :message="nvmeDhChapUnsupportedMessage" />
          <a-alert
            v-if="selectedNvmeHostAclAllowsAnyHost"
            class="storage-service__alert"
            type="warning"
            show-icon
            :message="$t('message.storage.service.nvme.host.acl.allow.any.host.blocked')" />
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nvme.host.access') }}</div>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.nvme.subsystem')" :tooltip="$t('message.storage.service.nvme.subsystem.help')" /></template>
              <a-select v-model:value="forms.nvmeHostAcl.subsystemid" show-search optionFilterProp="label" :disabled="actionModal.type === 'editNvmeHostAcl'">
                <a-select-option v-for="target in nvmeSubsystemTargets" :key="target.id" :value="target.id" :label="targetLabel(target)" :disabled="nvmeSubsystemAllowAnyHost(target)">
                  {{ targetLabel(target) }}
                  <span v-if="nvmeSubsystemAllowAnyHost(target)"> - {{ $t('label.storage.service.nvme.access.any.host') }}</span>
                </a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item required>
              <template #label><tooltip-label :title="$t('label.storage.service.allowed.host.nqn')" :tooltip="$t('message.storage.service.allowed.host.nqn.help')" /></template>
              <a-input v-model:value="forms.nvmeHostAcl.hostnqn" />
            </a-form-item>
          </section>
          <section class="storage-action-section">
            <div class="storage-action-section__title">{{ $t('label.storage.service.nvme.auth.host') }}</div>
            <a-form-item>
              <template #label><tooltip-label :title="$t('label.storage.service.dhchap.enabled')" :tooltip="$t('message.storage.service.dhchap.enabled.help')" /></template>
              <a-switch v-model:checked="forms.nvmeHostAcl.dhchapenabled" :disabled="!nvmeDhChapSupported" />
            </a-form-item>
            <a-form-item>
              <template #label><tooltip-label :title="$t('label.storage.service.dhchap.controller.enabled')" :tooltip="$t('message.storage.service.dhchap.controller.enabled.help')" /></template>
              <a-switch v-model:checked="forms.nvmeHostAcl.dhchapctrlenabled" :disabled="!nvmeDhChapSupported || !nvmeDhChapCtrlSupported || !forms.nvmeHostAcl.dhchapenabled" />
            </a-form-item>
            <a-form-item required v-if="forms.nvmeHostAcl.dhchapenabled">
              <template #label><tooltip-label :title="$t('label.storage.service.dhchap.key')" :tooltip="$t('message.storage.service.dhchap.key.help')" /></template>
              <a-input-password v-model:value="forms.nvmeHostAcl.dhchapkey" />
            </a-form-item>
            <a-form-item required v-if="forms.nvmeHostAcl.dhchapenabled && forms.nvmeHostAcl.dhchapctrlenabled">
              <template #label><tooltip-label :title="$t('label.storage.service.dhchap.controller.key')" :tooltip="$t('message.storage.service.dhchap.controller.key.help')" /></template>
              <a-input-password v-model:value="forms.nvmeHostAcl.dhchapctrlkey" />
            </a-form-item>
          </section>
        </div>
        <div v-if="actionModal.type === 'attachVolume'" class="storage-action-form storage-action-form--vertical">
          <a-row :gutter="16">
            <a-col :xs="24" :md="12"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.file.share')" :tooltip="$t('message.storage.service.select.share')" /></template><a-select v-model:value="forms.attachVolume.id" show-search optionFilterProp="label"><a-select-option v-for="share in fileShares" :key="share.id" :value="share.id" :label="shareLabel(share)">{{ shareLabel(share) }}</a-select-option></a-select></a-form-item></a-col>
            <a-col :xs="24" :md="12">
              <volume-select
                v-model:value="forms.attachVolume.volumeid"
                :volumes="availableVolumes"
                :loading="volumeLoading"
                :formatter="formatVolumeOption"
                required
                :tooltip="$t('message.storage.service.existing.volume.select.help')" />
            </a-col>
            <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.filesystem')" :tooltip="$t('message.storage.service.attach.filesystem.help')" /></template><a-select v-model:value="forms.attachVolume.filesystem"><a-select-option value="auto">auto</a-select-option><a-select-option value="xfs">xfs</a-select-option><a-select-option value="ext4">ext4</a-select-option></a-select></a-form-item></a-col>
            <a-col :xs="24" :md="12"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.mount.path')" :tooltip="$t('message.storage.service.mount.path.help')" /></template><a-input v-model:value="forms.attachVolume.path" /></a-form-item></a-col>
          </a-row>
        </div>
        <div v-if="actionModal.type === 'resizeShare'" class="storage-action-form storage-action-form--vertical">
          <a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.file.share')" :tooltip="$t('message.storage.service.select.share')" /></template><a-select v-model:value="forms.resizeShare.id" show-search optionFilterProp="label"><a-select-option v-for="share in fileShares" :key="share.id" :value="share.id" :label="shareLabel(share)">{{ shareLabel(share) }}</a-select-option></a-select></a-form-item>
          <a-form-item><template #label><tooltip-label :title="$t('label.storage.service.volume.size.gib')" :tooltip="$t('message.storage.service.volume.size.help')" /></template><a-input-number v-model:value="forms.resizeShare.size" class="storage-input-number" /></a-form-item>
          <capacity-input :label="$t('label.storage.service.file.share.capacity.limit')" :tooltip="$t('message.storage.service.quota.bytes.help')" v-model:amount="forms.resizeShare.quotaamount" v-model:unit="forms.resizeShare.quotaunit" :units="capacityUnits" />
        </div>
        <div v-if="actionModal.type === 'resizeBackingVolume'" class="storage-action-form storage-action-form--vertical">
          <a-alert class="storage-service__alert" type="info" show-icon :message="$t('message.storage.service.resize.backing.volume.help')" />
          <div class="storage-action-summary-box storage-action-summary-box--compact">
            <div class="storage-action-summary-row"><span>{{ $t('label.volumename') }}</span><code>{{ forms.resizeBackingVolume.name || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.volumeid') }}</span><code>{{ forms.resizeBackingVolume.volumeid || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.size') }}</span><code>{{ forms.resizeBackingVolume.currentSize || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.diskoffering') }}</span><code>{{ forms.resizeBackingVolume.diskOffering || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.storagepool') }}</span><code>{{ forms.resizeBackingVolume.storagePool || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.connected.resource') }}</span><code>{{ forms.resizeBackingVolume.resourceName || '-' }}</code></div>
          </div>
          <a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.new.volume.size.gib')" :tooltip="$t('message.storage.service.resize.backing.volume.size.help')" /></template><a-input-number v-model:value="forms.resizeBackingVolume.size" class="storage-input-number" :min="forms.resizeBackingVolume.minSizeGiB" :precision="0" /></a-form-item>
        </div>
        <div v-if="actionModal.type === 'detachBackingVolume'" class="storage-action-form storage-action-form--vertical">
          <a-alert type="warning" show-icon :message="$t('message.storage.service.detach.volume.warning')" />
          <div class="storage-action-summary-box storage-action-summary-box--compact">
            <div class="storage-action-summary-row"><span>{{ $t('label.volumename') }}</span><code>{{ actionModal.context?.name || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.volumeid') }}</span><code>{{ forms.detachBackingVolume.volumeid || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.size') }}</span><code>{{ actionModal.context?.size || '-' }}</code></div>
            <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.mount.path') }}</span><code>{{ actionModal.context?.mountPath || '-' }}</code></div>
          </div>
          <a-checkbox v-model:checked="forms.detachBackingVolume.confirmation">
            {{ $t('message.storage.service.detach.volume.confirm') }}
          </a-checkbox>
        </div>
        <div v-if="actionModal.type === 'disconnectSession'" class="storage-action-form storage-action-form--vertical">
          <a-row :gutter="16">
            <a-col :xs="24"><a-alert type="warning" show-icon :message="$t('message.storage.service.session.disconnect.warning')" /></a-col>
            <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.protocol')" :tooltip="$t('message.storage.service.protocol.help')" /></template><a-input v-model:value="forms.disconnectSession.protocol" disabled /></a-form-item></a-col>
            <a-col :xs="24" :md="12"><a-form-item required><template #label><tooltip-label :title="$t('label.storage.service.peer')" :tooltip="$t('message.storage.service.session.peer.help')" /></template><a-input v-model:value="forms.disconnectSession.peer" /></a-form-item></a-col>
            <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.local')" :tooltip="$t('message.storage.service.session.local.help')" /></template><a-input v-model:value="forms.disconnectSession.local" /></a-form-item></a-col>
            <a-col :xs="24" :md="12"><a-form-item><template #label><tooltip-label :title="$t('label.storage.service.force')" :tooltip="$t('message.storage.service.session.force.help')" /></template><a-switch v-model:checked="forms.disconnectSession.force" /></a-form-item></a-col>
          </a-row>
        </div>
      </a-form></div>
    </a-modal>
    <a-modal
      :visible="identityRepair.visible"
      :title="$t('label.storage.service.nic.identity.repair')"
      :confirmLoading="identityRepair.loading"
      :maskClosable="false"
      :centered="true"
      :okText="$t('label.storage.service.nic.identity.repair.apply')"
      :cancelText="$t('label.cancel')"
      :okButtonProps="{ disabled: !identityRepair.eligible }"
      wrapClassName="storage-service-action-modal"
      @ok="applyStorageIdentityRepair"
      @cancel="closeStorageIdentityRepair">
      <div class="storage-action-form storage-action-form--vertical">
        <a-alert
          class="storage-service__alert"
          :type="identityRepair.eligible ? 'warning' : 'error'"
          show-icon
          :message="identityRepair.eligible ? $t('message.storage.service.nic.identity.repair.warning') : $t('message.storage.service.nic.identity.repair.ineligible')" />
        <div class="storage-action-summary-box storage-action-summary-box--compact">
          <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.nic.persisted.primary') }}</span><code>{{ identityRepair.persistedPrimaryIp || '-' }}</code></div>
          <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.nic.runtime.primary') }}</span><code>{{ identityRepair.runtimePrimaryIp || '-' }}</code></div>
          <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.nic.aliases') }}</span><code>{{ identityRepair.aliases.join(', ') || '-' }}</code></div>
          <div class="storage-action-summary-row"><span>{{ $t('label.storage.service.nic.identity.repair.reason') }}</span><code>{{ identityRepair.reason || '-' }}</code></div>
        </div>
      </div>
    </a-modal>
  </a-spin>
</template>
<script>

import { h, resolveComponent } from 'vue'
import { api, getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import Status from '@/components/widgets/Status'
import DetailsTab from '@/components/view/DetailsTab'
import StatsTab from '@/components/view/StatsTab'
import EventsTab from '@/components/view/EventsTab'
import NicsTab from '@/views/network/NicsTab.vue'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { Empty } from 'ant-design-vue'
import {
  DeleteOutlined,
  DisconnectOutlined,
  EditOutlined,
  ExpandAltOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  LinkOutlined,
  PlusOutlined,
  PoweroffOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons-vue'

const ProtocolHeader = {
  props: {
    protocol: {
      type: String,
      required: true
    }
  },
  computed: {
    protocolLabel () {
      return this.protocol === 'NVME_OF' ? 'NVMe-oF' : this.protocol
    }
  },
  render () {
    return h('div', { class: 'protocol-header' }, [
      h('div', [
        h('h3', this.protocolLabel),
        h('p', this.$t('message.storage.service.protocol.tab.description'))
      ])
    ])
  }
}

const EllipsisText = {
  props: {
    value: {
      type: [String, Number, Boolean],
      default: '-'
    },
    code: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    displayValue () {
      if (this.value === undefined || this.value === null || this.value === '') {
        return '-'
      }
      return String(this.value)
    }
  },
  render () {
    return h('span', {
      class: {
        'storage-ellipsis': true,
        'storage-ellipsis--code': this.code
      },
      title: this.displayValue
    }, this.displayValue)
  }
}

const VolumeSelect = {
  components: {
    TooltipLabel
  },
  props: {
    value: {
      type: String,
      default: ''
    },
    volumes: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    formatter: {
      type: Function,
      required: true
    },
    required: {
      type: Boolean,
      default: false
    },
    tooltip: {
      type: String,
      default: ''
    }
  },
  emits: ['update:value'],
  render () {
    const AFormItem = resolveComponent('a-form-item')
    const ASelect = resolveComponent('a-select')
    const ASelectOption = resolveComponent('a-select-option')
    return h(AFormItem, {
      required: this.required
    }, {
      label: () => h(TooltipLabel, {
        title: this.$t('label.storage.service.existing.volume.select'),
        tooltip: this.tooltip
      }),
      default: () => [
        h(ASelect, {
          value: this.value,
          loading: this.loading,
          allowClear: true,
          showSearch: true,
          optionFilterProp: 'label',
          onChange: value => this.$emit('update:value', value)
        }, {
          default: () => this.volumes.map(volume => h(ASelectOption, {
            key: volume.id,
            value: volume.id,
            label: this.formatter(volume)
          }, {
            default: () => this.formatter(volume)
          }))
        })
      ]
    })
  }
}

const CapacityInput = {
  components: {
    TooltipLabel
  },
  props: {
    label: {
      type: String,
      required: true
    },
    amount: {
      type: [Number, String],
      default: null
    },
    unit: {
      type: String,
      default: 'GiB'
    },
    units: {
      type: Array,
      default: () => []
    },
    tooltip: {
      type: String,
      default: ''
    },
    required: {
      type: Boolean,
      default: false
    }
  },
  emits: ['update:amount', 'update:unit'],
  render () {
    const AFormItem = resolveComponent('a-form-item')
    const AInputGroup = resolveComponent('a-input-group')
    const AInputNumber = resolveComponent('a-input-number')
    const ASelect = resolveComponent('a-select')
    const ASelectOption = resolveComponent('a-select-option')
    return h(AFormItem, {
      required: this.required
    }, {
      label: () => h(TooltipLabel, {
        title: this.label,
        tooltip: this.tooltip
      }),
      default: () => [
        h(AInputGroup, {
          compact: true,
          class: 'capacity-input-group'
        }, {
          default: () => [
            h(AInputNumber, {
              value: this.amount,
              min: 0,
              onChange: value => this.$emit('update:amount', value)
            }),
            h(ASelect, {
              value: this.unit,
              onChange: value => this.$emit('update:unit', value)
            }, {
              default: () => this.units.map(unitItem => h(ASelectOption, {
                key: unitItem.value,
                value: unitItem.value
              }, {
                default: () => unitItem.label
              }))
            })
          ]
        })
      ]
    })
  }
}

export default {
  name: 'SharedFSTab',
  components: {
    DetailsTab,
    StatsTab,
    EventsTab,
    NicsTab,
    TooltipButton,
    TooltipLabel,
    Status,
    ProtocolHeader,
    EllipsisText,
    VolumeSelect,
    CapacityInput,
    DeleteOutlined,
    DisconnectOutlined,
    EditOutlined,
    ExpandAltOutlined,
    FullscreenExitOutlined,
    FullscreenOutlined,
    LinkOutlined,
    PlusOutlined,
    PoweroffOutlined,
    ReloadOutlined,
    SafetyCertificateOutlined
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  emits: ['wide-layout-change'],
  inject: ['parentFetchData'],
  data () {
    return {
      vm: {},
      volume: {},
      volumes: [],
      availableVolumes: [],
      volumeLoading: false,
      storagePools: [],
      storagePoolLoading: false,
      virtualmachines: [],
      currentTab: 'details',
      protocolWideLayout: false,
      dataResource: {},
      storageService: {
        loading: false,
        initialLoading: false,
        refreshing: false,
        loaded: false,
        instance: null,
        health: [],
        inventory: [],
        protocols: [],
        sessions: [],
        domains: [],
        nfsExports: [],
        smbShares: [],
        iscsiTargets: [],
        nvmeSubsystems: [],
        nvmeNamespaces: [],
        nfsAcls: [],
        smbAcls: [],
        iscsiAcls: [],
        nvmeHostAcls: [],
        backingVolumes: []
      },
      storageRefreshGeneration: 0,
      diskOfferings: [],
      diskOfferingLoading: false,
      actionModal: {
        visible: false,
        type: '',
        context: null,
        loading: false
      },
      actionLoading: {},
      identityRepair: {
        visible: false,
        loading: false,
        eligible: false,
        persistedPrimaryIp: '',
        runtimePrimaryIp: '',
        aliases: [],
        reason: ''
      },
      capacityUnits: [
        { value: 'B', label: 'B', multiplier: 1 },
        { value: 'MiB', label: 'MiB', multiplier: 1024 * 1024 },
        { value: 'GiB', label: 'GiB', multiplier: 1024 * 1024 * 1024 },
        { value: 'TiB', label: 'TiB', multiplier: 1024 * 1024 * 1024 * 1024 }
      ],
      forms: {
        enableProtocol: {
          protocol: 'NFS',
          listenipmode: 'EXISTING',
          listenip: '',
          port: null,
          protocolmode: 'V4_ONLY'
        },
        nfsExport: {
          name: '',
          path: '',
          volumeid: '',
          volumemode: 'CURRENT',
          newvolumename: '',
          diskofferingid: '',
          storageid: '',
          newvolumesize: null,
          filesystem: 'xfs',
          relativepath: '',
          createdirectory: false,
          quotaamount: null,
          quotaunit: 'GiB',
          protocolmode: 'V4_ONLY',
          endpointmode: 'LISTENER_GROUP',
          listenips: [],
          listenerports: [2049],
          readonly: false,
          rootsquash: true,
          allsquash: false,
          anonuid: 65534,
          anongid: 65534,
          owneruid: 65534,
          ownergid: 65534,
          mode: '0775',
          recursivepermission: false,
          sync: true,
          secure: false
        },
        nfsAcl: {
          exportid: '',
          principaltype: 'CIDR',
          principal: '',
          principals: [],
          permission: 'READ_WRITE',
          rootsquash: true,
          allsquash: false,
          anonuid: null,
          anongid: null,
          sync: true,
          secure: false
        },
        smbShare: {
          name: '',
          path: '',
          volumeid: '',
          volumemode: 'CURRENT',
          newvolumename: '',
          diskofferingid: '',
          storageid: '',
          newvolumesize: null,
          filesystem: 'xfs',
          quotaamount: null,
          quotaunit: 'GiB',
          readonly: false,
          browseable: true,
          guestok: false,
          createdirectory: true,
          crossprotocol: false,
          directorymode: '0770'
        },
        smbAcl: {
          id: '',
          shareid: '',
          principaltype: 'LOCAL_USER',
          principal: '',
          permission: 'READ_WRITE',
          password: ''
        },
        adJoin: {
          domainname: '',
          username: '',
          password: '',
          organizationalunit: '',
          dnsservers: '',
          workgroup: ''
        },
        adLeave: {
          confirmation: '',
          username: '',
          password: ''
        },
        iscsiTarget: {
          id: '',
          targetname: '',
          lun: '0',
          volumeid: '',
          volumemode: 'CURRENT',
          newvolumename: '',
          diskofferingid: '',
          storageid: '',
          newvolumesize: null,
          backingpath: '',
          endpointmode: 'LISTENER_GROUP',
          listenerports: [3260]
        },
        iscsiAcl: {
          targetid: '',
          initiatoriqn: '',
          permission: 'READ_WRITE',
          chapenabled: false,
          chapusername: '',
          chapsecret: '',
          mutualchapenabled: false,
          mutualchapusername: '',
          mutualchapsecret: ''
        },
        nvmePrepare: {
          engine: 'KERNEL_NVMET',
          transport: 'tcp',
          validateonly: true
        },
        nvmeSubsystem: {
          subsystemnqn: '',
          allowanyhost: false,
          engine: 'KERNEL_NVMET',
          transport: 'tcp'
        },
        nvmeNamespace: {
          id: '',
          subsystemid: '',
          namespaceid: '1',
          volumeid: '',
          volumemode: 'CURRENT',
          newvolumename: '',
          diskofferingid: '',
          storageid: '',
          newvolumesize: null,
          backingpath: '',
          listenerports: [4420]
        },
        nvmeHostAcl: {
          id: '',
          subsystemid: '',
          hostnqn: '',
          dhchapenabled: false,
          dhchapkey: '',
          dhchapctrlenabled: false,
          dhchapctrlkey: ''
        },
        attachVolume: {
          id: '',
          volumeid: '',
          path: '',
          filesystem: 'auto',
          importmode: 'MOUNT_EXISTING'
        },
        resizeShare: {
          id: '',
          size: null,
          quotaamount: null,
          quotaunit: 'GiB',
          resizevolume: true
        },
        resizeBackingVolume: {
          volumeid: '',
          size: null,
          currentSizeBytes: null,
          currentSizeGiB: null,
          minSizeGiB: 1,
          name: '',
          currentSize: '',
          diskOffering: '',
          storagePool: '',
          resourceName: ''
        },
        detachBackingVolume: {
          volumeid: '',
          confirmation: false
        },
        disconnectSession: {
          protocol: '',
          peer: '',
          local: '',
          sessionid: '',
          force: true
        },
        deleteConfirm: {
          resourceType: '',
          command: '',
          id: '',
          protocol: '',
          confirmation: ''
        },
        deleteEndpoint: {
          protocol: 'NFS',
          listenip: '',
          confirmation: ''
        }
      }
    }
  },
  computed: {
    hasStorageServiceApi () {
      return 'listStorageServiceInstances' in this.$store.getters.apis
    },
    storageIdentityDriftProtocols () {
      return (this.storageService.protocols || []).filter(protocol =>
        String(protocol.identitystatus || protocol.identityStatus || '').toUpperCase() === 'DRIFT')
    },
    storageIdentityDrift () {
      return this.storageIdentityDriftProtocols.length > 0
    },
    canRepairStorageIdentity () {
      const apiMap = this.$store.getters.apis || {}
      return this.storageIdentityDrift && !!this.storageService.instance && 'repairStorageServiceNicIdentity' in apiMap
    },
    storageIdentityWarning () {
      const protocol = this.storageIdentityDriftProtocols[0] || {}
      return this.$t('message.storage.service.nic.identity.drift.description', {
        persisted: protocol.primaryip || protocol.primaryIp || '-',
        runtime: protocol.runtimeprimaryip || protocol.runtimePrimaryIp || '-'
      })
    },
    fileShares () {
      return [
        ...this.storageService.nfsExports,
        ...this.storageService.smbShares
      ]
    },
    currentBackingVolumes () {
      const volumes = []
      const seen = new Set()
      const add = volume => {
        if (!volume || !volume.id || seen.has(String(volume.id))) {
          return
        }
        if (!this.belongsToCurrentServiceVm(volume)) {
          return
        }
        const type = String(volume.type || '').toUpperCase()
        if (type && type !== 'DATADISK') {
          return
        }
        seen.add(String(volume.id))
        volumes.push(volume)
      }
      this.storageService.backingVolumes.forEach(add)
      if (this.volume.id) {
        add(this.volume)
      }
      return volumes
    },
    nfsBackingVolumes () {
      const volumeIds = new Set()
      this.storageService.nfsExports.forEach(share => {
        [share.volumeid, share.volumeId, share.volumeuuid, share.volumeUuid]
          .filter(Boolean)
          .forEach(value => volumeIds.add(String(value)))
      })
      if (!volumeIds.size) {
        return []
      }
      const seen = new Set()
      return this.currentBackingVolumes.filter(volume => {
        const ids = [volume.id, volume.uuid].filter(Boolean).map(value => String(value))
        const canonicalId = String(volume.id || volume.uuid || '')
        if (!ids.some(id => volumeIds.has(id)) || !canonicalId || seen.has(canonicalId)) {
          return false
        }
        seen.add(canonicalId)
        return true
      })
    },
    currentIscsiBlockVolumes () {
      const used = new Set()
      const addUsed = value => {
        if (value) {
          used.add(String(value))
        }
      }
      this.storageService.nfsExports.forEach(share => addUsed(share.volumeid || share.volumeId))
      this.storageService.smbShares.forEach(share => addUsed(share.volumeid || share.volumeId))
      this.storageService.iscsiTargets.forEach(target => addUsed(target.volumeid || target.volumeId))
      this.storageService.nvmeNamespaces.forEach(target => addUsed(target.volumeid || target.volumeId))
      return this.currentBackingVolumes.filter(volume => {
        if (used.has(String(volume.id)) || used.has(String(volume.uuid))) {
          return false
        }
        const mountPath = this.currentBackingVolumeMountPath(volume)
        return !mountPath || mountPath === '-'
      })
    },
    currentNvmeBlockVolumes () {
      return this.currentIscsiBlockVolumes
    },
    nvmeSubsystemTargets () {
      return this.storageService.nvmeSubsystems.filter(target => {
        const config = this.parseStorageConfig(target.config)
        return (config.type || '').toLowerCase() === 'subsystem' || !(target.volumeid || target.volumeId)
      })
    },
    nvmeNamespaceTargets () {
      return this.storageService.nvmeNamespaces.filter(target => {
        const config = this.parseStorageConfig(target.config)
        return (config.type || '').toLowerCase() === 'namespace' || !!(target.volumeid || target.volumeId)
      })
    },
    selectedCurrentBackingVolume () {
      const selected = String(this.forms.nfsExport.volumeid || '')
      if (!selected) {
        return null
      }
      return this.currentBackingVolumes.find(volume => String(volume.id) === selected || String(volume.uuid) === selected) || null
    },
    selectedSmbCurrentBackingVolume () {
      const selected = String(this.forms.smbShare.volumeid || '')
      if (!selected) {
        return null
      }
      return this.currentBackingVolumes.find(volume => String(volume.id) === selected || String(volume.uuid) === selected) || null
    },
    selectedIscsiCurrentBackingVolume () {
      const selected = String(this.forms.iscsiTarget.volumeid || '')
      if (!selected) {
        return null
      }
      return this.currentIscsiBlockVolumes.find(volume => String(volume.id) === selected || String(volume.uuid) === selected) || null
    },
    selectedNvmeDiskOffering () {
      return this.diskOfferings.find(offering => offering.id === this.forms.nvmeNamespace.diskofferingid)
    },
    selectedNvmeDiskOfferingTags () {
      return this.extractStorageTags(this.selectedNvmeDiskOffering)
    },
    filteredNvmeNewVolumeStoragePools () {
      const requiredTags = this.selectedNvmeDiskOfferingTags.map(tag => tag.toLowerCase())
      if (!requiredTags.length) {
        return this.storagePools
      }
      return this.storagePools.filter(pool => {
        const poolTags = this.extractStorageTags(pool).map(tag => tag.toLowerCase())
        return requiredTags.every(tag => poolTags.includes(tag))
      })
    },
    selectedIscsiAclTarget () {
      const selected = String(this.forms.iscsiAcl.targetid || '')
      if (!selected) {
        return null
      }
      return this.storageService.iscsiTargets.find(target => String(target.id) === selected || String(target.uuid) === selected) || null
    },
    selectedIscsiAclTargetLuns () {
      const target = this.selectedIscsiAclTarget
      if (!target) {
        return '-'
      }
      return target.targetluns || target.targetLuns || target.lunornamespace || target.lunOrNamespace || '0'
    },
    selectedIscsiAclVolumeLabel () {
      const target = this.selectedIscsiAclTarget
      if (!target) {
        return '-'
      }
      const volume = this.volumeForTarget(target)
      const name = volume.name || volume.displayname || target.volumename || target.volumeName || target.volumeid || target.volumeId || '-'
      const size = this.formatCapacityValue(target.volumesizebytes || target.volumeSizeBytes || volume.size || this.volume.size || this.resource.size)
      return size && size !== '-' ? `${name} / ${size}` : name
    },
    selectedIscsiAclEndpointLabel () {
      const target = this.selectedIscsiAclTarget
      if (!target) {
        return '-'
      }
      const config = this.parseStorageConfig(target.config)
      return target.endpoints || target.endpoint || this.formatIscsiListenerGroupEndpoints(this.normalizeListenerPorts(target.listenerports || target.listenerPorts || config.listenerGroupPorts || config.listenerports || 3260))
    },
    selectedNvmeHostAclSubsystem () {
      const selected = String(this.forms.nvmeHostAcl.subsystemid || '')
      if (!selected) {
        return null
      }
      return this.nvmeSubsystemTargets.find(target => String(target.id || '') === selected || String(target.uuid || '') === selected) || null
    },
    selectedNvmeHostAclAllowsAnyHost () {
      return this.nvmeSubsystemAllowAnyHost(this.selectedNvmeHostAclSubsystem)
    },
    selectedNfsDiskOffering () {
      return this.diskOfferings.find(offering => offering.id === this.forms.nfsExport.diskofferingid)
    },
    selectedNfsDiskOfferingTags () {
      return this.extractStorageTags(this.selectedNfsDiskOffering)
    },
    filteredNfsNewVolumeStoragePools () {
      const requiredTags = this.selectedNfsDiskOfferingTags.map(tag => tag.toLowerCase())
      if (!requiredTags.length) {
        return this.storagePools
      }
      return this.storagePools.filter(pool => {
        const poolTags = this.extractStorageTags(pool).map(tag => tag.toLowerCase())
        return requiredTags.every(tag => poolTags.includes(tag))
      })
    },
    selectedSmbDiskOffering () {
      return this.diskOfferings.find(offering => offering.id === this.forms.smbShare.diskofferingid)
    },
    selectedSmbDiskOfferingTags () {
      return this.extractStorageTags(this.selectedSmbDiskOffering)
    },
    filteredSmbNewVolumeStoragePools () {
      const requiredTags = this.selectedSmbDiskOfferingTags.map(tag => tag.toLowerCase())
      if (!requiredTags.length) {
        return this.storagePools
      }
      return this.storagePools.filter(pool => {
        const poolTags = this.extractStorageTags(pool).map(tag => tag.toLowerCase())
        return requiredTags.every(tag => poolTags.includes(tag))
      })
    },
    selectedIscsiDiskOffering () {
      return this.diskOfferings.find(offering => offering.id === this.forms.iscsiTarget.diskofferingid)
    },
    selectedIscsiDiskOfferingTags () {
      return this.extractStorageTags(this.selectedIscsiDiskOffering)
    },
    filteredIscsiNewVolumeStoragePools () {
      const requiredTags = this.selectedIscsiDiskOfferingTags.map(tag => tag.toLowerCase())
      if (!requiredTags.length) {
        return this.storagePools
      }
      return this.storagePools.filter(pool => {
        const poolTags = this.extractStorageTags(pool).map(tag => tag.toLowerCase())
        return requiredTags.every(tag => poolTags.includes(tag))
      })
    },
    serviceNics () {
      return [
        ...(this.vm.nic || []),
        ...(this.resource.nic || [])
      ]
    },
    runtimeNetworkAddresses () {
      const items = []
      const seen = new Set()
      const add = (source, label) => {
        const values = Array.isArray(source) ? source : (source ? [source] : [])
        values.forEach(item => {
          let ipaddress = ''
          let interfaceName = ''
          let cidr = ''
          let role = ''
          let secondary = false
          let dynamic = false
          if (typeof item === 'string') {
            ipaddress = item.includes('/') ? item.split('/', 1)[0] : item
            cidr = item
          } else if (item && typeof item === 'object') {
            ipaddress = item.ipaddress || item.ipAddress || item.ip || item.address || item.local || ''
            interfaceName = item.interface || item.ifname || item.device || ''
            cidr = item.cidr || item.prefix || item.netmask || ''
            role = String(item.role || '').toLowerCase()
            secondary = this.boolValue(item.secondary) || role === 'secondary'
            dynamic = this.boolValue(item.dynamic)
          }
          ipaddress = String(ipaddress || '').trim()
          if (this.isWildcardListenIp(ipaddress) || !ipaddress || seen.has(ipaddress)) {
            return
          }
          if (!role) {
            role = secondary ? 'secondary' : 'primary'
          }
          seen.add(ipaddress)
          const roleLabel = role === 'secondary' ? this.$t('label.secondaryips') : this.$t('label.primary')
          items.push({
            ipaddress,
            interfaceName,
            cidr,
            role,
            secondary,
            primary: role === 'primary',
            dynamic,
            label: [label, roleLabel, interfaceName, ipaddress].filter(Boolean).join(' / ')
          })
        })
      }
      const healthNetwork = this.parsedHealth.network || {}
      const inventoryNetwork = this.parsedInventory.network || {}
      add(healthNetwork.addresses || this.parsedHealth.networkAddresses, this.$t('label.storage.service.runtime.endpoint'))
      add(inventoryNetwork.addresses || this.parsedInventory.networkAddresses, this.$t('label.storage.service.runtime.endpoint'))
      return items
    },
    serviceListenIps () {
      const items = []
      const seen = new Set()
      const addIp = (nic, ipaddress, kind) => {
        if (this.isWildcardListenIp(ipaddress) || !ipaddress || seen.has(ipaddress)) {
          return
        }
        seen.add(ipaddress)
        const labelParts = [nic.networkname, ipaddress].filter(Boolean)
        if (kind === 'SECONDARY') {
          labelParts.push(this.$t('label.secondaryips'))
        } else if (kind === 'PRIMARY') {
          labelParts.push(this.$t('label.primary'))
        }
        items.push({
          ...nic,
          key: `${kind}-${nic.id || nic.networkid || ipaddress}-${ipaddress}`,
          ipaddress,
          kind,
          label: labelParts.join(' / ')
        })
      }
      this.runtimeNetworkAddresses.forEach(address => {
        addIp({
          id: address.interfaceName || address.ipaddress,
          networkname: [this.$t('label.storage.service.runtime.endpoint'), address.interfaceName].filter(Boolean).join(' / '),
          cidr: address.cidr
        }, address.ipaddress, address.role === 'secondary' ? 'SECONDARY' : 'PRIMARY')
      })
      this.serviceNics.forEach(nic => {
        addIp(nic, nic.ipaddress, 'PRIMARY')
        ;(nic.secondaryip || []).forEach(secondary => addIp(nic, secondary.ipaddress, 'SECONDARY'))
      })
      this.storageService.nfsExports.forEach(share => {
        this.normalizeEndpointIps(share.listenips || share.listenIps || this.parseStorageConfig(share.config).listenIps || this.parseStorageConfig(share.config).listenips).forEach(ipaddress => {
          addIp({
            id: `nfs-${share.id || share.name || ipaddress}`,
            networkname: this.$t('label.storage.service.configured.endpoint')
          }, ipaddress, 'CONFIGURED')
        })
      })
      return items
    },
    nfsListenerGroupOptions () {
      const ports = new Set([2049])
      this.nfsRuntimeProtocolEntries().forEach(entry => {
        const port = Number(entry.port)
        if (Number.isFinite(port) && port > 0) ports.add(port)
      })
      const addPorts = value => this.normalizeListenerPorts(value).forEach(port => ports.add(port))
      const addConfigPorts = config => {
        if (!config || typeof config !== 'object') return
        addPorts(config.listenerGroupPorts ?? config.listenergroupports ?? config.listenerPorts ?? config.listenerports)
        const groups = config.listenerGroups ?? config.listenergroups
        if (Array.isArray(groups)) {
          groups.forEach(group => addPorts(group?.port ?? group?.listenerPort ?? group?.listenerport))
        }
      }
      (this.storageService.protocols || []).forEach(protocol => {
        addPorts(protocol?.port ?? protocol?.listenPort ?? protocol?.listenport ?? protocol?.endpointPort)
        addConfigPorts(this.parseStorageConfig(protocol.config))
      })
      this.storageService.nfsExports.forEach(share => addConfigPorts(this.parseStorageConfig(share.config)))
      return Array.from(ports).sort((a, b) => a - b).map(port => ({
        value: port,
        label: `${this.$t('label.port')} ${port} / ${this.formatNfsListenerGroupEndpoints([port])}`
      }))
    },
    iscsiListenerGroupOptions () {
      const ports = new Set([3260])
      const addPorts = value => this.normalizeListenerPorts(value).forEach(port => ports.add(port))
      const addConfigPorts = config => {
        if (!config || typeof config !== 'object') return
        addPorts(config.listenerGroupPorts ?? config.listenergroupports ?? config.listenerPorts ?? config.listenerports)
      }
      const protocols = this.storageService.protocols || []
      protocols.forEach(protocol => {
        if (String(protocol.protocol || protocol.name || '').toUpperCase() === 'ISCSI') {
          addPorts(protocol?.port ?? protocol?.listenPort ?? protocol?.listenport ?? protocol?.endpointPort)
          addConfigPorts(this.parseStorageConfig(protocol.config))
        }
      })
      this.storageService.iscsiTargets.forEach(target => addConfigPorts(this.parseStorageConfig(target.config)))
      return Array.from(ports).sort((a, b) => a - b).map(port => ({
        value: port,
        label: `${this.$t('label.port')} ${port} / ${this.formatIscsiListenerGroupEndpoints([port])}`
      }))
    },
    nvmeListenerGroupOptions () {
      const ports = new Set([4420])
      const addPorts = value => this.normalizeListenerPorts(value).forEach(port => ports.add(port))
      const addConfigPorts = config => {
        if (!config || typeof config !== 'object') return
        addPorts(config.listenerGroupPorts ?? config.listenergroupports ?? config.listenerPorts ?? config.listenerports)
      }
      const protocols = this.storageService.protocols || []
      protocols.forEach(protocol => {
        if (String(protocol.protocol || protocol.name || '').toUpperCase() === 'NVME_OF') {
          addPorts(protocol?.port ?? protocol?.listenPort ?? protocol?.listenport ?? protocol?.endpointPort)
          addConfigPorts(this.parseStorageConfig(protocol.config))
        }
      })
      this.nvmeNamespaceTargets.forEach(target => addConfigPorts(this.parseStorageConfig(target.config)))
      const statusMap = this.nvmeHealthPortStatusMap()
      return Array.from(ports).sort((a, b) => a - b).map(port => {
        const statusLabel = this.nvmePortStatusLabel(statusMap[port])
        const summary = this.nvmeListenerGroupSummary([port])
        return {
          value: port,
          label: `${summary.listenerGroupLabel}${statusLabel ? ` / ${statusLabel}` : ''}`,
          endpoints: summary.effectiveEndpoints
        }
      })
    },
    serviceEndpoint () {
      const nic = this.serviceListenIps.find(item => item.kind === 'PRIMARY' && item.ipaddress) || this.serviceListenIps.find(item => item.ipaddress) || {}
      return nic.ipaddress || this.vm.ipaddress || this.resource.ipaddress || this.resource.serviceip || this.resource.ip || ''
    },
    serviceEndpoints () {
      const endpoints = this.serviceListenIps.map(item => item.ipaddress).filter(ip => ip && !this.isWildcardListenIp(ip))
      const fallback = this.serviceEndpoint
      if (fallback && !endpoints.includes(fallback)) {
        endpoints.unshift(fallback)
      }
      return endpoints
    },
    removableServiceEndpoints () {
      return this.serviceListenIps.filter(item => {
        if (!item.ipaddress) return false
        return item.kind !== 'PRIMARY'
      })
    },
    serviceEndpointSummary () {
      const nfsEndpoints = this.nfsRuntimeEndpointSummary()
      if (nfsEndpoints) {
        return nfsEndpoints
      }
      return this.serviceEndpoints.length ? this.serviceEndpoints.join(', ') : this.serviceEndpoint
    },
    selectedNfsAclExport () {
      const selected = String(this.forms.nfsAcl.exportid || '')
      return this.storageService.nfsExports.find(share => String(share.id) === selected || String(share.uuid) === selected) || null
    },
    parsedHealth () {
      return this.parseRuntimeResult(this.storageService.health[0])
    },
    parsedInventory () {
      return this.parseRuntimeResult(this.storageService.inventory[0])
    },
    nvmeCapability () {
      return this.parsedHealth?.capabilities?.nvmeof ||
        this.parsedInventory?.capabilities?.nvmeof ||
        this.parsedInventory?.nvmeofRuntime?.capabilities ||
        {}
    },
    nvmeDhChapSupported () {
      return this.boolValue(this.nvmeCapability.dhChapSupported)
    },
    nvmeDhChapCtrlSupported () {
      return this.boolValue(this.nvmeCapability.dhChapCtrlSupported)
    },
    nvmeDhChapUnsupportedMessage () {
      const reason = this.nvmeCapability.reason
      if (reason) {
        return this.$t('message.storage.service.nvme.dhchap.unsupported.with.reason', { reason })
      }
      return this.$t('message.storage.service.nvme.dhchap.unsupported')
    },
    nvmeProtocolListenerEntries () {
      return this.protocolListenerEntries('NVME_OF').map(entry => {
        const effectiveEndpoints = this.nvmeEffectiveEndpointsForListener(entry)
        const state = this.nvmeListenerState(entry)
        return {
          key: `${entry.listenIp}:${entry.port}`,
          listenIp: entry.listenIp,
          port: entry.port,
          wildcard: this.isWildcardListenIp(entry.listenIp),
          effectiveEndpoints,
          state,
          status: state
        }
      })
    },
    nvmeEndpointSummary () {
      const endpoints = []
      this.nvmeProtocolListenerEntries.forEach(entry => {
        entry.effectiveEndpoints.forEach(item => {
          if (item.endpoint && !endpoints.includes(item.endpoint)) {
            endpoints.push(item.endpoint)
          }
        })
      })
      return endpoints.length ? endpoints.join(', ') : `${this.serviceEndpoint || '<service-ip>'}:4420`
    },
    primaryNvmeEndpoint () {
      return this.nvmeEndpointSummary.split(',').map(item => item.trim()).filter(Boolean)[0] || `${this.serviceEndpoint || '<service-ip>'}:4420`
    },
    nvmeListenerColumns () {
      return this.protocolListenerColumns
    },
    protocolListenerColumns () {
      return [
        { title: this.$t('label.storage.service.listen.ip'), dataIndex: 'listenIp', key: 'listenIp', fixed: 'left', width: 190, code: true },
        { title: this.$t('label.port'), dataIndex: 'port', key: 'port', width: 110, code: true },
        { title: this.$t('label.storage.service.listener.type'), dataIndex: 'type', key: 'type', width: 160 },
        { title: this.$t('label.storage.service.accessible.endpoints'), dataIndex: 'effectiveEndpoints', key: 'effectiveEndpoints', width: 420, code: true },
        { title: this.$t('label.storage.service.linked.resources'), dataIndex: 'linkedResourceCount', key: 'linkedResourceCount', width: 140 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 140, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nvmeListenerRows () {
      return this.protocolListenerRows('NVME_OF')
    },
    nfsListenerRows () {
      return this.protocolListenerRows('NFS')
    },
    smbListenerRows () {
      return this.protocolListenerRows('SMB')
    },
    iscsiListenerRows () {
      return this.protocolListenerRows('ISCSI')
    },
    latestHealth () {
      return {
        success: this.parsedHealth.success !== false,
        status: this.parsedHealth.status || this.storageService.health[0]?.status || '-'
      }
    },
    qgaStatus () {
      return this.parsedHealth.qga || this.parsedHealth.qgaStatus || this.parsedHealth.guestAgent || '-'
    },
    allSessions () {
      return this.sessionsRuntime.sessions || []
    },
    sessionsRuntime () {
      return this.parseRuntimeResult(this.storageService.sessions[0])
    },
    iscsiSessionRuntimeWarning () {
      const runtime = this.sessionsRuntime || {}
      const observed = Number(runtime.observedIscsiTcpCount || runtime.observediscsiTcpCount || 0)
      const sessions = this.protocolSessions('ISCSI')
      const hasUnmappedSession = sessions.some(session => String(session.mappingStatus || session.mappingstatus || '').toUpperCase() === 'UNMAPPED')
      if (!observed || (sessions.length > 0 && !hasUnmappedSession)) {
        return ''
      }
      return this.$t('message.storage.service.iscsi.sessions.incomplete', {
        count: observed
      })
    },
    activeServiceTypes () {
      const protocols = []
      const enabled = new Set((this.storageService.protocols || [])
        .filter(item => item.enabled === undefined || this.boolValue(item.enabled))
        .map(item => String(item.protocol || item.name || '').toUpperCase()))
      if (enabled.has('NFS') || this.storageService.nfsExports.length > 0) protocols.push('NFS')
      if (enabled.has('SMB') || this.storageService.smbShares.length > 0) protocols.push('SMB')
      if (enabled.has('ISCSI') || this.storageService.iscsiTargets.length > 0) protocols.push('iSCSI')
      if (enabled.has('NVME_OF') || this.storageService.nvmeSubsystems.length > 0) protocols.push('NVMe-oF')
      return protocols
    },
    nfsConnectionCommands () {
      const runtimeEndpoints = this.nfsRuntimeProtocolEntries()
      const runtimeMode = this.nfsRuntimeProtocolMode()
      const listenerEndpoints = this.protocolEndpointValues(this.nfsListenerRows)
      const legacyEndpoints = []
      runtimeEndpoints.forEach(entry => {
        const port = Number(entry.port || this.nfsRuntimePort() || this.defaultProtocolPort('NFS'))
        if (this.isWildcardListenIp(entry.listenIp)) {
          this.serviceEndpoints.forEach(ip => legacyEndpoints.push(`${ip}:${port}`))
        } else if (entry.listenIp) {
          legacyEndpoints.push(`${entry.listenIp}:${port}`)
        }
      })
      const endpoints = listenerEndpoints.length ? listenerEndpoints : [...new Set(legacyEndpoints)]
      const endpointParts = endpoints.map(endpoint => this.splitEndpointValue(endpoint))
      const uniquePorts = [...new Set(endpointParts.map(entry => entry.port).filter(Boolean))]
      const endpointIp = endpointParts.length === 1
        ? endpointParts[0].ip
        : `<${this.$t('label.storage.service.endpoint.ip.placeholder')}>`
      const endpointPort = uniquePorts.length === 1
        ? uniquePorts[0]
        : '<port>'
      const exportName = `<${this.$t('label.storage.service.export.name.placeholder')}>`
      const mountPath = `<${this.$t('label.storage.service.local.mount.path.placeholder')}>`
      const commands = [`mount -t nfs4 -o vers=4.1,proto=tcp,port=${endpointPort} ${endpointIp}:/${exportName} ${mountPath}`]
      if (runtimeMode === 'V3V4_DUAL') {
        commands.push(`mount -t nfs -o vers=3,proto=tcp,port=${endpointPort} ${endpointIp}:/export/${exportName} ${mountPath}`)
        commands.push(`showmount -e ${endpointIp}`)
      }
      return commands
    },
    smbProtocolRows () {
      return (this.storageService.protocols || []).filter(protocol => {
        const name = String(protocol.protocol || protocol.name || protocol.type || '').toUpperCase()
        return name === 'SMB' && protocol.enabled !== false && protocol.state !== 'Disabled'
      })
    },
    smbEffectivePorts () {
      const ports = []
      const add = value => {
        const port = Number(value)
        if (Number.isFinite(port) && port > 0 && !ports.includes(port)) {
          ports.push(port)
        }
      }
      this.smbProtocolRows.forEach(protocol => {
        add(protocol.port || protocol.listenPort || protocol.listenport || protocol.endpointPort)
        const config = this.parseStorageConfig(protocol.config || protocol.configjson || protocol.configJson)
        add(config.port || config.listenPort || config.listenport || config.endpointPort)
      })
      if (!ports.length && (this.isProtocolEnabled('SMB') || this.storageService.smbShares.length > 0)) {
        add(this.defaultProtocolPort('SMB'))
      }
      return ports.length ? ports : [this.defaultProtocolPort('SMB')]
    },
    smbEffectiveEndpointPairs () {
      const pairs = []
      const addPair = (ipValue, portValue) => {
        const ip = String(ipValue || '').trim()
        const port = Number(portValue)
        if (!ip || this.isWildcardListenIp(ip) || !Number.isFinite(port) || port <= 0) return
        const key = `${ip}:${port}`
        if (!pairs.some(pair => pair.key === key)) pairs.push({ key, ip, port })
      }
      const listeners = this.protocolListenerEntries('SMB')
      listeners.forEach(listener => {
        const apiEndpoints = Array.isArray(listener.effectiveEndpoints) ? listener.effectiveEndpoints : []
        if (apiEndpoints.length) {
          apiEndpoints.forEach(endpoint => addPair(
            endpoint?.ipaddress || endpoint?.ipAddress || endpoint?.ip,
            endpoint?.port || listener.port
          ))
        } else if (this.isWildcardListenIp(listener.listenIp)) {
          this.serviceEndpoints.forEach(ip => addPair(ip, listener.port))
        } else {
          addPair(listener.listenIp, listener.port)
        }
      })
      if (!listeners.length) {
        const fallbackIp = this.serviceEndpoint
        this.smbEffectivePorts.forEach(port => addPair(fallbackIp, port))
      }
      return pairs
    },
    smbEndpointPairSummary () {
      return this.smbEffectiveEndpointPairs.map(pair => `${pair.ip}:${pair.port}`).join(', ')
    },
    smbConnectionCommands () {
      const share = `<${this.$t('label.storage.service.share.name.placeholder')}>`
      const user = `<${this.$t('label.username')}>`
      const pairs = this.smbEffectiveEndpointPairs.length ? this.smbEffectiveEndpointPairs : [{ ip: '<service-ip>' }]
      const commands = []
      pairs.forEach(pair => commands.push(`\\\\${pair.ip}\\${share}`))
      const first = pairs[0]
      commands.push(`net use * \\\\${first.ip}\\${share} /user:${user}`)
      commands.push(`smbclient //${first.ip}/${share} -U ${user}`)
      return commands
    },
    smbEndpoint () {
      return this.smbClientPathForShare()
    },
    smbDomainStatus () {
      return this.storageService.domains.find(domain => {
        const protocol = String(domain.protocol || domain.service || domain.type || 'SMB').toUpperCase()
        return protocol === 'SMB'
      }) || {}
    },
    normalizedSmbDomainState () {
      return String(this.smbDomainState || '').trim().toUpperCase()
    },
    isSmbAdConfigured () {
      const domainName = String(this.smbDomainName || '').trim()
      const mode = String(this.smbIdentityMode || '').trim()
      return !!this.smbDomainStatus.id ||
        this.storageService.domains.length > 0 ||
        (!!domainName && domainName !== '-') ||
        mode === this.$t('label.storage.service.smb.identity.ad')
    },
    isSmbAdJoined () {
      return ['JOINED', 'OK', 'READY'].includes(this.normalizedSmbDomainState)
    },
    currentSmbDomainConfirmation () {
      const domainName = String(this.smbDomainName || '').trim()
      return domainName && domainName !== '-' ? domainName : 'LEAVE'
    },
    smbRuntime () {
      const inventory = this.parsedInventory || {}
      const health = this.parsedHealth || {}
      const identity = health.identity || {}
      return inventory.smbDomain || identity.smbDomain || inventory.smb || health.smb || inventory.samba || health.samba || {}
    },
    smbIdentityMode () {
      const mode = this.smbDomainStatus.identitymode || this.smbDomainStatus.identityMode || this.smbDomainStatus.mode || this.smbRuntime.identityMode || this.smbRuntime.identitymode || this.smbRuntime.identityProvider || this.smbRuntime.identityprovider
      if (['AD', 'ACTIVE_DIRECTORY', 'ACTIVE DIRECTORY'].includes(String(mode || '').toUpperCase())) {
        return this.$t('label.storage.service.smb.identity.ad')
      }
      if (mode) {
        return this.$t('label.storage.service.smb.identity.local')
      }
      return this.storageService.domains.length > 0 ? this.$t('label.storage.service.smb.identity.ad') : this.$t('label.storage.service.smb.identity.local')
    },
    smbDomainName () {
      return this.smbDomainStatus.domainname || this.smbDomainStatus.domainName || this.smbRuntime.domain || this.smbRuntime.domainName || '-'
    },
    smbWorkgroup () {
      return this.smbDomainStatus.workgroup || this.smbRuntime.workgroup || '-'
    },
    smbDomainError () {
      const inventory = this.parsedInventory || {}
      const health = this.parsedHealth || {}
      const identity = health.identity || {}
      return inventory.smbDomainError || identity.smbDomainError || this.smbRuntime.smbDomainError || {}
    },
    smbDomainErrorSummary () {
      const error = this.smbDomainError || {}
      const message = error.message || error.details || ''
      const phase = error.phase || ''
      if (!message && !phase) {
        return ''
      }
      return [phase, message].filter(Boolean).join(': ')
    },
    smbDomainState () {
      const error = this.smbDomainError || {}
      return this.firstDefined(error.state, this.smbDomainStatus.joinstate, this.smbDomainStatus.joinState, this.smbDomainStatus.state, this.smbDomainStatus.status, this.smbRuntime.joinstate, this.smbRuntime.joinState, this.smbRuntime.state, this.smbRuntime.status, this.smbRuntime.domainState, '-')
    },
    smbDomainHealthState () {
      return this.firstDefined(this.smbDomainStatus.healthstate, this.smbDomainStatus.healthState, this.smbRuntime.healthstate, this.smbRuntime.healthState, this.smbRuntime.health, this.smbRuntime.status, '-')
    },
    smbTrustVerifiedLabel () {
      const value = this.firstDefined(this.smbDomainStatus.trustverified, this.smbDomainStatus.trustVerified, this.smbRuntime.trustverified, this.smbRuntime.trustVerified)
      if (value === undefined) return '-'
      return this.boolValue(value) ? this.$t('label.storage.service.domain.trust.verified') : this.$t('label.storage.service.domain.trust.unverified')
    },
    smbDnsServers () {
      return this.firstDefined(this.smbDomainStatus.dnsservers, this.smbDomainStatus.dnsServers, this.smbRuntime.dnsservers, this.smbRuntime.dnsServers, '-')
    },
    smbRealm () {
      return this.firstDefined(this.smbDomainStatus.realm, this.smbRuntime.realm, '-')
    },
    smbNetbiosName () {
      return this.firstDefined(this.smbDomainStatus.netbiosname, this.smbDomainStatus.netbiosName, this.smbRuntime.netbiosname, this.smbRuntime.netbiosName, '-')
    },
    smbOrganizationalUnit () {
      return this.firstDefined(this.smbDomainStatus.organizationalunit, this.smbDomainStatus.organizationalUnit, this.smbRuntime.organizationalunit, this.smbRuntime.organizationalUnit, '-')
    },
    smbDaemonState () {
      const services = this.smbRuntime.services || this.smbRuntime.daemons || this.parsedHealth.services || {}
      if (Array.isArray(services)) {
        return services.filter(service => /smb|nmb|winbind/i.test(service.name || service.service || '')).map(service => `${service.name || service.service}:${service.state || service.status || '-'}`).join(', ') || '-'
      }
      const values = ['smbd', 'nmbd', 'winbind'].map(name => services[name] ? `${name}:${services[name].state || services[name].status || services[name]}` : '').filter(Boolean)
      return values.join(', ') || this.smbRuntime.daemonState || '-'
    },
    smbSetupIncomplete () {
      return this.isProtocolEnabled('SMB') && this.storageService.smbShares.length === 0
    },
    iscsiConnectionCommands () {
      const targetObject = this.storageService.iscsiTargets[0] || {}
      const config = this.parseStorageConfig(targetObject.config)
      const target = targetObject.targetname || targetObject.targetName || '<target-iqn>'
      const ports = this.normalizeListenerPorts(
        targetObject.listenerports || targetObject.listenerPorts || config.listenerGroupPorts || config.listenerports || 3260
      )
      const endpoints = this.formatIscsiListenerGroupEndpoints(ports)
        .split(',')
        .map(item => item.trim())
        .filter(item => item && item !== '-')
      const effectiveEndpoints = endpoints.length ? endpoints : [`${this.serviceEndpoint || '<service-ip>'}:3260`]
      const commands = []
      effectiveEndpoints.forEach(endpoint => {
        commands.push(`iscsiadm -m discovery -t sendtargets -p ${endpoint}`)
        commands.push(`iscsiadm -m node -T ${target} -p ${endpoint} --login`)
      })
      return commands
    },
    iscsiEndpointSummary () {
      const listenerEndpoints = this.protocolEndpointValues(this.iscsiListenerRows)
      if (listenerEndpoints.length) {
        return listenerEndpoints.join(', ')
      }
      const targetObject = this.storageService.iscsiTargets[0] || {}
      const config = this.parseStorageConfig(targetObject.config)
      const ports = this.normalizeListenerPorts(
        targetObject.listenerports || targetObject.listenerPorts || config.listenerGroupPorts || config.listenerports
      )
      const targetEndpoints = this.formatIscsiListenerGroupEndpoints(ports)
      return targetEndpoints && targetEndpoints !== '-'
        ? targetEndpoints
        : `${this.serviceEndpoint || '<service-ip>'}:${this.defaultProtocolPort('ISCSI')}`
    },
    nvmeConnectionCommands () {
      const endpointPair = this.primaryNvmeEndpoint || `${this.serviceEndpoint || '<service-ip>'}:4420`
      const parts = endpointPair.split(':')
      const port = parts.pop() || 4420
      const endpoint = parts.join(':') || this.serviceEndpoint || '<service-ip>'
      const nqn = this.storageService.nvmeSubsystems[0]?.targetname || '<subsystem-nqn>'
      const commands = [`nvme discover -t tcp -a ${endpoint} -s ${port}`, `nvme connect -t tcp -a ${endpoint} -s ${port} -n ${nqn}`]
      if (this.nvmeAclRows.some(row => row.authRequired)) {
        commands.push(this.$t('message.storage.service.nvme.auth.command.hint'))
      }
      return commands
    },
    monitorCacheInfo () {
      const health = this.parsedHealth || {}
      const cache = health.cache || health.monitorCache || health.monitor || {}
      const generatedAt = cache.generatedAt || health.generatedAt || health.timestamp || this.storageService.health[0]?.created || ''
      const status = cache.status || cache.state || health.monitorStatus || health.status || '-'
      const stale = cache.stale === true || cache.isStale === true || health.stale === true
      return {
        generatedAt,
        status,
        stale
      }
    },
    monitorCacheLabel () {
      if (!this.storageService.health.length) {
        return this.$t('label.storage.service.cache.not.loaded')
      }
      if (this.monitorCacheInfo.stale) {
        return this.$t('label.storage.service.cache.stale')
      }
      return this.monitorCacheInfo.status || this.$t('label.storage.service.cache.fresh')
    },
    monitorCacheColor () {
      if (!this.storageService.health.length) return 'default'
      return this.monitorCacheInfo.stale ? 'orange' : 'green'
    },
    monitorCacheTimestamp () {
      return this.monitorCacheInfo.generatedAt || '-'
    },
    nfsExportColumns () {
      return [
        { title: this.$t('label.storage.service.export.name'), dataIndex: 'name', key: 'name', fixed: 'left', width: 180, code: true },
        { title: this.$t('label.storage.service.client.mount.root'), dataIndex: 'clientPath', key: 'clientPath', width: 240, code: true },
        { title: this.$t('label.storage.service.internal.path'), dataIndex: 'path', key: 'path', width: 220, code: true },
        { title: this.$t('label.storage.service.nfs.protocol.mode'), dataIndex: 'protocolMode', key: 'protocolMode', width: 150 },
        { title: this.$t('label.storage.service.ip.port'), dataIndex: 'endpoint', key: 'endpoint', width: 260, code: true },
        { title: this.$t('label.storage.service.permission'), dataIndex: 'permission', key: 'permission', width: 130 },
        { title: this.$t('label.storage.service.root.squash'), dataIndex: 'rootSquash', key: 'rootSquash', width: 130 },
        { title: this.$t('label.storage.service.posix.permission'), dataIndex: 'posixPermission', key: 'posixPermission', width: 220, code: true },
        { title: this.$t('label.storage.service.access.rules'), dataIndex: 'aclSummary', key: 'aclSummary', width: 220 },
        { title: this.$t('label.storage.service.capacity'), dataIndex: 'capacity', key: 'capacity', width: 150 },
        { title: this.$t('label.storage.service.backing.volume'), dataIndex: 'volumeName', key: 'volumeName', width: 210 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 110 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 310, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nfsAclColumns () {
      return [
        { title: this.$t('label.storage.service.export.name'), dataIndex: 'exportName', key: 'exportName', fixed: 'left', width: 180, code: true },
        { title: this.$t('label.storage.service.principal'), dataIndex: 'principal', key: 'principal', width: 220, code: true },
        { title: this.$t('label.storage.service.permission'), dataIndex: 'permission', key: 'permission', width: 140 },
        { title: this.$t('label.storage.service.root.squash'), dataIndex: 'rootSquash', key: 'rootSquash', width: 130 },
        { title: this.$t('label.storage.service.all.squash'), dataIndex: 'allSquash', key: 'allSquash', width: 130 },
        { title: this.$t('label.storage.service.anon.uid.gid'), dataIndex: 'anonUidGid', key: 'anonUidGid', width: 150, code: true },
        { title: this.$t('label.storage.service.sync'), dataIndex: 'sync', key: 'sync', width: 110 },
        { title: this.$t('label.storage.service.secure'), dataIndex: 'secure', key: 'secure', width: 130 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 180, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nfsVolumeColumns () {
      return [
        { title: this.$t('label.volumename'), dataIndex: 'name', key: 'name', fixed: 'left', width: 200 },
        { title: this.$t('label.volumeid'), dataIndex: 'id', key: 'id', width: 260, code: true },
        { title: this.$t('label.size'), dataIndex: 'size', key: 'size', width: 130 },
        { title: this.$t('label.storage.service.used.capacity'), dataIndex: 'used', key: 'used', width: 140 },
        { title: this.$t('label.diskoffering'), dataIndex: 'diskOffering', key: 'diskOffering', width: 220 },
        { title: this.$t('label.storagepool'), dataIndex: 'storagePool', key: 'storagePool', width: 220 },
        { title: this.$t('label.filesystem'), dataIndex: 'filesystem', key: 'filesystem', width: 130 },
        { title: this.$t('label.storage.service.current.guest.device'), dataIndex: 'runtimeDevicePath', key: 'runtimeDevicePath', width: 180, code: true },
        { title: this.$t('label.storage.service.volume.mapping.status'), dataIndex: 'mappingStatus', key: 'mappingStatus', width: 160 },
        { title: this.$t('label.storage.service.attached.export'), dataIndex: 'exportName', key: 'exportName', width: 200, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 290, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nfsSessionColumns () {
      return [
        { title: this.$t('label.storage.service.peer'), dataIndex: 'peer', key: 'peer', fixed: 'left', width: 200, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 130 },
        { title: this.$t('label.storage.service.connected.at'), dataIndex: 'connectedAt', key: 'connectedAt', width: 190 },
        { title: this.$t('label.storage.service.export.name'), dataIndex: 'resourceName', key: 'resourceName', width: 200, code: true },
        { title: this.$t('label.storage.service.local'), dataIndex: 'local', key: 'local', width: 220, code: true },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    smbShareColumns () {
      return [
        { title: this.$t('label.storage.service.smb.share.name'), dataIndex: 'name', key: 'name', fixed: 'left', width: 190, code: true },
        { title: this.$t('label.storage.service.client.unc.root'), dataIndex: 'clientPath', key: 'clientPath', width: 340, code: true },
        { title: this.$t('label.storage.service.internal.path'), dataIndex: 'path', key: 'path', width: 220, code: true },
        { title: this.$t('label.storage.service.ip.port'), dataIndex: 'endpoint', key: 'endpoint', width: 260, code: true },
        { title: this.$t('label.storage.service.browseable'), dataIndex: 'browseable', key: 'browseable', width: 120 },
        { title: this.$t('label.storage.service.guest.access'), dataIndex: 'guestOk', key: 'guestOk', width: 130 },
        { title: this.$t('label.storage.service.permission'), dataIndex: 'permission', key: 'permission', width: 140 },
        { title: this.$t('label.storage.service.capacity'), dataIndex: 'capacity', key: 'capacity', width: 150 },
        { title: this.$t('label.storage.service.backing.volume'), dataIndex: 'volumeName', key: 'volumeName', width: 210 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 110 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 260, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    smbAclColumns () {
      return [
        { title: this.$t('label.storage.service.smb.share.name'), dataIndex: 'shareName', key: 'shareName', fixed: 'left', width: 190, code: true },
        { title: this.$t('label.storage.service.principal.type'), dataIndex: 'principalType', key: 'principalType', width: 170 },
        { title: this.$t('label.storage.service.principal'), dataIndex: 'principal', key: 'principal', width: 220, code: true },
        { title: this.$t('label.storage.service.permission'), dataIndex: 'permission', key: 'permission', width: 140 },
        { title: this.$t('label.storage.service.smb.identity.mode'), dataIndex: 'identityMode', key: 'identityMode', width: 190 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 160, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    smbVolumeColumns () {
      return [
        { title: this.$t('label.volumename'), dataIndex: 'name', key: 'name', fixed: 'left', width: 200 },
        { title: this.$t('label.volumeid'), dataIndex: 'id', key: 'id', width: 260, code: true },
        { title: this.$t('label.size'), dataIndex: 'size', key: 'size', width: 130 },
        { title: this.$t('label.storage.service.used.capacity'), dataIndex: 'used', key: 'used', width: 140 },
        { title: this.$t('label.diskoffering'), dataIndex: 'diskOffering', key: 'diskOffering', width: 220 },
        { title: this.$t('label.storagepool'), dataIndex: 'storagePool', key: 'storagePool', width: 220 },
        { title: this.$t('label.filesystem'), dataIndex: 'filesystem', key: 'filesystem', width: 130 },
        { title: this.$t('label.storage.service.current.guest.device'), dataIndex: 'runtimeDevicePath', key: 'runtimeDevicePath', width: 180, code: true },
        { title: this.$t('label.storage.service.volume.mapping.status'), dataIndex: 'mappingStatus', key: 'mappingStatus', width: 160 },
        { title: this.$t('label.storage.service.attached.share'), dataIndex: 'shareName', key: 'shareName', width: 200, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    smbSessionColumns () {
      return [
        { title: this.$t('label.storage.service.peer'), dataIndex: 'peer', key: 'peer', fixed: 'left', width: 200, code: true },
        { title: this.$t('label.username'), dataIndex: 'user', key: 'user', width: 180, code: true },
        { title: this.$t('label.storage.service.smb.share.name'), dataIndex: 'resourceName', key: 'resourceName', width: 200, code: true },
        { title: this.$t('label.storage.service.smb.dialect'), dataIndex: 'dialect', key: 'dialect', width: 150 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 130 },
        { title: this.$t('label.storage.service.connected.at'), dataIndex: 'connectedAt', key: 'connectedAt', width: 190 },
        { title: this.$t('label.storage.service.local'), dataIndex: 'local', key: 'local', width: 220, code: true },
        { title: this.$t('label.storage.service.tree.session.id'), dataIndex: 'sessionId', key: 'sessionId', width: 190, code: true },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nfsExportRows () {
      return this.storageService.nfsExports.map((share, index) => {
        const name = this.clientVisibleName(share.name || share.exportname, `nfs${index + 1}`)
        const acls = this.nfsAclsForShare(share)
        const config = this.effectiveNfsExportConfig(this.parseStorageConfig(share.config))
        const volume = this.volumeForShare(share)
        const protocolMode = this.nfsExportProtocolMode(share, config)
        return {
          key: share.id || `nfs-export-${index}`,
          id: share.id,
          name,
          clientPath: this.formatNfsClientMountRoots(share, name),
          path: share.path || share.mountpath || share.backingpath || '-',
          endpoint: this.formatNfsExportEndpoints(share),
          protocolMode: this.nfsProtocolModeLabel(protocolMode),
          permission: this.permissionLabel(share.permission || (share.readonly || config.readOnly ? 'READ_ONLY' : 'READ_WRITE')),
          rootSquash: this.booleanLabel(config.rootSquash),
          posixPermission: this.posixPermissionSummary(config),
          aclSummary: this.aclSummary(acls),
          capacity: this.formatCapacityValue(share.quotabytes || share.quotaBytes || share.capacitybytes || share.sizebytes),
          volumeName: volume.name || volume.displayname || share.volumename || share.volumeName || share.volumeid || '-',
          state: share.state || share.status || '-',
          raw: share
        }
      })
    },
    nfsFailedExportMessages () {
      return this.storageService.nfsExports
        .filter(share => String(share.state || '').toLowerCase() === 'error' || share.configvalid === false || share.configValid === false)
        .map(share => {
          const config = this.parseStorageConfig(share.config)
          const error = config.lastError || config.lasterror || {}
          const name = this.clientVisibleName(share.name || share.exportname, share.id || '-')
          const reason = error.message || share.configerror || share.configError || this.$t('message.storage.service.nfs.export.error.unknown')
          return `${name}: ${reason}`
        })
    },
    nfsAclRows () {
      const rows = this.storageService.nfsAcls.map((acl, index) => {
        const share = this.nfsShareForAcl(acl)
        const config = this.parseStorageConfig(acl.config)
        return {
          key: acl.id || `nfs-acl-${index}`,
          exportName: this.clientVisibleName(share?.name || acl.exportname || acl.resourcename, '-'),
          principal: acl.principal || acl.cidr || acl.client || '-',
          permission: this.permissionLabel(acl.permission || acl.access || '-'),
          rootSquash: this.booleanLabel(acl.rootsquash ?? acl.rootSquash ?? config.rootSquash),
          allSquash: this.booleanLabel(acl.allsquash ?? acl.allSquash ?? config.allSquash),
          anonUidGid: this.uidGidSummary(config.anonUid ?? config.anonuid, config.anonGid ?? config.anongid),
          sync: this.booleanLabel(acl.sync ?? config.sync),
          secure: this.booleanLabel(acl.secure ?? config.secure),
          state: acl.state || acl.status || '-',
          raw: acl
        }
      })
      this.storageService.nfsExports.forEach((share, index) => {
        if (this.nfsAclsForShare(share).length > 0) {
          return
        }
        const config = this.effectiveNfsExportConfig(this.parseStorageConfig(share.config))
        rows.push({
          key: `nfs-acl-implicit-${share.id || share.uuid || index}`,
          exportName: this.clientVisibleName(share.name || share.exportname, '-'),
          principal: '*',
          permission: this.permissionLabel(share.permission || (share.readonly || config.readOnly ? 'READ_ONLY' : 'READ_WRITE')),
          rootSquash: this.booleanLabel(config.rootSquash),
          allSquash: this.booleanLabel(config.allSquash),
          anonUidGid: this.uidGidSummary(config.anonUid ?? config.anonuid, config.anonGid ?? config.anongid),
          sync: this.booleanLabel(config.sync),
          secure: this.booleanLabel(config.secure),
          state: this.$t('label.storage.service.implicit.default'),
          implicit: true,
          raw: {}
        })
      })
      return rows
    },
    nfsVolumeRows () {
      const rows = []
      const seen = new Set()
      this.nfsBackingVolumes.forEach((volume, index) => {
        const id = volume.id || `backing-volume-${index}`
        if (seen.has(id)) return
        seen.add(id)
        const shares = this.nfsExportsForVolume(volume)
        const mountPath = this.currentBackingVolumeMountPath(volume)
        const resourceName = shares.length ? shares.map(share => this.clientVisibleName(share.name || share.exportname, '-')).join(', ') : '-'
        const runtimeShare = shares.find(share => share.runtimedevicepath || share.runtimeDevicePath || share.mappingstatus || share.mappingStatus) || shares[0] || {}
        const resizeContext = this.backingVolumeActionFields(volume, resourceName, volume.size)
        rows.push({
          key: id,
          id,
          ...resizeContext,
          exportId: shares[0]?.id,
          name: volume.name || volume.displayname || '-',
          size: this.formatCapacityValue(volume.size),
          used: this.formatCapacityValue(volume.usedfsbytes || volume.usedphysicalsize || volume.physicalsize),
          diskOffering: volume.diskofferingname || '-',
          storagePool: volume.storage || volume.storagepool || volume.storagePoolName || '-',
          filesystem: this.nfsBackingVolumeFilesystem(volume),
          runtimeDevicePath: runtimeShare.runtimedevicepath || runtimeShare.runtimeDevicePath || '-',
          mappingStatus: this.fileShareVolumeMappingStatusLabel(runtimeShare.mappingstatus || runtimeShare.mappingStatus),
          exportName: shares.length ? shares.map(share => this.clientVisibleName(share.name || share.exportname, '-')).join(', ') : '-',
          mountPath,
          detachAllowed: shares.length === 0 && String(volume.state || '').toLowerCase() !== 'destroyed',
          state: volume.state || '-',
          raw: volume
        })
      })
      return rows
    },
    nfsSessionRows () {
      return this.protocolSessions('NFS').map((session, index) => ({
        key: session.sessionId || session.id || `${session.peer || 'session'}-${index}`,
        protocol: session.protocol || 'NFS',
        peer: this.formatSessionEndpoint(session.peer || session.client || session.clientIp || '-'),
        state: session.state || session.status || '-',
        connectedAt: session.connectedAt || session.since || session.age || '-',
        resourceName: this.nfsSessionResourceName(session),
        local: this.formatSessionEndpoint(session.local || session.endpoint || '-'),
        sessionId: session.sessionId || session.id || '',
        raw: session
      }))
    },
    smbShareRows () {
      return this.storageService.smbShares.map((share, index) => {
        const name = this.clientVisibleName(share.name || share.sharename, `smb${index + 1}`)
        const config = this.parseStorageConfig(share.config)
        const volume = this.volumeForShare(share)
        return {
          key: share.id || `smb-share-${index}`,
          id: share.id,
          name,
          clientPath: this.smbClientPathForShare(name),
          path: share.path || share.mountpath || share.backingpath || '-',
          endpoint: this.smbEndpointPairSummary || `${share.listenip || this.serviceEndpoint || '-'}:${share.port || 445}`,
          browseable: this.booleanLabel(share.browseable ?? config.browseable),
          guestOk: this.booleanLabel(share.guestok ?? share.guestOk ?? config.guestOk),
          permission: this.permissionLabel(share.permission || (share.readonly || config.readOnly ? 'READ_ONLY' : 'READ_WRITE')),
          capacity: this.formatCapacityValue(share.quotabytes || share.quotaBytes || share.capacitybytes || share.sizebytes),
          volumeName: volume.name || volume.displayname || share.volumename || share.volumeName || share.volumeid || '-',
          state: share.state || share.status || '-',
          raw: share
        }
      })
    },
    smbAclRows () {
      return this.storageService.smbAcls.map((acl, index) => {
        const share = this.smbShareForAcl(acl)
        const principalType = acl.principaltype || acl.principalType || '-'
        return {
          key: acl.id || `smb-acl-${index}`,
          shareName: this.clientVisibleName(share?.name || acl.sharename || acl.resourcename, '-'),
          principalType: this.principalTypeLabel(principalType),
          principal: acl.principal || acl.username || acl.account || '-',
          permission: this.permissionLabel(acl.permission || acl.access || '-'),
          identityMode: String(principalType).startsWith('AD_') ? this.$t('label.storage.service.smb.identity.ad') : this.$t('label.storage.service.smb.identity.local'),
          state: acl.state || acl.status || '-',
          raw: acl
        }
      })
    },
    smbVolumeRows () {
      const rows = []
      const seen = new Set()
      this.storageService.smbShares.forEach((share, index) => {
        const volume = this.volumeForShare(share)
        const id = volume.id || volume.uuid || share.volumeid || share.volumeId || `smb-share-${index}`
        if (seen.has(id)) return
        seen.add(id)
        const shareName = this.clientVisibleName(share.name || share.sharename, '-')
        const resizeContext = this.backingVolumeActionFields(volume, shareName, share.volumesize || share.volumeSize)
        rows.push({
          key: id,
          id,
          ...resizeContext,
          shareId: share.id,
          name: volume.name || volume.displayname || share.volumename || share.volumeName || share.volumeid || '-',
          size: this.formatCapacityValue(share.volumesize || share.volumeSize || volume.size),
          used: this.formatCapacityValue(share.usedbytes || share.usedBytes || volume.usedfsbytes || volume.usedphysicalsize || volume.physicalsize || share.physicalsize),
          diskOffering: share.diskofferingname || share.diskOfferingName || volume.diskofferingname || '-',
          storagePool: share.storage || share.storagepool || share.storagePoolName || volume.storage || '-',
          filesystem: this.displayBackingVolumeFilesystem(volume, share),
          runtimeDevicePath: share.runtimedevicepath || share.runtimeDevicePath || '-',
          mappingStatus: this.fileShareVolumeMappingStatusLabel(share.mappingstatus || share.mappingStatus),
          shareName,
          state: share.volumestate || share.volumeState || volume.state || '-',
          raw: share
        })
      })
      return rows
    },
    smbSessionRows () {
      return this.protocolSessions('SMB').map((session, index) => ({
        key: session.sessionId || session.id || `${session.peer || 'session'}-${index}`,
        protocol: session.protocol || 'SMB',
        peer: session.peer || session.client || session.clientIp || '-',
        user: session.user || session.username || session.userName || '-',
        group: session.group || session.groupname || session.groupName || '-',
        resourceName: session.resourceName || session.shareName || session.service || session.share || '-',
        dialect: session.dialect || session.sessionDialect || session.smbVersion || session.version || session.protocolVersion || '-',
        state: session.state || session.status || '-',
        connectedAt: session.connectedAt || session.since || session.age || '-',
        local: session.local || session.endpoint || '-',
        sessionId: session.treeId || session.sambaSessionId || session.sessionId || session.id || '',
        raw: session
      }))
    },
    iscsiTargetColumns () {
      return [
        { title: this.$t('label.storage.service.target.iqn'), dataIndex: 'targetName', key: 'targetName', fixed: 'left', width: 330, code: true },
        { title: this.$t('label.storage.service.lun'), dataIndex: 'lun', key: 'lun', width: 90, code: true },
        { title: this.$t('label.storage.service.target.luns'), dataIndex: 'targetLuns', key: 'targetLuns', width: 130, code: true },
        { title: this.$t('label.storage.service.endpoint'), dataIndex: 'endpoint', key: 'endpoint', width: 180, code: true },
        { title: this.$t('label.storage.service.backing.volume'), dataIndex: 'volumeName', key: 'volumeName', width: 220 },
        { title: this.$t('label.storage.service.lun.size'), dataIndex: 'lunSize', key: 'lunSize', width: 150 },
        { title: this.$t('label.storage.service.effective.lun.size'), dataIndex: 'effectiveSize', key: 'effectiveSize', width: 160 },
        { title: this.$t('label.storage.service.runtime.backing.path'), dataIndex: 'backingPath', key: 'backingPath', width: 300, code: true },
        { title: this.$t('label.storage.service.volume.mapping.status'), dataIndex: 'mappingStatus', key: 'mappingStatus', width: 150 },
        { title: this.$t('label.storage.service.access.rules'), dataIndex: 'aclSummary', key: 'aclSummary', width: 260 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 190, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    iscsiAclColumns () {
      return [
        { title: this.$t('label.storage.service.target.iqn'), dataIndex: 'targetName', key: 'targetName', fixed: 'left', width: 330, code: true },
        { title: this.$t('label.storage.service.allowed.initiator.iqn'), dataIndex: 'principal', key: 'principal', width: 300, code: true },
        { title: this.$t('label.storage.service.target.luns'), dataIndex: 'targetLuns', key: 'targetLuns', width: 130, code: true },
        { title: this.$t('label.storage.service.permission'), dataIndex: 'permission', key: 'permission', width: 140 },
        { title: this.$t('label.storage.service.chap.enabled'), dataIndex: 'chapEnabled', key: 'chapEnabled', width: 130 },
        { title: this.$t('label.storage.service.chap.username'), dataIndex: 'chapUsername', key: 'chapUsername', width: 190, code: true },
        { title: this.$t('label.storage.service.mutual.chap.enabled'), dataIndex: 'mutualChapEnabled', key: 'mutualChapEnabled', width: 160 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    iscsiVolumeColumns () {
      return [
        { title: this.$t('label.volumename'), dataIndex: 'name', key: 'name', fixed: 'left', width: 200 },
        { title: this.$t('label.volumeid'), dataIndex: 'id', key: 'id', width: 260, code: true },
        { title: this.$t('label.size'), dataIndex: 'size', key: 'size', width: 130 },
        { title: this.$t('label.storage.service.used.capacity'), dataIndex: 'used', key: 'used', width: 140 },
        { title: this.$t('label.diskoffering'), dataIndex: 'diskOffering', key: 'diskOffering', width: 220 },
        { title: this.$t('label.storagepool'), dataIndex: 'storagePool', key: 'storagePool', width: 220 },
        { title: this.$t('label.storage.service.iscsi.target'), dataIndex: 'targetName', key: 'targetName', width: 300, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    iscsiSessionColumns () {
      return [
        { title: this.$t('label.storage.service.peer'), dataIndex: 'peer', key: 'peer', fixed: 'left', width: 220, code: true },
        { title: this.$t('label.storage.service.initiator.iqn'), dataIndex: 'initiatorIqn', key: 'initiatorIqn', width: 300, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 130 },
        { title: this.$t('label.storage.service.session.mapping.status'), dataIndex: 'mappingStatusLabel', key: 'mappingStatusLabel', width: 150 },
        { title: this.$t('label.storage.service.session.endpoint.mapping.status'), dataIndex: 'endpointMappingStatusLabel', key: 'endpointMappingStatusLabel', width: 170 },
        { title: this.$t('label.storage.service.connected.at'), dataIndex: 'connectedAt', key: 'connectedAt', width: 190 },
        { title: this.$t('label.storage.service.target.iqn'), dataIndex: 'resourceName', key: 'resourceName', width: 300, code: true },
        { title: this.$t('label.storage.service.lun'), dataIndex: 'lun', key: 'lun', width: 130, code: true },
        { title: this.storageLabel('label.storage.service.listener.ports', '수신 포트 그룹'), dataIndex: 'listenerPorts', key: 'listenerPorts', width: 190, code: true },
        { title: this.$t('label.storage.service.local'), dataIndex: 'local', key: 'local', width: 260, code: true },
        { title: this.$t('label.storage.service.chap.configured'), dataIndex: 'chapConfiguredLabel', key: 'chapConfiguredLabel', width: 150 },
        { title: this.$t('label.storage.service.authentication.status'), dataIndex: 'authVerificationLabel', key: 'authVerification', width: 180 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    selectedNvmeNamespaceSubsystem () {
      return this.nvmeSubsystemTargets.find(target => String(target.id || '') === String(this.forms.nvmeNamespace.subsystemid || '')) || null
    },
    selectedNvmeNamespaceHostPolicy () {
      return this.nvmeSubsystemAccessPolicy(this.selectedNvmeNamespaceSubsystem)
    },
    selectedNvmeNamespaceHostPolicyType () {
      const type = this.selectedNvmeNamespaceHostPolicy.type
      if (type === 'NO_ACL') return 'warning'
      return 'info'
    },
    selectedNvmeNamespaceHostPolicyMessage () {
      if (!this.selectedNvmeNamespaceSubsystem) {
        return ''
      }
      const policy = this.selectedNvmeNamespaceHostPolicy
      if (policy.type === 'ALLOW_ANY') {
        return this.$t('message.storage.service.nvme.namespace.allow.any.host')
      }
      if (policy.type === 'EXPLICIT_ACL') {
        return this.$t('message.storage.service.nvme.namespace.explicit.host.acl', { count: policy.aclCount })
      }
      return this.$t('message.storage.service.nvme.namespace.no.host.acl')
    },
    nvmeSubsystemColumns () {
      return [
        { title: this.$t('label.storage.service.subsystem.nqn'), dataIndex: 'targetName', key: 'targetName', fixed: 'left', width: 340, code: true },
        { title: this.$t('label.storage.service.nvme.port.groups'), dataIndex: 'listenerGroupLabel', key: 'listenerGroupLabel', width: 260, code: true },
        { title: this.$t('label.storage.service.nvme.effective.endpoints'), dataIndex: 'effectiveEndpoints', key: 'effectiveEndpoints', width: 360, code: true },
        { title: this.$t('label.storage.service.engine'), dataIndex: 'engine', key: 'engine', width: 160 },
        { title: this.$t('label.storage.service.nvme.host.policy'), dataIndex: 'hostPolicyLabel', key: 'hostPolicyLabel', width: 190 },
        { title: this.$t('label.storage.service.nvme.effective.host.access'), dataIndex: 'effectiveHostAccess', key: 'effectiveHostAccess', width: 220 },
        { title: this.$t('label.storage.service.access.rules'), dataIndex: 'aclSummary', key: 'aclSummary', width: 260 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 190, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nvmeNamespaceColumns () {
      return [
        { title: this.$t('label.storage.service.subsystem.nqn'), dataIndex: 'targetName', key: 'targetName', fixed: 'left', width: 330, code: true },
        { title: this.$t('label.storage.service.namespace.id'), dataIndex: 'namespace', key: 'namespace', width: 130, code: true },
        { title: this.$t('label.storage.service.nvme.namespace.port.groups'), dataIndex: 'listenerGroupLabel', key: 'listenerGroupLabel', width: 260, code: true },
        { title: this.$t('label.storage.service.nvme.namespace.endpoints'), dataIndex: 'effectiveEndpoints', key: 'effectiveEndpoints', width: 360, code: true },
        { title: this.$t('label.storage.service.nvme.namespace.host.access'), dataIndex: 'hostPolicyLabel', key: 'hostPolicyLabel', width: 190 },
        { title: this.$t('label.storage.service.nvme.effective.host.access'), dataIndex: 'effectiveHostAccess', key: 'effectiveHostAccess', width: 220 },
        { title: this.$t('label.storage.service.backing.volume'), dataIndex: 'volumeName', key: 'volumeName', width: 220 },
        { title: this.$t('label.storage.service.namespace.size'), dataIndex: 'namespaceSize', key: 'namespaceSize', width: 160 },
        { title: this.$t('label.storage.service.effective.lun.size'), dataIndex: 'effectiveSize', key: 'effectiveSize', width: 160 },
        { title: this.$t('label.storage.service.runtime.backing.path'), dataIndex: 'backingPath', key: 'backingPath', width: 300, code: true },
        { title: this.$t('label.storage.service.session.mapping.status'), dataIndex: 'mappingStatus', key: 'mappingStatus', width: 150 },
        { title: this.$t('label.storage.service.runtime.observed.at'), dataIndex: 'runtimeObservedAt', key: 'runtimeObservedAt', width: 190 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nvmeVolumeColumns () {
      return [
        { title: this.$t('label.volumename'), dataIndex: 'name', key: 'name', fixed: 'left', width: 200 },
        { title: this.$t('label.volumeid'), dataIndex: 'id', key: 'id', width: 260, code: true },
        { title: this.$t('label.size'), dataIndex: 'size', key: 'size', width: 130 },
        { title: this.$t('label.storage.service.used.capacity'), dataIndex: 'used', key: 'used', width: 140 },
        { title: this.$t('label.diskoffering'), dataIndex: 'diskOffering', key: 'diskOffering', width: 220 },
        { title: this.$t('label.storagepool'), dataIndex: 'storagePool', key: 'storagePool', width: 220 },
        { title: this.$t('label.storage.service.namespace'), dataIndex: 'namespaceName', key: 'namespaceName', width: 300, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 270, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nvmeAclColumns () {
      return [
        { title: this.$t('label.storage.service.subsystem.nqn'), dataIndex: 'targetName', key: 'targetName', fixed: 'left', width: 340, code: true },
        { title: this.$t('label.storage.service.policy.source'), dataIndex: 'policySource', key: 'policySource', width: 160 },
        { title: this.$t('label.storage.service.allowed.host.nqn'), dataIndex: 'principal', key: 'principal', width: 320, code: true },
        { title: this.$t('label.storage.service.nvme.auth.mode'), dataIndex: 'authMode', key: 'authMode', width: 170 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 120 },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 180, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    nvmeSessionColumns () {
      return [
        { title: this.$t('label.storage.service.peer'), dataIndex: 'peer', key: 'peer', fixed: 'left', width: 220, code: true },
        { title: this.$t('label.storage.service.host.nqn'), dataIndex: 'hostNqn', key: 'hostNqn', width: 320, code: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 130 },
        { title: this.$t('label.storage.service.session.mapping.status'), dataIndex: 'mappingStatusLabel', key: 'mappingStatusLabel', width: 160 },
        { title: this.$t('label.storage.service.connected.at'), dataIndex: 'connectedAt', key: 'connectedAt', width: 190 },
        { title: this.$t('label.storage.service.subsystem.nqn'), dataIndex: 'resourceName', key: 'resourceName', width: 320, code: true },
        { title: this.$t('label.storage.service.namespace.id'), dataIndex: 'namespaceId', key: 'namespaceId', width: 140, code: true },
        { title: this.$t('label.storage.service.nvme.possible.subsystems'), dataIndex: 'possibleSubsystems', key: 'possibleSubsystems', width: 360, code: true },
        { title: this.$t('label.storage.service.nvme.possible.namespaces'), dataIndex: 'possibleNamespaces', key: 'possibleNamespaces', width: 320, code: true },
        { title: this.$t('label.storage.service.nvme.host.policy'), dataIndex: 'hostPolicyLabel', key: 'hostPolicyLabel', width: 180 },
        { title: this.$t('label.storage.service.nvme.transport.queue.count'), dataIndex: 'queueCount', key: 'queueCount', width: 150 },
        { title: this.$t('label.storage.service.local'), dataIndex: 'local', key: 'local', width: 220, code: true },
        { title: this.$t('label.actions'), dataIndex: 'actions', key: 'actions', fixed: 'right', width: 170, align: 'right', className: 'storage-table-actions-column' }
      ]
    },
    iscsiTargetRows () {
      return this.storageService.iscsiTargets.map((target, index) => {
        const config = this.parseStorageConfig(target.config)
        const acls = this.blockAclsForTarget(target, this.storageService.iscsiAcls)
        const volume = this.volumeForTarget(target)
        const runtime = this.runtimeBlockTarget(target, 'iscsiTargets')
        const runtimeConfig = this.parseStorageConfig(runtime.config)
        const lunSizeBytes = this.firstDefined(target.lunsizebytes, target.lunSizeBytes, config.lunSizeBytes, runtime.lunSizeBytes, runtimeConfig.lunSizeBytes)
        const effectiveSizeBytes = this.firstDefined(target.actualbackingsizebytes, target.actualBackingSizeBytes, runtime.actualBackingSizeBytes, target.effectivesizebytes, target.effectiveSizeBytes, runtime.effectiveSizeBytes, runtime.actualSizeBytes, lunSizeBytes, target.volumesizebytes, target.volumeSizeBytes, volume.size, this.volume.size, this.resource.size)
        const mappingStatus = String(this.firstDefined(target.runtimemappingstatus, target.runtimeMappingStatus, runtime.runtimeMappingStatus, 'UNMAPPED')).toUpperCase()
        const runtimeBackingPath = this.firstDefined(target.runtimebackingpath, target.runtimeBackingPath, runtime.runtimeBackingPath, runtime.backingPath, runtime.backingpath)
        return {
          key: target.id || `iscsi-target-${index}`,
          id: target.id,
          targetName: target.targetname || target.targetName || target.name || '-',
          lun: target.lunornamespace || target.lunOrNamespace || target.lun || '0',
          targetLuns: target.targetluns || target.targetLuns || runtime.targetLuns || runtime.targetluns || target.lunornamespace || target.lunOrNamespace || target.lun || '0',
          endpoint: target.endpoints || target.endpoint || this.formatIscsiListenerGroupEndpoints(this.normalizeListenerPorts(target.listenerports || target.listenerPorts || config.listenerGroupPorts || config.listenerports || 3260)),
          volumeName: volume.name || volume.displayname || target.volumename || target.volumeName || target.volumeid || '-',
          lunSize: lunSizeBytes ? this.formatCapacityValue(lunSizeBytes) : this.$t('label.storage.service.entire.backing.volume'),
          effectiveSize: this.formatCapacityValue(effectiveSizeBytes),
          backingPath: mappingStatus === 'EXACT' ? (runtimeBackingPath || '-') : '-',
          mappingStatus: this.fileShareVolumeMappingStatusLabel(mappingStatus),
          aclSummary: this.aclSummary(acls),
          state: target.runtimestate || target.runtimeState || runtime.runtimeState || target.state || target.status || '-',
          raw: target
        }
      })
    },
    iscsiAclRows () {
      return this.storageService.iscsiAcls.map((acl, index) => {
        const target = this.blockTargetForAcl(acl, this.storageService.iscsiTargets)
        const config = this.parseStorageConfig(acl.config)
        return {
          key: acl.id || `iscsi-acl-${index}`,
          targetName: target?.targetname || target?.targetName || acl.targetname || acl.resourcename || '-',
          principal: acl.principal || acl.initiatoriqn || '-',
          targetLuns: acl.targetluns || acl.targetLuns || target?.targetluns || target?.targetLuns || target?.lunornamespace || target?.lunOrNamespace || '-',
          permission: this.permissionLabel(acl.permission || acl.access || '-'),
          chapEnabled: this.booleanLabel(acl.chapenabled ?? acl.chapEnabled ?? config.chapEnabled),
          chapUsername: acl.chapusername || acl.chapUsername || config.chapUsername || '-',
          mutualChapEnabled: this.booleanLabel(acl.mutualchapenabled ?? acl.mutualChapEnabled ?? config.mutualChapEnabled),
          state: acl.state || acl.status || '-',
          raw: acl
        }
      })
    },
    iscsiVolumeRows () {
      const rows = []
      const seen = new Set()
      this.storageService.iscsiTargets.forEach((target, index) => {
        const volume = this.volumeForTarget(target)
        const id = volume.id || volume.uuid || target.volumeid || target.volumeId || `iscsi-target-${index}`
        if (seen.has(id)) return
        seen.add(id)
        const targetName = target.targetname || target.targetName || '-'
        const resizeContext = this.backingVolumeActionFields(volume, targetName, target.volumesizebytes || target.volumeSizeBytes || target.volumesize || target.volumeSize)
        rows.push({
          key: id,
          id,
          ...resizeContext,
          targetId: target.id,
          name: volume.name || volume.displayname || target.volumename || target.volumeName || target.volumeid || '-',
          size: this.formatCapacityValue(target.volumesizebytes || target.volumeSizeBytes || target.volumesize || target.volumeSize || volume.size),
          used: this.formatCapacityValue(target.usedbytes || target.usedBytes || volume.usedfsbytes || volume.usedphysicalsize || volume.physicalsize || target.physicalsize),
          diskOffering: target.diskofferingname || target.diskOfferingName || volume.diskofferingname || '-',
          storagePool: target.storage || target.storagepool || target.storagePoolName || volume.storage || '-',
          targetName,
          state: target.volumestate || target.volumeState || volume.state || '-',
          raw: target
        })
      })
      return rows
    },
    iscsiSessionRows () {
      return this.protocolSessions('ISCSI').map((session, index) => {
        const mappingStatus = session.mappingStatus || session.mappingstatus || 'exact'
        const endpointMappingStatus = session.endpointMappingStatus || session.endpointmappingstatus || (session.local || session.endpoint ? 'exact' : (session.possibleEndpoints || session.possibleendpoints ? 'candidate' : ''))
        const possibleEndpoints = session.possibleEndpoints || session.possibleendpoints
        const listenerPorts = session.listenerPorts || session.listenerports || this.possibleSessionValues(possibleEndpoints, 'port')
        const chapConfigured = session.chapConfigured ?? session.chapconfigured
        const rawVerification = String(session.authVerification || session.authverification || '').toUpperCase()
        const authVerification = rawVerification || (session.authenticated === true ? 'VERIFIED' : 'UNKNOWN')
        return {
          key: session.sessionId || session.id || `${session.peer || 'session'}-${index}`,
          protocol: session.protocol || 'ISCSI',
          peer: session.peer || session.client || session.clientIp || '-',
          initiatorIqn: session.initiatorIqn || session.initiatoriqn || session.principal || this.possibleSessionValues(session.possibleInitiators, 'principal') || '-',
          state: session.state || session.status || '-',
          mappingStatus,
          mappingStatusLabel: this.iscsiMappingStatusLabel(mappingStatus),
          endpointMappingStatus,
          endpointMappingStatusLabel: this.iscsiEndpointMappingStatusLabel(endpointMappingStatus),
          connectedAt: session.connectedAt || session.since || session.age || '-',
          resourceName: session.resourceName || session.targetIqn || session.targetiqn || session.targetName || session.target || this.possibleSessionValues(session.possibleTargets, 'targetIqn') || '-',
          lun: session.targetLuns || session.targetluns || session.lun || session.lunOrNamespace || session.lunornamespace || this.possibleSessionValues(session.possibleTargets, 'lun') || '-',
          listenerPorts: listenerPorts || '-',
          local: session.local || session.endpoint || this.possibleSessionValues(possibleEndpoints) || '-',
          chapConfigured,
          chapConfiguredLabel: this.booleanLabel(chapConfigured),
          authVerification,
          authVerificationLabel: this.iscsiAuthVerificationLabel(authVerification),
          authVerificationTooltip: authVerification === 'UNKNOWN' ? this.$t('message.storage.service.authentication.unknown.help') : '',
          sessionId: session.sessionId || session.id || '',
          raw: session
        }
      })
    },
    nvmeSubsystemRows () {
      return this.nvmeSubsystemTargets.map((target, index) => {
        const config = this.parseStorageConfig(target.config)
        const acls = this.blockAclsForTarget(target, this.storageService.nvmeHostAcls)
        const subsystemName = target.targetname || target.subsystemnqn || target.targetName
        const hostPolicy = this.nvmeSubsystemAccessPolicy(target, acls, config)
        const namespaceListenerPorts = []
        this.nvmeNamespaceTargets
          .filter(namespace => (namespace.targetname || namespace.subsystemnqn || namespace.targetName) === subsystemName)
          .forEach(namespace => {
            const namespaceConfig = this.parseStorageConfig(namespace.config)
            this.normalizeListenerPorts(namespace.listenerports || namespace.listenerPorts || namespaceConfig.listenerGroupPorts || namespaceConfig.listenerports)
              .forEach(port => namespaceListenerPorts.push(port))
          })
        const listenerPorts = this.normalizeListenerPorts(namespaceListenerPorts.length ? namespaceListenerPorts : (target.listenerports || target.listenerPorts || config.listenerGroupPorts || config.listenerports || this.defaultNvmeListenerPort()))
        const listenerSummary = this.nvmeListenerGroupSummary(listenerPorts)
        const namespaceCount = this.nvmeSubsystemNamespaceCount(subsystemName)
        const deleteDisabledReason = this.nvmeSubsystemDeleteDisabledReason(namespaceCount, hostPolicy.aclCount)
        return {
          key: target.id || `nvme-subsystem-${index}`,
          id: target.id,
          targetName: subsystemName || '-',
          listenerPorts: listenerPorts.join(', '),
          listenerGroupLabel: listenerSummary.listenerGroupLabel,
          endpoint: listenerSummary.effectiveEndpoints,
          effectiveEndpoints: listenerSummary.effectiveEndpoints,
          engine: target.engine || config.engine || '-',
          aclSummary: this.aclSummary(acls),
          hostPolicyLabel: hostPolicy.label,
          hostPolicyColor: hostPolicy.color,
          effectiveHostAccess: hostPolicy.effectiveAccess,
          hostAclCount: hostPolicy.aclCount,
          namespaceCount,
          canDelete: !deleteDisabledReason,
          deleteDisabledReason,
          state: target.runtimestate || target.runtimeState || target.state || target.status || '-',
          raw: target
        }
      })
    },
    nvmeNamespaceRows () {
      return this.nvmeNamespaceTargets.map((target, index) => {
        const config = this.parseStorageConfig(target.config)
        const volume = this.volumeForTarget(target)
        const runtime = this.runtimeBlockTarget(target, 'nvmeTargets')
        const runtimeConfig = this.parseStorageConfig(runtime.config)
        const nsSizeBytes = this.firstDefined(target.namespacesizebytes, target.namespaceSizeBytes, config.namespaceSizeBytes, runtime.namespaceSizeBytes, runtimeConfig.namespaceSizeBytes)
        const mappingStatus = String(this.firstDefined(target.runtimemappingstatus, target.runtimeMappingStatus, runtime.runtimeMappingStatus, 'UNMAPPED')).toUpperCase()
        const runtimeBackingPath = this.firstDefined(target.runtimebackingpath, target.runtimeBackingPath, runtime.runtimeBackingPath, runtime.backingPath, runtime.backingpath)
        const actualBackingSizeBytes = this.firstDefined(target.actualbackingsizebytes, target.actualBackingSizeBytes, runtime.actualBackingSizeBytes, runtime.actualSizeBytes)
        const effectiveSizeBytes = this.firstDefined(actualBackingSizeBytes, target.effectivesizebytes, target.effectiveSizeBytes, runtime.effectiveSizeBytes, nsSizeBytes, target.volumesizebytes, target.volumeSizeBytes, volume.size, this.volume.size, this.resource.size)
        const listenerPorts = this.normalizeListenerPorts(target.listenerports || target.listenerPorts || config.listenerGroupPorts || config.listenerports || this.defaultNvmeListenerPort())
        const listenerSummary = this.nvmeListenerGroupSummary(listenerPorts)
        const hostPolicy = this.nvmeNamespaceAccessPolicy(target)
        return {
          key: target.id || `nvme-namespace-${index}`,
          id: target.id,
          targetName: target.targetname || target.subsystemnqn || target.targetName || '-',
          namespace: target.lunornamespace || target.namespaceid || target.namespaceId || '1',
          listenerPorts: listenerPorts.join(', '),
          listenerGroupLabel: listenerSummary.listenerGroupLabel,
          endpoint: listenerSummary.effectiveEndpoints,
          effectiveEndpoints: listenerSummary.effectiveEndpoints,
          volumeName: volume.name || volume.displayname || target.volumename || target.volumeName || target.volumeid || '-',
          namespaceSize: nsSizeBytes ? this.formatCapacityValue(nsSizeBytes) : this.$t('label.storage.service.entire.backing.volume'),
          effectiveSize: this.formatCapacityValue(effectiveSizeBytes),
          backingPath: mappingStatus === 'EXACT' ? (runtimeBackingPath || '-') : '-',
          mappingStatus: this.nvmeRuntimeMappingStatusLabel(mappingStatus),
          runtimeObservedAt: target.runtimeobservedat || target.runtimeObservedAt || runtime.runtimeObservedAt || '-',
          hostPolicyLabel: hostPolicy.label,
          hostPolicyColor: hostPolicy.color,
          effectiveHostAccess: hostPolicy.effectiveAccess,
          state: target.runtimestate || target.runtimeState || runtime.runtimeState || runtime.state || target.state || target.status || '-',
          raw: target
        }
      })
    },
    nvmeVolumeRows () {
      const rows = []
      const seen = new Set()
      this.nvmeNamespaceTargets.forEach((target, index) => {
        const volume = this.volumeForTarget(target)
        const id = volume.id || volume.uuid || target.volumeid || target.volumeId || `nvme-namespace-${index}`
        if (seen.has(id)) return
        seen.add(id)
        const namespaceName = `${target.targetname || target.targetName || '-'} / ${target.lunornamespace || target.lunOrNamespace || '1'}`
        const resizeContext = this.backingVolumeActionFields(volume, namespaceName, target.volumesizebytes || target.volumeSizeBytes)
        rows.push({
          key: id,
          id,
          ...resizeContext,
          namespaceId: target.id,
          name: volume.name || volume.displayname || target.volumename || target.volumeName || '-',
          size: this.formatCapacityValue(target.volumesizebytes || target.volumeSizeBytes || volume.size),
          used: this.formatCapacityValue(target.usedbytes || target.usedBytes || volume.usedfsbytes || volume.usedphysicalsize || target.physicalsize),
          diskOffering: target.diskofferingname || target.diskOfferingName || volume.diskofferingname || '-',
          storagePool: target.storage || target.storagepool || target.storagePoolName || volume.storage || '-',
          namespaceName,
          state: target.volumestate || target.volumeState || volume.state || '-',
          detachAllowed: false,
          detachDisabledReason: this.$t('message.storage.service.nvme.volume.detach.in.use.disabled'),
          raw: target
        })
      })
      return rows
    },
    nvmeAclRows () {
      const inheritedRows = this.nvmeSubsystemTargets
        .filter(target => this.nvmeSubsystemAllowAnyHost(target))
        .map((target, index) => ({
          key: `nvme-acl-policy-${target.id || index}`,
          targetName: this.nvmeTargetName(target) || '-',
          policySource: this.$t('label.storage.service.nvme.subsystem.policy'),
          principal: '*',
          authMode: this.$t('label.storage.service.nvme.auth.none'),
          authRequired: false,
          state: this.$t('label.storage.service.inherited.policy'),
          policyRow: true,
          raw: target
        }))
      const aclRows = this.storageService.nvmeHostAcls.map((acl, index) => {
        const target = this.blockTargetForAcl(acl, this.storageService.nvmeSubsystems)
        const config = this.parseStorageConfig(acl.config || acl.configjson || acl.configJson)
        const authMode = this.nvmeAuthModeFromAcl(acl, config)
        return {
          key: acl.id || `nvme-acl-${index}`,
          targetName: target?.targetname || target?.subsystemnqn || target?.targetName || acl.targetname || acl.resourcename || '-',
          policySource: this.$t('label.storage.service.explicit.rule'),
          principal: acl.principal || acl.hostnqn || '-',
          authMode: authMode.label,
          authRequired: authMode.required,
          state: acl.state || acl.status || '-',
          id: acl.id,
          subsystemAllowAnyHost: this.nvmeSubsystemAllowAnyHost(target),
          raw: acl
        }
      })
      return [...inheritedRows, ...aclRows]
    },
    nvmeSessionRows () {
      return this.protocolSessions('NVME_OF').map((session, index) => {
        const mappingStatus = String(session.mappingStatus || session.mappingstatus || 'UNMAPPED').toUpperCase()
        const exact = mappingStatus === 'EXACT'
        const possibleSubsystems = this.possibleSessionValues(session.possibleSubsystems || session.possiblesubsystems, 'subsystemNqn')
        const possibleNamespaces = this.possibleSessionValues(session.possibleNamespaces || session.possiblenamespaces, 'namespaceId')
        const hostPolicy = String(session.hostPolicy || session.hostpolicy || '').toUpperCase()
        const hostPolicyLabels = {
          ALLOW_ANY: this.$t('label.storage.service.nvme.allow.any.host'),
          EXPLICIT_ACL: this.$t('label.storage.service.nvme.explicit.host.acl'),
          DENY_ALL: this.$t('label.storage.service.nvme.no.host.access')
        }
        const logicalSessionId = session.logicalSessionId || session.logicalsessionid || ''
        const canDisconnect = this.boolValue(session.canDisconnect ?? session.candisconnect) && !!logicalSessionId
        return {
          key: session.transportSessionId || session.transportsessionid || session.sessionId || session.id || `${session.peer || 'session'}-${index}`,
          protocol: session.protocol || 'NVME_OF',
          peer: session.peer || session.client || session.clientIp || '-',
          state: session.state || session.status || '-',
          mappingStatus,
          mappingStatusLabel: this.iscsiMappingStatusLabel(mappingStatus),
          connectedAt: session.connectedAt || session.firstSeen || session.since || session.age || '-',
          resourceName: exact ? (session.resourceName || session.subsystemNqn || session.subsystemNQN || session.targetName || session.subsystem || '-') : '-',
          namespaceId: exact ? (session.namespaceId || session.namespaceid || session.lunOrNamespace || session.lunornamespace || session.namespace || '-') : '-',
          possibleSubsystems: possibleSubsystems || '-',
          possibleNamespaces: possibleNamespaces || '-',
          hostNqn: session.observedHostNqn || session.observedhostnqn || (exact ? (session.hostNqn || session.hostnqn || session.initiatorNqn || session.initiatornqn) : '') || '-',
          hostPolicyLabel: hostPolicyLabels[hostPolicy] || '-',
          queueCount: session.queueCount || session.queuecount || '-',
          local: session.local || session.endpoint || '-',
          sessionId: logicalSessionId,
          transportSessionId: session.transportSessionId || session.transportsessionid || session.sessionId || '',
          canDisconnect,
          disconnectDisabledReason: session.disconnectDisabledReason || session.disconnectdisabledreason || this.$t('message.storage.service.nvme.session.disconnect.unavailable'),
          raw: session
        }
      })
    },
    actionModalTitle () {
      const titles = {
        enableProtocol: 'label.storage.service.enable.protocol',
        nfsExport: 'label.storage.service.create.nfs.export',
        editNfsExport: 'label.storage.service.update.nfs.export',
        nfsAcl: 'label.storage.service.create.nfs.acl',
        editNfsAcl: 'label.storage.service.update.nfs.acl',
        deleteConfirm: 'label.storage.service.delete.confirm',
        smbShare: 'label.storage.service.create.smb.share',
        editSmbShare: 'label.storage.service.update.smb.share',
        smbAcl: 'label.storage.service.create.smb.acl',
        editSmbAcl: 'label.storage.service.update.smb.acl',
        adJoin: 'label.storage.service.join.ad.domain',
        adRejoin: 'label.storage.service.rejoin.ad.domain',
        adLeave: 'label.storage.service.leave.ad.domain',
        iscsiTarget: 'label.storage.service.create.iscsi.target',
        editIscsiTarget: 'label.storage.service.update.iscsi.target',
        iscsiAcl: 'label.storage.service.create.iscsi.acl',
        editIscsiAcl: 'label.storage.service.update.iscsi.acl',
        nvmePrepare: 'label.storage.service.prepare.nvmeof',
        nvmeSubsystem: 'label.storage.service.create.nvme.subsystem',
        editNvmeSubsystem: 'label.storage.service.update.nvme.subsystem',
        nvmeNamespace: 'label.storage.service.create.nvme.namespace',
        editNvmeNamespace: 'label.storage.service.update.nvme.namespace',
        nvmeHostAcl: 'label.storage.service.create.nvme.host.acl',
        editNvmeHostAcl: 'label.storage.service.update.nvme.host.acl',
        attachVolume: 'label.storage.service.attach.existing.volume',
        resizeShare: 'label.storage.service.resize.file.share',
        resizeBackingVolume: 'label.storage.service.resize.volume',
        detachBackingVolume: 'label.storage.service.detach.backing.volume',
        disconnectSession: 'label.storage.service.disconnect.session',
        deleteEndpoint: 'label.storage.service.delete.endpoint'
      }
      return this.$t(titles[this.actionModal.type] || 'label.action')
    },
    actionModalOkText () {
      return ['deleteConfirm', 'deleteEndpoint', 'detachBackingVolume', 'adLeave'].includes(this.actionModal.type) ? this.$t('label.ok') : this.$t('label.ok')
    },
    actionModalOkButtonProps () {
      const deleteEndpointBlocked = this.actionModal.type === 'deleteEndpoint' && !this.deleteEndpointConfirmationMatched
      const resizeVolumeSize = Number(this.forms.resizeBackingVolume.size)
      const resizeVolumeMinSize = Number(this.forms.resizeBackingVolume.minSizeGiB)
      const resizeBackingVolumeBlocked = this.actionModal.type === 'resizeBackingVolume' && (
        !this.forms.resizeBackingVolume.volumeid ||
        !Number.isInteger(resizeVolumeSize) ||
        !Number.isFinite(resizeVolumeMinSize) ||
        resizeVolumeSize < resizeVolumeMinSize
      )
      return {
        danger: ['deleteConfirm', 'deleteEndpoint', 'detachBackingVolume', 'adLeave'].includes(this.actionModal.type),
        disabled: (this.actionModal.type === 'deleteConfirm' && !this.deleteConfirmationMatched) ||
          deleteEndpointBlocked ||
          (this.actionModal.type === 'detachBackingVolume' && !this.forms.detachBackingVolume.confirmation) ||
          resizeBackingVolumeBlocked ||
          (this.actionModal.type === 'adLeave' && !this.adLeaveConfirmationMatched)
      }
    },
    isNfsRuntimeDualMode () {
      return this.nfsRuntimeProtocolMode() === 'V3V4_DUAL'
    },
    nfsServiceWideEndpointSummary () {
      const endpoints = []
      const add = (ip, port = 2049) => {
        if (!ip) return
        const normalizedIp = String(ip).trim()
        if (!normalizedIp || this.isWildcardListenIp(normalizedIp)) return
        const value = `${normalizedIp}:${port || 2049}`
        if (!endpoints.includes(value)) endpoints.push(value)
      }
      this.nfsRuntimeProtocolEntries().forEach(entry => {
        if (entry.listenIp === '0.0.0.0') {
          this.serviceEndpoints.forEach(ip => add(ip, entry.port))
        } else {
          add(entry.listenIp, entry.port)
        }
      })
      if (!endpoints.length) {
        this.serviceEndpoints.forEach(ip => add(ip, 2049))
      }
      return endpoints.length ? endpoints.join(', ') : '-'
    },
    isEnableProtocolNfsDualMode () {
      return this.actionModal.type === 'enableProtocol' &&
        String(this.forms.enableProtocol.protocol || '').toUpperCase() === 'NFS' &&
        this.isNfsRuntimeDualMode
    },
    enableProtocolListenerConflictMessage () {
      if (this.actionModal.type !== 'enableProtocol') {
        return ''
      }
      const protocol = String(this.forms.enableProtocol.protocol || '').toUpperCase()
      if (!['ISCSI', 'NVME_OF'].includes(protocol)) {
        return ''
      }
      return this.blockProtocolListenerConflictMessage(protocol, this.forms.enableProtocol.listenip, this.forms.enableProtocol.port)
    },
    enableProtocolListenerCoveredMessage () {
      if (this.actionModal.type !== 'enableProtocol') {
        return ''
      }
      const protocol = String(this.forms.enableProtocol.protocol || '').toUpperCase()
      if (protocol !== 'NVME_OF') {
        return ''
      }
      return this.blockProtocolListenerCoveredByWildcardMessage(protocol, this.forms.enableProtocol.listenip, this.forms.enableProtocol.port)
    },
    deleteConfirmationMatched () {
      if (this.actionModal.type !== 'deleteConfirm') {
        return true
      }
      return String(this.forms.deleteConfirm.confirmation || '') === String(this.actionModal.context?.name || '')
    },
    deleteEndpointConfirmationMatched () {
      if (this.actionModal.type !== 'deleteEndpoint') {
        return true
      }
      return !!this.forms.deleteEndpoint.listenip &&
        String(this.forms.deleteEndpoint.confirmation || '') === String(this.forms.deleteEndpoint.listenip || '')
    },
    adLeaveConfirmationMatched () {
      if (this.actionModal.type !== 'adLeave') {
        return true
      }
      return String(this.forms.adLeave.confirmation || '') === String(this.currentSmbDomainConfirmation || '')
    },
    deleteTargetTypeLabel () {
      const labels = {
        protocol: 'label.protocol',
        nfsExport: 'label.storage.service.nfs.export',
        nfsAcl: 'label.storage.service.nfs.acl'
      }
      return this.$t(labels[this.forms.deleteConfirm.resourceType] || 'label.resource')
    }
  },
  created () {
    const self = this
    this.dataResource = this.resource
    this.initStorageDefaults()
    this.fetchData()
    window.addEventListener('popstate', function () {
      self.setCurrentTab()
    })
  },
  watch: {
    resource: {
      deep: true,
      handler (newData, oldData) {
        if (newData !== oldData) {
          this.dataResource = newData
          this.initStorageDefaults()
          this.fetchData()
        }
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
    },
    'forms.enableProtocol.protocol': function (protocol) {
      this.forms.enableProtocol.port = this.defaultProtocolPort(protocol)
      if (String(protocol || '').toUpperCase() === 'NFS' && !this.forms.enableProtocol.protocolmode) {
        this.forms.enableProtocol.protocolmode = this.nfsRuntimeProtocolMode()
      }
    },
    'forms.nfsExport.volumemode': function (mode) {
      if (mode === 'CURRENT' && !this.forms.nfsExport.volumeid) {
        this.forms.nfsExport.volumeid = this.defaultCurrentBackingVolumeId()
      }
      this.syncNfsExportPathToCurrentVolume()
    },
    'forms.smbShare.volumemode': function (mode) {
      if (mode === 'CURRENT' && !this.forms.smbShare.volumeid) {
        this.forms.smbShare.volumeid = this.defaultCurrentBackingVolumeId()
      }
    },
    'forms.smbShare.name': function (name, previousName) {
      if (!this.actionModal.visible || !['smbShare', 'editSmbShare'].includes(this.actionModal.type)) {
        return
      }
      const previousPath = previousName ? this.defaultSmbSharePath(previousName) : ''
      if (!this.forms.smbShare.path || this.forms.smbShare.path === previousPath) {
        this.forms.smbShare.path = this.defaultSmbSharePath(name)
      }
    },
    'forms.nfsExport.volumeid': function () {
      this.syncNfsExportPathToCurrentVolume()
    },
    'forms.nfsExport.name': function (name, previousName) {
      if (!this.actionModal.visible || !['nfsExport', 'editNfsExport'].includes(this.actionModal.type)) {
        return
      }
      const previousPath = previousName ? this.defaultNfsExportPath(previousName) : ''
      if (!this.forms.nfsExport.path || this.forms.nfsExport.path === previousPath) {
        this.forms.nfsExport.path = this.defaultNfsExportPath(name)
      }
    },
    nvmeDhChapSupported: function (supported) {
      if (!supported) {
        this.forms.nvmeHostAcl.dhchapenabled = false
        this.forms.nvmeHostAcl.dhchapctrlenabled = false
        this.forms.nvmeHostAcl.dhchapkey = ''
        this.forms.nvmeHostAcl.dhchapctrlkey = ''
      }
    },
    'forms.nvmeHostAcl.dhchapenabled': function (enabled) {
      if (!enabled) {
        this.forms.nvmeHostAcl.dhchapctrlenabled = false
        this.forms.nvmeHostAcl.dhchapkey = ''
        this.forms.nvmeHostAcl.dhchapctrlkey = ''
      }
    }
  },
  mounted () {
    this.setCurrentTab()
  },
  unmounted () {
    this.$emit('wide-layout-change', false)
  },
  methods: {
    storageLabel (key, fallback) {
      const value = this.$t(key)
      return value && value !== key ? value : fallback
    },
    nvmeNamespaceListenerPortSet () {
      const ports = new Set()
      this.nvmeNamespaceTargets.forEach(target => {
        const config = this.parseStorageConfig(target.config || target.configjson || target.configJson)
        this.normalizeListenerPorts(target.listenerports || target.listenerPorts || config.listenerGroupPorts || config.listenerports || this.defaultNvmeListenerPort())
          .forEach(port => ports.add(Number(port)))
      })
      return ports
    },
    nvmeListenerSamePortCount (port) {
      const targetPort = Number(port || this.defaultNvmeListenerPort())
      return this.nvmeProtocolListenerEntries.filter(entry => Number(entry.port) === targetPort).length
    },
    isPrimaryServiceEndpointIp (ip) {
      const value = String(ip || '').trim()
      return !!value && !this.isWildcardListenIp(value) && value === String(this.serviceEndpoint || '').trim()
    },
    canDeleteNvmeListener (entry) {
      if (!entry) {
        return false
      }
      if (this.isPrimaryServiceEndpointIp(entry.listenIp)) {
        return false
      }
      const port = Number(entry.port || this.defaultNvmeListenerPort())
      if (this.nvmeNamespaceListenerPortSet().has(port) && this.nvmeListenerSamePortCount(port) <= 1) {
        return false
      }
      return true
    },
    nvmeListenerDeleteDisabledReason (entry) {
      if (!entry) {
        return ''
      }
      if (this.isPrimaryServiceEndpointIp(entry.listenIp)) {
        return this.$t('message.storage.service.nvme.listener.delete.primary.disabled')
      }
      const port = Number(entry.port || this.defaultNvmeListenerPort())
      if (this.nvmeNamespaceListenerPortSet().has(port) && this.nvmeListenerSamePortCount(port) <= 1) {
        return this.$t('message.storage.service.nvme.listener.delete.in.use.disabled', { port })
      }
      return ''
    },
    nvmeSubsystemNamespaceCount (subsystemName) {
      const name = String(subsystemName || '').trim()
      if (!name) {
        return 0
      }
      return this.nvmeNamespaceTargets.filter(namespace => {
        return String(namespace.targetname || namespace.subsystemnqn || namespace.targetName || '').trim() === name
      }).length
    },
    nvmeSubsystemDeleteDisabledReason (namespaceCount, hostAclCount) {
      if (namespaceCount > 0) {
        return this.$t('message.storage.service.nvme.subsystem.delete.namespace.disabled', { count: namespaceCount })
      }
      if (hostAclCount > 0) {
        return this.$t('message.storage.service.nvme.subsystem.delete.acl.disabled', { count: hostAclCount })
      }
      return ''
    },
    nvmePortStatusLabel (status) {
      if (!status) return ''
      const state = String(status.state || '').toUpperCase()
      if (state === 'UNUSED') return this.storageLabel('label.storage.service.runtime.unused', '미사용')
      if (state === 'LISTENING') return this.storageLabel('label.storage.service.runtime.listening', '수신 중')
      if (state === 'ERROR') return this.storageLabel('label.storage.service.runtime.error', '오류')
      if (status.listening) return this.storageLabel('label.storage.service.runtime.listening', '수신 중')
      return ''
    },
    smbClientPathForShare (shareName) {
      const name = shareName || `<${this.$t('label.storage.service.share.name.placeholder')}>`
      const pairs = this.smbEffectiveEndpointPairs.length ? this.smbEffectiveEndpointPairs : [{ ip: this.serviceEndpoint || '<service-ip>' }]
      return pairs.map(pair => `\\\\${String(pair.ip || '').trim() || '<service-ip>'}\\${name}`).join(', ')
    },
    setCurrentTab () {
      const tab = this.$route.query.tab ? this.$route.query.tab : 'details'
      this.currentTab = tab
      this.protocolWideLayout = this.isStorageProtocolTab(tab) && this.$route.query.wide === 'true'
      this.emitWideLayout()
    },
    handleChangeTab (e) {
      this.currentTab = e
      if (['details', 'nfs', 'smb', 'iscsi', 'nvmeof'].includes(e)) {
        this.fetchStorageServiceData()
      }
      if (!this.isStorageProtocolTab(e)) {
        this.protocolWideLayout = false
      }
      this.updateRouteQuery(e)
      this.emitWideLayout()
    },
    isStorageProtocolTab (tab) {
      return ['nfs', 'smb', 'iscsi', 'nvmeof'].includes(tab)
    },
    toggleProtocolWideLayout () {
      if (!this.isStorageProtocolTab(this.currentTab)) {
        return
      }
      this.protocolWideLayout = !this.protocolWideLayout
      this.updateRouteQuery(this.currentTab)
      this.emitWideLayout()
    },
    emitWideLayout () {
      this.$emit('wide-layout-change', this.isStorageProtocolTab(this.currentTab) && this.protocolWideLayout)
    },
    updateRouteQuery (tab) {
      const query = Object.assign({}, this.$route.query)
      query.tab = tab
      if (this.isStorageProtocolTab(tab) && this.protocolWideLayout) {
        query.wide = 'true'
      } else {
        delete query.wide
      }
      const queryString = Object.keys(query).map(key => {
        return (
          encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
        )
      }).join('&')
      history.pushState({}, null, '#' + this.$route.path + (queryString ? '?' + queryString : ''))
    },
    preserveCurrentProtocolRoute () {
      if (!this.isStorageProtocolTab(this.currentTab)) {
        return
      }
      const query = Object.assign({}, this.$route.query, { tab: this.currentTab })
      if (this.protocolWideLayout) {
        query.wide = 'true'
      } else {
        delete query.wide
      }
      this.$router.replace({ path: this.$route.path, query }).catch(() => {})
    },
    fetchInstances () {
      if (!this.resource.virtualmachineid) {
        return
      }
      this.instanceLoading = true
      var params = {
        id: this.resource.virtualmachineid,
        listall: true
      }
      if (this.$store.getters.listAllProjects) {
        params.projectid = '-1'
      }
      api('listVirtualMachines', params).then(json => {
        this.virtualmachines = json.listvirtualmachinesresponse.virtualmachine || []
        this.vm = this.virtualmachines[0] || {}
      })
      this.instanceLoading = false
    },
    fetchVolumes () {
      if (!this.resource.volumeid) {
        return
      }
      this.volumeLoading = true
      var params = {
        id: this.resource.volumeid,
        listsystemvms: 'true',
        listall: true
      }
      api('listVolumes', params).then(json => {
        this.volumes = json.listvolumesresponse.volume || []
        this.volume = this.volumes[0] || {}
      })
      this.volumeLoading = false
    },
    fetchData () {
      this.fetchInstances()
      this.fetchVolumes()
      this.fetchAvailableVolumes()
      this.fetchDiskOfferings()
      this.fetchStoragePools()
      if (this.hasStorageServiceApi) {
        this.fetchStorageServiceData()
      }
    },
    fetchDiskOfferings () {
      if (!('listDiskOfferings' in this.$store.getters.apis)) {
        this.diskOfferings = []
        return
      }
      this.diskOfferingLoading = true
      const params = {
        listall: true
      }
      if (this.resource.zoneid) {
        params.zoneid = this.resource.zoneid
      }
      getAPI('listDiskOfferings', params).then(json => {
        this.diskOfferings = json.listdiskofferingsresponse.diskoffering || []
        this.reconcileNfsNewVolumeStorage()
        this.reconcileSmbNewVolumeStorage()
      }).finally(() => {
        this.diskOfferingLoading = false
      })
    },
    fetchStoragePools () {
      if (!('listStoragePools' in this.$store.getters.apis) || !this.resource.zoneid) {
        this.storagePools = []
        return
      }
      this.storagePoolLoading = true
      getAPI('listStoragePools', {
        zoneid: this.resource.zoneid,
        listall: true,
        showicon: true
      }).then(json => {
        const pools = json.liststoragepoolsresponse.storagepool || []
        this.storagePools = pools.filter(pool => pool.state === 'Up')
        if (!this.forms.nfsExport.storageid) {
          this.forms.nfsExport.storageid = this.defaultNewVolumeStorageId()
        }
        this.reconcileNfsNewVolumeStorage()
        if (!this.forms.smbShare.storageid) {
          this.forms.smbShare.storageid = this.defaultSmbNewVolumeStorageId()
        }
        this.reconcileSmbNewVolumeStorage()
      }).finally(() => {
        this.storagePoolLoading = false
      })
    },
    fetchAvailableVolumes () {
      if (!('listVolumes' in this.$store.getters.apis) || !this.resource.zoneid) {
        this.availableVolumes = []
        return
      }
      this.volumeLoading = true
      getAPI('listVolumes', {
        zoneid: this.resource.zoneid,
        listall: true,
        type: 'DATADISK'
      }).then(json => {
        const volumes = json.listvolumesresponse.volume || []
        this.availableVolumes = volumes.filter(volume => {
          return volume.type === 'DATADISK' &&
            volume.state === 'Ready' &&
            !volume.virtualmachineid &&
            !volume.vmname
        })
      }).finally(() => {
        this.volumeLoading = false
      })
    },
    initStorageDefaults () {
      const firstNic = this.serviceListenIps[0] || {}
      this.forms.enableProtocol.listenip = firstNic.ipaddress || this.resource.ipaddress || ''
      this.forms.enableProtocol.port = this.defaultProtocolPort(this.forms.enableProtocol.protocol)
    },
    defaultProtocolPort (protocol) {
      const ports = {
        NFS: 2049,
        SMB: 445,
        ISCSI: 3260,
        NVME_OF: 4420
      }
      return ports[protocol] || null
    },
    async fetchStorageServiceData () {
      if (!this.hasStorageServiceApi || this.storageService.loading) {
        return
      }
      const initialLoad = !this.storageService.loaded
      this.storageService.loading = true
      this.storageService.initialLoading = initialLoad
      this.storageService.refreshing = !initialLoad
      const refreshGeneration = ++this.storageRefreshGeneration
      try {
        const params = {
          zoneid: this.resource.zoneid,
          listall: true
        }
        const instances = await this.listApi('listStorageServiceInstances', params, 'storageserviceinstance')
        const instance = instances.find(item => item.virtualmachineid === this.resource.virtualmachineid) || null
        if (!instance) {
          Object.assign(this.storageService, {
            instance: null,
            health: [],
            inventory: [],
            protocols: [],
            sessions: [],
            domains: [],
            nfsExports: [],
            smbShares: [],
            iscsiTargets: [],
            nvmeSubsystems: [],
            nvmeNamespaces: [],
            nfsAcls: [],
            smbAcls: [],
            iscsiAcls: [],
            nvmeHostAcls: [],
            backingVolumes: [],
            loaded: true
          })
          return
        }
        const results = await Promise.all([
          this.listApi('listStorageServiceHealth', { instanceid: instance.id }, 'storageserviceruntime'),
          this.listApi('listStorageServiceProtocols', { instanceid: instance.id }, 'storageserviceprotocol'),
          this.listApi('listStorageServiceDomainStatus', { instanceid: instance.id }, 'storageidentitydomain'),
          this.listApi('listStorageNfsExports', { instanceid: instance.id }, 'storagenfsexport'),
          this.listApi('listStorageSmbShares', { instanceid: instance.id }, 'storagesmbshare'),
          this.listApi('listStorageIscsiTargets', { instanceid: instance.id }, 'storageiscsitarget'),
          this.fetchNvmeStorageSnapshot(instance.id)
        ])
        if (refreshGeneration !== this.storageRefreshGeneration) {
          return
        }
        const [health, protocols, domains, nfsExports, smbShares, iscsiTargets, nvmeSnapshot] = results
        const accessRules = await this.loadAccessRules(instance.id, nfsExports, smbShares, iscsiTargets, nvmeSnapshot.nvmeSubsystems, nvmeSnapshot.nvmeHostAcls)
        const backingVolumes = await this.loadBackingVolumes({
          instance,
          nfsExports,
          smbShares,
          iscsiTargets,
          nvmeNamespaces: nvmeSnapshot.nvmeNamespaces
        })
        if (refreshGeneration !== this.storageRefreshGeneration) {
          return
        }
        Object.assign(this.storageService, {
          instance,
          health,
          protocols,
          domains,
          nfsExports,
          smbShares,
          iscsiTargets,
          inventory: nvmeSnapshot.inventory,
          sessions: nvmeSnapshot.sessions,
          nvmeSubsystems: nvmeSnapshot.nvmeSubsystems,
          nvmeNamespaces: nvmeSnapshot.nvmeNamespaces,
          ...accessRules,
          backingVolumes,
          loaded: true
        })
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.storageService.loading = false
        this.storageService.initialLoading = false
        this.storageService.refreshing = false
      }
    },
    clearStorageServiceRuntime () {
      this.storageService.health = []
      this.storageService.inventory = []
      this.storageService.protocols = []
      this.storageService.sessions = []
      this.storageService.domains = []
      this.storageService.nfsExports = []
      this.storageService.smbShares = []
      this.storageService.iscsiTargets = []
      this.storageService.nvmeSubsystems = []
      this.storageService.nvmeNamespaces = []
      this.storageService.nfsAcls = []
      this.storageService.smbAcls = []
      this.storageService.iscsiAcls = []
      this.storageService.nvmeHostAcls = []
      this.storageService.backingVolumes = []
    },
    async fetchRuntime (api, key) {
      this.storageService[key] = await this.listApi(api, { instanceid: this.storageService.instance.id }, 'storageserviceruntime')
    },
    async fetchCollection (api, key, objectName) {
      this.storageService[key] = await this.listApi(api, { instanceid: this.storageService.instance.id }, objectName)
    },
    async fetchNvmeStorageSnapshot (instanceId) {
      const [inventory, sessions, nvmeSubsystems, nvmeNamespaces] = await Promise.all([
        this.listApi('listStorageServiceInventory', { instanceid: instanceId }, 'storageserviceruntime'),
        this.listApi('listStorageServiceSessions', { instanceid: instanceId }, 'storageserviceruntime'),
        this.listApi('listStorageNvmeOfSubsystems', { instanceid: instanceId }, 'storagenvmeofsubsystem'),
        this.listApi('listStorageNvmeOfNamespaces', { instanceid: instanceId }, 'storagenvmeofnamespace')
      ])
      const nvmeHostAclLists = await Promise.all(nvmeSubsystems.map(target => this.listApi('listStorageNvmeOfHostAcls', { subsystemid: target.id }, 'storageaccessrule')))
      return {
        inventory,
        sessions,
        nvmeSubsystems,
        nvmeNamespaces,
        nvmeHostAcls: nvmeHostAclLists.flat()
      }
    },
    applyNvmeStorageSnapshot (snapshot, refreshGeneration) {
      if (!snapshot || refreshGeneration !== this.storageRefreshGeneration) {
        return false
      }
      this.storageService.inventory = snapshot.inventory
      this.storageService.sessions = snapshot.sessions
      this.storageService.nvmeSubsystems = snapshot.nvmeSubsystems
      this.storageService.nvmeNamespaces = snapshot.nvmeNamespaces
      this.storageService.nvmeHostAcls = snapshot.nvmeHostAcls
      return true
    },
    async fetchAccessRules (prefetchedNvmeHostAcls = null) {
      const rules = await this.loadAccessRules(
        this.storageService.instance.id,
        this.storageService.nfsExports,
        this.storageService.smbShares,
        this.storageService.iscsiTargets,
        this.nvmeSubsystemTargets,
        prefetchedNvmeHostAcls)
      Object.assign(this.storageService, rules)
    },
    async loadAccessRules (instanceId, nfsExports, smbShares, iscsiTargets, nvmeSubsystems, prefetchedNvmeHostAcls = null) {
      const [nfsAcls, smbAcls, iscsiAcls, nvmeHostAcls] = await Promise.all([
        this.listApi('listStorageNfsAcls', { instanceid: instanceId }, 'storageaccessrule'),
        Promise.all(smbShares.map(share => this.listApi('listStorageSmbAcls', { shareid: share.id }, 'storageaccessrule'))),
        Promise.all(iscsiTargets.map(target => this.listApi('listStorageIscsiAcls', { targetid: target.id }, 'storageaccessrule'))),
        prefetchedNvmeHostAcls === null
          ? Promise.all(nvmeSubsystems.map(target => this.listApi('listStorageNvmeOfHostAcls', { subsystemid: target.id }, 'storageaccessrule'))).then(items => items.flat())
          : Promise.resolve(prefetchedNvmeHostAcls)
      ])
      const iscsiAclMap = new Map()
      iscsiAcls.flat().forEach(acl => {
        const key = acl.id || [
          acl.targetgroupkey || acl.targetGroupKey || acl.targetname || acl.targetName || acl.resourceid || acl.resourceId || '',
          acl.principal || '',
          acl.permission || acl.access || ''
        ].join(':')
        if (!iscsiAclMap.has(key)) {
          iscsiAclMap.set(key, acl)
        }
      })
      const nfsIds = new Set(nfsExports.flatMap(share => [share.id, share.uuid]).filter(Boolean).map(String))
      return {
        nfsAcls: nfsAcls.filter(acl => [
          acl.exportid,
          acl.exportId,
          acl.shareid,
          acl.shareId,
          acl.resourceid,
          acl.resourceId,
          acl.resourceuuid,
          acl.resourceUuid,
          acl.parentid,
          acl.parentId,
          acl.fileshareid,
          acl.fileShareId
        ].filter(Boolean).some(value => nfsIds.has(String(value)))),
        smbAcls: smbAcls.flat(),
        iscsiAcls: Array.from(iscsiAclMap.values()),
        nvmeHostAcls
      }
    },
    async fetchBackingVolumes () {
      this.storageService.backingVolumes = await this.loadBackingVolumes({
        instance: this.storageService.instance,
        nfsExports: this.storageService.nfsExports,
        smbShares: this.storageService.smbShares,
        iscsiTargets: this.storageService.iscsiTargets,
        nvmeNamespaces: this.storageService.nvmeNamespaces
      })
    },
    async loadBackingVolumes ({ instance, nfsExports, smbShares, iscsiTargets, nvmeNamespaces }) {
      if (!('listVolumes' in this.$store.getters.apis)) {
        return []
      }
      const ids = new Set()
      nfsExports.forEach(share => {
        if (share.volumeid || share.volumeId) {
          ids.add(share.volumeid || share.volumeId)
        }
      })
      smbShares.forEach(share => {
        if (share.volumeid || share.volumeId) {
          ids.add(share.volumeid || share.volumeId)
        }
      })
      iscsiTargets.forEach(target => {
        if (target.volumeid || target.volumeId) {
          ids.add(target.volumeid || target.volumeId)
        }
      })
      nvmeNamespaces.forEach(target => {
        if (target.volumeid || target.volumeId) {
          ids.add(target.volumeid || target.volumeId)
        }
      })
      if (this.resource.volumeid) {
        ids.add(this.resource.volumeid)
      }
      if (this.volume.id) {
        ids.add(this.volume.id)
      }
      const volumeRequests = [...ids].map(id => this.listApi('listVolumes', {
        id,
        listall: true,
        listsystemvms: true
      }, 'volume'))
      const vmId = this.resource.virtualmachineid || this.vm.id || instance?.virtualmachineid
      if (vmId) {
        volumeRequests.push(this.listApi('listVolumes', {
          virtualmachineid: vmId,
          listall: true,
          listsystemvms: true
        }, 'volume'))
      }
      const volumeLists = await Promise.all(volumeRequests)
      const seen = new Set()
      return volumeLists.flat().filter(volume => {
        if (!volume?.id || seen.has(String(volume.id))) {
          return false
        }
        seen.add(String(volume.id))
        return true
      })
    },
    async listApi (api, params, objectName) {
      const apiMap = this.$store.getters.apis || {}
      const storageReadApi = api.startsWith('listStorage') || api === 'listVolumes'
      if (!(api in apiMap) && !storageReadApi) {
        return []
      }
      const json = await getAPI(api, params)
      const response = json[api.toLowerCase() + 'response'] || {}
      const items = response[objectName] || Object.values(response).find(value => Array.isArray(value)) || []
      return Array.isArray(items) ? items : [items]
    },
    delay (milliseconds) {
      return new Promise(resolve => window.setTimeout(resolve, milliseconds))
    },
    async waitStorageServiceJob (jobId, apiName, maxAttempts = 120) {
      for (let attempt = 0; attempt < maxAttempts; attempt++) {
        const json = await getAPI('queryAsyncJobResult', { jobId })
        const result = json.queryasyncjobresultresponse || {}
        if (Number(result.jobstatus) === 1) {
          return result
        }
        if (Number(result.jobstatus) === 2) {
          const errorText = result.jobresult?.errortext || result.jobresult?.errorText || result.jobresult?.message || this.$t('error.fetching.async.job.result')
          throw new Error(errorText)
        }
        await this.delay(2000)
      }
      throw new Error(`${apiName || 'Async job'} timed out`)
    },
    async callStorageIdentityRepair (dryRun, expectedRuntimePrimary = '') {
      const params = this.cleanParams({
        sharedfilesystemid: this.resource.id,
        dryrun: dryRun,
        expectedruntimeprimary: expectedRuntimePrimary
      })
      const json = await postAPI('repairStorageServiceNicIdentity', params)
      const response = json.repairstorageservicenicidentityresponse || {}
      if (!response.jobid) {
        return response
      }
      const job = await this.waitStorageServiceJob(response.jobid, 'repairStorageServiceNicIdentity')
      return job.jobresult?.storageserviceruntime || job.jobresult || {}
    },
    parseIdentityRepairEvidence (response) {
      const raw = response?.resultjson || response?.result || response?.details || response?.data || response?.response || response
      if (raw && typeof raw === 'object') {
        return raw
      }
      try {
        return JSON.parse(raw || '{}')
      } catch (error) {
        return {}
      }
    },
    async openStorageIdentityRepair () {
      if (!this.canRepairStorageIdentity || this.identityRepair.loading) return
      this.identityRepair.loading = true
      try {
        const response = await this.callStorageIdentityRepair(true)
        const evidence = this.parseIdentityRepairEvidence(response)
        this.identityRepair.eligible = this.boolValue(evidence.eligible)
        this.identityRepair.persistedPrimaryIp = evidence.persistedPrimaryIp || evidence.persistedprimaryip || ''
        this.identityRepair.runtimePrimaryIp = evidence.runtimePrimaryIp || evidence.runtimeprimaryip || ''
        this.identityRepair.aliases = Array.isArray(evidence.aliases) ? evidence.aliases : []
        this.identityRepair.reason = evidence.reason || ''
        this.identityRepair.visible = true
      } catch (error) {
        this.$message.error(error.message || String(error))
      } finally {
        this.identityRepair.loading = false
      }
    },
    closeStorageIdentityRepair () {
      if (!this.identityRepair.loading) this.identityRepair.visible = false
    },
    async applyStorageIdentityRepair () {
      if (!this.identityRepair.eligible || !this.identityRepair.runtimePrimaryIp) return
      this.identityRepair.loading = true
      try {
        await this.callStorageIdentityRepair(false, this.identityRepair.runtimePrimaryIp)
        await this.fetchStorageServiceData()
        if (this.storageIdentityDrift) {
          throw new Error(this.$t('message.storage.service.nic.identity.repair.postcondition.failed'))
        }
        this.identityRepair.visible = false
        this.$message.success(this.$t('message.storage.service.nic.identity.repair.success'))
      } catch (error) {
        this.$message.error(error.message || String(error))
      } finally {
        this.identityRepair.loading = false
      }
    },
    async waitVolumeAttachable (volumeId, maxAttempts = 60) {
      const attachableStates = ['Allocated', 'Ready', 'Uploaded']
      for (let attempt = 0; attempt < maxAttempts; attempt++) {
        const volumes = await this.listApi('listVolumes', { id: volumeId, listall: true }, 'volume')
        const volume = volumes[0]
        if (volume && attachableStates.includes(volume.state)) {
          return volume
        }
        if (volume && volume.state && !['Creating', 'Copying', 'UploadOp', 'UploadInProgress', 'Migrating', 'Resizing', 'Attaching', 'Snapshotting'].includes(volume.state)) {
          throw new Error(`Volume ${volumeId} is not attachable. Current state: ${volume.state}`)
        }
        await this.delay(2000)
      }
      throw new Error(`Volume ${volumeId} did not reach an attachable state`)
    },
    validateListenIpSelection () {
      const listenIp = this.forms.enableProtocol.listenip
      if (!listenIp) {
        this.$message.error(this.$t('message.storage.service.listen.ip.required'))
        return false
      }
      if (!this.isValidIpv4(listenIp)) {
        this.$message.error(this.$t('message.storage.service.listen.ip.invalid'))
        return false
      }
      if (this.enableProtocolListenerConflictMessage) {
        this.$message.error(this.enableProtocolListenerConflictMessage)
        return false
      }
      if (this.forms.enableProtocol.listenipmode !== 'NEW') {
        return true
      }
      if (this.serviceEndpoints.includes(String(listenIp).trim())) {
        this.$message.error(this.$t('message.storage.service.listen.ip.already.configured'))
        return false
      }
      const runtimeComparable = this.runtimeNetworkAddresses.filter(address => {
        return address.ipaddress && this.isValidIpv4(address.ipaddress) && address.cidr
      })
      if (runtimeComparable.length) {
        const matchedRuntime = runtimeComparable.find(address => this.isSameCidr(listenIp, address.ipaddress, address.cidr))
        if (!matchedRuntime) {
          this.$message.error(this.$t('message.storage.service.listen.ip.cidr.mismatch'))
          return false
        }
        return true
      }
      const matchedNic = this.serviceNics.find(nic => {
        return nic.ipaddress && this.isValidIpv4(nic.ipaddress) && this.isSameCidr(listenIp, nic.ipaddress, nic.netmask || nic.cidr)
      })
      const hasComparableNic = this.serviceNics.some(nic => nic.ipaddress && (nic.netmask || nic.cidr))
      if (hasComparableNic && !matchedNic) {
        this.$message.error(this.$t('message.storage.service.listen.ip.cidr.mismatch'))
        return false
      }
      return true
    },
    isValidIpv4 (value) {
      const parts = String(value || '').split('.')
      return parts.length === 4 && parts.every(part => /^\d+$/.test(part) && Number(part) >= 0 && Number(part) <= 255)
    },
    ipv4ToNumber (value) {
      return String(value).split('.').reduce((acc, part) => (acc << 8) + Number(part), 0) >>> 0
    },
    netmaskToPrefix (value) {
      if (!value) return null
      if (String(value).includes('/')) {
        const prefix = Number(String(value).split('/').pop())
        return Number.isInteger(prefix) ? prefix : null
      }
      if (!this.isValidIpv4(value)) return null
      const mask = this.ipv4ToNumber(value)
      let prefix = 0
      for (let i = 31; i >= 0; i--) {
        if ((mask & (1 << i)) === 0) break
        prefix++
      }
      return prefix
    },
    isSameCidr (ip, baseIp, netmaskOrCidr) {
      const prefix = this.netmaskToPrefix(netmaskOrCidr)
      if (prefix === null || prefix < 0 || prefix > 32) return false
      const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0
      return (this.ipv4ToNumber(ip) & mask) === (this.ipv4ToNumber(baseIp) & mask)
    },
    async runStorageAction (key, api, params, title) {
      if (!this.storageService.instance || this.actionLoading[key]) {
        return
      }
      const actionTab = this.currentTab
      const actionWideLayout = this.protocolWideLayout
      this.actionLoading[key] = true
      try {
        const response = await postAPI(api, this.cleanParams(params))
        const result = response[api.toLowerCase() + 'response'] || {}
        if (result.jobid) {
          this.$pollJob({
            jobId: result.jobid,
            title,
            description: this.storageService.instance.name || this.resource.name,
            successMessage: this.$t('label.success'),
            errorMessage: this.$t('label.error'),
            loadingMessage: this.$t('label.loading') + '...',
            catchMessage: this.$t('error.fetching.async.job.result'),
            action: { isFetchData: false },
            originalPage: this.$route.path,
            successMethod: () => this.refreshAfterStorageAction(key, actionTab, actionWideLayout)
          })
        } else {
          await this.refreshAfterStorageAction(key, actionTab, actionWideLayout)
        }
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.actionLoading[key] = false
      }
    },
    async refreshAfterStorageAction (key, actionTab = this.currentTab, actionWideLayout = this.protocolWideLayout) {
      if (!this.storageService.instance) {
        return this.fetchStorageServiceData()
      }
      this.currentTab = actionTab || this.currentTab
      this.protocolWideLayout = !!actionWideLayout
      this.preserveCurrentProtocolRoute()
      this.emitWideLayout()
      this.storageService.refreshing = true
      try {
        const nvmeActions = ['nvmePrepare', 'nvmeSubsystem', 'nvmeNamespace', 'editNvmeNamespace', 'nvmeHostAcl', 'editNvmeHostAcl']
        if (nvmeActions.includes(key)) {
          const refreshGeneration = ++this.storageRefreshGeneration
          const results = await Promise.all([
            this.fetchRuntime('listStorageServiceHealth', 'health'),
            this.fetchCollection('listStorageServiceProtocols', 'protocols', 'storageserviceprotocol'),
            this.fetchNvmeStorageSnapshot(this.storageService.instance.id)
          ])
          const snapshot = results[results.length - 1]
          if (this.applyNvmeStorageSnapshot(snapshot, refreshGeneration)) {
            await this.fetchAccessRules(snapshot.nvmeHostAcls)
            await this.fetchBackingVolumes()
          }
          return
        }
        await Promise.all([
          this.fetchRuntime('listStorageServiceHealth', 'health'),
          this.fetchRuntime('listStorageServiceInventory', 'inventory'),
          this.fetchCollection('listStorageServiceProtocols', 'protocols', 'storageserviceprotocol'),
          this.fetchRuntime('listStorageServiceSessions', 'sessions')
        ])
        if (['resizeBackingVolume', 'detachBackingVolume'].includes(key)) {
          await Promise.all([
            this.fetchCollection('listStorageNfsExports', 'nfsExports', 'storagenfsexport'),
            this.fetchCollection('listStorageSmbShares', 'smbShares', 'storagesmbshare'),
            this.fetchCollection('listStorageIscsiTargets', 'iscsiTargets', 'storageiscsitarget'),
            this.fetchCollection('listStorageNvmeOfSubsystems', 'nvmeSubsystems', 'storagenvmeofsubsystem'),
            this.fetchCollection('listStorageNvmeOfNamespaces', 'nvmeNamespaces', 'storagenvmeofnamespace')
          ])
          await this.fetchAccessRules()
          await this.fetchBackingVolumes()
        } else if (['nfsExport', 'editNfsExport', 'nfsAcl', 'editNfsAcl', 'resizeShare', 'enableProtocol', 'deleteEndpoint'].includes(key)) {
          await this.fetchCollection('listStorageNfsExports', 'nfsExports', 'storagenfsexport')
          await this.fetchAccessRules()
          await this.fetchBackingVolumes()
        } else if (['smbShare', 'editSmbShare', 'smbAcl', 'editSmbAcl', 'adJoin', 'adRejoin', 'adLeave'].includes(key)) {
          await Promise.all([
            this.fetchCollection('listStorageServiceDomainStatus', 'domains', 'storageidentitydomain'),
            this.fetchCollection('listStorageSmbShares', 'smbShares', 'storagesmbshare')
          ])
          await this.fetchAccessRules()
          await this.fetchBackingVolumes()
        } else if (['iscsiTarget', 'iscsiAcl'].includes(key)) {
          await this.fetchCollection('listStorageIscsiTargets', 'iscsiTargets', 'storageiscsitarget')
          await this.fetchAccessRules()
          await this.fetchBackingVolumes()
        }
      } finally {
        this.storageService.loaded = true
        this.storageService.refreshing = false
        this.currentTab = actionTab || this.currentTab
        this.protocolWideLayout = !!actionWideLayout
        this.preserveCurrentProtocolRoute()
        this.emitWideLayout()
      }
    },
    cleanParams (params) {
      const clean = {}
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') {
          clean[key] = value
        }
      })
      return clean
    },
    storageCellValue (record, column) {
      const key = column.dataIndex || column.key
      const value = record ? record[key] : null
      if (value === undefined || value === null || value === '') {
        return '-'
      }
      return value
    },
    storageTableLocale (emptyKey) {
      return {
        emptyText: h(Empty, {
          image: Empty.PRESENTED_IMAGE_SIMPLE,
          description: this.$t(emptyKey)
        })
      }
    },
    formatCapacityValue (value) {
      if (value === undefined || value === null || value === '') {
        return '-'
      }
      return this.formatBytes(value)
    },
    booleanLabel (value) {
      if (value === undefined || value === null || value === '') {
        return '-'
      }
      if (value === true || value === 'true' || value === 'TRUE' || value === 1 || value === '1') {
        return this.$t('label.yes')
      }
      return this.$t('label.no')
    },
    iscsiAuthVerificationLabel (value) {
      const status = String(value || 'UNKNOWN').toUpperCase()
      const labels = {
        VERIFIED: 'label.storage.service.authentication.verified',
        NOT_REQUIRED: 'label.storage.service.authentication.not.required',
        FAILED: 'label.storage.service.authentication.failed',
        UNKNOWN: 'label.storage.service.authentication.unknown'
      }
      return this.$t(labels[status] || labels.UNKNOWN)
    },
    iscsiAuthVerificationColor (value) {
      const status = String(value || 'UNKNOWN').toUpperCase()
      return {
        VERIFIED: 'green',
        NOT_REQUIRED: 'blue',
        FAILED: 'red',
        UNKNOWN: 'orange'
      }[status] || 'orange'
    },
    iscsiMappingStatusLabel (value) {
      const status = String(value || '').toLowerCase()
      if (status === 'exact') {
        return this.$t('label.storage.service.session.mapping.exact')
      }
      if (status === 'candidate') {
        return this.$t('label.storage.service.session.mapping.candidate')
      }
      if (status === 'ambiguous') {
        return this.$t('label.storage.service.session.mapping.ambiguous')
      }
      if (status === 'unmapped') {
        return this.$t('label.storage.service.session.mapping.unmapped')
      }
      return value || '-'
    },
    iscsiEndpointMappingStatusLabel (value) {
      const status = String(value || '').toLowerCase()
      if (status === 'exact') {
        return this.$t('label.storage.service.session.endpoint.mapping.exact')
      }
      if (status === 'candidate') {
        return this.$t('label.storage.service.session.endpoint.mapping.candidate')
      }
      if (status === 'unmapped') {
        return this.$t('label.storage.service.session.endpoint.mapping.unmapped')
      }
      return value || '-'
    },
    boolValue (value) {
      return value === true || value === 'true' || value === 'TRUE' || value === 1 || value === '1'
    },
    nvmeAuthModeLabel (dhChapEnabled, dhChapCtrlEnabled) {
      if (dhChapEnabled && dhChapCtrlEnabled) {
        return this.$t('label.storage.service.nvme.auth.mutual')
      }
      if (dhChapEnabled) {
        return this.$t('label.storage.service.nvme.auth.host')
      }
      return this.$t('label.storage.service.nvme.auth.none')
    },
    nvmeAuthModeFromAcl (acl, config = {}) {
      const dhChapEnabled = this.boolValue(this.firstDefined(
        acl.dhchapenabled,
        acl.dhChapEnabled,
        acl.dh_chap_enabled,
        config.dhChapEnabled,
        config.dhchapEnabled,
        config.dh_chap_enabled,
        config.hostAuthEnabled,
        config.authEnabled
      ))
      const dhChapCtrlEnabled = this.boolValue(this.firstDefined(
        acl.dhchapctrlenabled,
        acl.dhChapCtrlEnabled,
        acl.dh_chap_ctrl_enabled,
        config.dhChapCtrlEnabled,
        config.dhchapCtrlEnabled,
        config.dh_chap_ctrl_enabled,
        config.controllerAuthEnabled,
        config.ctrlAuthEnabled
      ))
      return {
        label: this.nvmeAuthModeLabel(dhChapEnabled, dhChapCtrlEnabled),
        required: dhChapEnabled || dhChapCtrlEnabled
      }
    },
    nvmeTargetName (target) {
      return target?.targetname || target?.subsystemnqn || target?.subsystemNqn || target?.targetName || ''
    },
    nvmeSubsystemByName (targetName) {
      const expected = String(targetName || '')
      if (!expected) return null
      return this.nvmeSubsystemTargets.find(target => String(this.nvmeTargetName(target)) === expected) || null
    },
    nvmeSubsystemAllowAnyHost (target, parsedConfig = null) {
      if (!target) return false
      const config = parsedConfig || this.parseStorageConfig(target.config || target.configjson || target.configJson)
      return this.boolValue(this.firstDefined(
        target.allowanyhost,
        target.allowAnyHost,
        target.allow_any_host,
        config.allowAnyHost,
        config.allowanyhost,
        config.allow_any_host
      ))
    },
    nvmeSubsystemAccessPolicy (target, explicitAcls = null, parsedConfig = null) {
      if (!target) {
        return {
          type: 'UNKNOWN',
          label: '-',
          color: 'default',
          effectiveAccess: '-',
          aclCount: 0
        }
      }
      const acls = explicitAcls || this.blockAclsForTarget(target, this.storageService.nvmeHostAcls)
      if (this.nvmeSubsystemAllowAnyHost(target, parsedConfig)) {
        return {
          type: 'ALLOW_ANY',
          label: this.$t('label.storage.service.nvme.access.any.host'),
          color: 'green',
          effectiveAccess: this.$t('label.storage.service.nvme.access.all.hosts'),
          aclCount: acls.length
        }
      }
      if (acls.length) {
        return {
          type: 'EXPLICIT_ACL',
          label: this.$t('label.storage.service.nvme.access.explicit.host.acl'),
          color: 'blue',
          effectiveAccess: this.$t('label.storage.service.nvme.host.acl.count', { count: acls.length }),
          aclCount: acls.length
        }
      }
      return {
        type: 'NO_ACL',
        label: this.$t('label.storage.service.nvme.access.no.host.acl'),
        color: 'orange',
        effectiveAccess: this.$t('label.storage.service.nvme.access.blocked'),
        aclCount: 0
      }
    },
    nvmeNamespaceAccessPolicy (namespaceTarget) {
      const subsystemName = this.nvmeTargetName(namespaceTarget)
      return this.nvmeSubsystemAccessPolicy(this.nvmeSubsystemByName(subsystemName))
    },
    permissionLabel (value) {
      const permission = String(value || '').toUpperCase()
      if (permission === 'READ_ONLY' || permission === 'READONLY' || permission === 'RO') {
        return this.$t('label.storage.service.permission.readonly')
      }
      if (permission === 'READ_WRITE' || permission === 'READWRITE' || permission === 'RW') {
        return this.$t('label.storage.service.permission.readwrite')
      }
      if (permission === 'ADMIN') {
        return this.$t('label.admin')
      }
      return value || '-'
    },
    principalTypeLabel (value) {
      const type = String(value || '').toUpperCase()
      const labels = {
        LOCAL_USER: 'label.storage.service.local.user',
        LOCAL_GROUP: 'label.storage.service.local.group',
        AD_USER: 'label.storage.service.ad.user',
        AD_GROUP: 'label.storage.service.ad.group',
        CIDR: 'label.storage.service.cidr'
      }
      return labels[type] ? this.$t(labels[type]) : (value || '-')
    },
    parseStorageConfig (config) {
      if (!config) {
        return {}
      }
      if (typeof config === 'object') {
        return config
      }
      try {
        return JSON.parse(config)
      } catch (e) {
        return {}
      }
    },
    firstDefined (...values) {
      return values.find(value => value !== undefined && value !== null && value !== '')
    },
    runtimeBlockTarget (target, inventoryKey) {
      const inventory = this.parsedInventory[inventoryKey] || {}
      let targets = Array.isArray(inventory.targets) ? inventory.targets : []
      if (inventoryKey === 'nvmeTargets' && !targets.length) {
        const nvmeInventory = this.parsedInventory.nvmeofSubsystems || this.parsedInventory.nvmeOfSubsystems || {}
        targets = []
        ;(nvmeInventory.subsystems || []).forEach(subsystem => {
          ;(subsystem.namespaces || []).forEach(namespace => {
            targets.push({
              ...namespace,
              targetName: subsystem.targetName || subsystem.subsystemNqn,
              runtime: namespace.runtime || {},
              ...(namespace.runtime || {})
            })
          })
        })
      }
      const targetName = String(target.targetname || target.targetName || '')
      const targetLun = String(target.lunornamespace || target.lunOrNamespace || target.lun || '0')
      if (inventoryKey === 'nvmeTargets') {
        const explicitMappingStatus = target.runtimemappingstatus || target.runtimeMappingStatus
        if (explicitMappingStatus) {
          return {
            runtimeMappingStatus: explicitMappingStatus,
            runtimeBackingPath: target.runtimebackingpath || target.runtimeBackingPath,
            actualBackingSizeBytes: target.actualbackingsizebytes || target.actualBackingSizeBytes,
            runtimeObservedAt: target.runtimeobservedat || target.runtimeObservedAt,
            runtimeState: target.runtimestate || target.runtimeState,
            enabled: target.runtimeenabled ?? target.runtimeEnabled
          }
        }
        const normalizeNqn = value => String(value || '').trim().toLowerCase()
        const normalizeNamespaceId = value => {
          const normalized = String(value || '1').trim()
          return /^\d+$/.test(normalized) ? String(Number(normalized)) : normalized
        }
        const compositeMatches = targets.filter(item => {
          const itemName = item.targetName || item.targetname || item.subsystemNqn || item.subsystemnqn
          const itemNamespace = item.lunOrNamespace || item.lunornamespace || item.namespaceId || item.namespaceid || item.nsid || '1'
          return normalizeNqn(itemName) === normalizeNqn(targetName) && normalizeNamespaceId(itemNamespace) === normalizeNamespaceId(targetLun)
        })
        if (compositeMatches.length === 1) {
          return { ...compositeMatches[0], runtimeMappingStatus: 'EXACT' }
        }
        return { runtimeMappingStatus: compositeMatches.length > 1 ? 'AMBIGUOUS' : 'UNMAPPED' }
      }
      const ids = [
        target.id,
        target.uuid,
        target.lunornamespace,
        target.lunOrNamespace
      ].filter(Boolean).map(value => String(value))
      const exact = targets.find(item => {
        const itemIds = [
          item.uuid,
          item.id,
          item.lunOrNamespace,
          item.lunornamespace
        ].filter(Boolean).map(value => String(value))
        return itemIds.some(id => ids.includes(id))
      })
      if (exact) {
        return exact
      }
      return targets.find(item => {
        const itemName = String(item.targetName || item.targetname || '')
        const itemLun = String(item.lunOrNamespace || item.lunornamespace || item.lun || '0')
        return itemName && itemName === targetName && itemLun === targetLun
      }) || {}
    },
    nvmeRuntimeMappingStatusLabel (status) {
      const labels = {
        EXACT: 'label.storage.service.session.mapping.exact',
        AMBIGUOUS: 'label.storage.service.session.mapping.ambiguous',
        UNAVAILABLE: 'label.storage.service.session.mapping.unavailable',
        UNMAPPED: 'label.storage.service.session.mapping.unmapped'
      }
      return this.$t(labels[String(status || '').toUpperCase()] || 'label.storage.service.session.mapping.unmapped')
    },
    fileShareVolumeMappingStatusLabel (status) {
      const labels = {
        EXACT: 'label.storage.service.volume.mapping.exact',
        STALE: 'label.storage.service.volume.mapping.stale',
        AMBIGUOUS: 'label.storage.service.volume.mapping.ambiguous',
        UNAVAILABLE: 'label.storage.service.volume.mapping.unavailable',
        UNMAPPED: 'label.storage.service.volume.mapping.unmapped'
      }
      return this.$t(labels[String(status || 'UNMAPPED').toUpperCase()] || labels.UNMAPPED)
    },
    volumeForShare (share) {
      const ids = [share.volumeid, share.volumeId, share.volumeuuid, share.volumeUuid].filter(Boolean).map(value => String(value))
      const exact = this.storageService.backingVolumes.find(volume => {
        return ids.includes(String(volume.id)) || ids.includes(String(volume.uuid))
      })
      if (exact && this.belongsToCurrentServiceVm(exact)) {
        return exact
      }
      return {}
    },
    nfsExportsForVolume (volume) {
      const ids = [volume?.id, volume?.uuid].filter(Boolean).map(value => String(value))
      if (!ids.length) {
        return []
      }
      return this.storageService.nfsExports.filter(share => {
        const shareIds = [share.volumeid, share.volumeId, share.volumeuuid, share.volumeUuid].filter(Boolean).map(value => String(value))
        return shareIds.some(id => ids.includes(id))
      })
    },
    currentBackingVolumeConfig (volume) {
      const shares = this.nfsExportsForVolume(volume)
      const share = shares.find(item => this.parseStorageConfig(item.config).lastInspection) || shares[0] || null
      return share ? this.parseStorageConfig(share.config) : {}
    },
    currentBackingVolumeMountPath (volume) {
      const config = this.currentBackingVolumeConfig(volume)
      const inspection = config.lastInspection || config.lastinspection || {}
      if (inspection.mountPath || inspection.mountpath) {
        return inspection.mountPath || inspection.mountpath
      }
      const share = this.nfsExportsForVolume(volume)[0]
      if (share?.path || share?.mountpath || share?.backingpath) {
        return share.path || share.mountpath || share.backingpath
      }
      if (volume?.id && (volume.id === this.resource.volumeid || volume.id === this.volume.id)) {
        return '/export'
      }
      return ''
    },
    currentBackingVolumeFilesystem (volume) {
      const config = this.currentBackingVolumeConfig(volume)
      const inspection = config.lastInspection || config.lastinspection || {}
      return inspection.filesystem || inspection.fsType || inspection.fstype || this.nfsExportsForVolume(volume)[0]?.filesystem || volume?.filesystem || ''
    },
    nfsBackingVolumeFilesystem (volume) {
      const shares = this.nfsExportsForVolume(volume)
      for (const share of shares) {
        const config = this.parseStorageConfig(share.config)
        const inspection = config.lastInspection || config.lastinspection || {}
        const value = inspection.filesystem || inspection.fsType || inspection.fstype || share.filesystem || share.fsType || share.fstype
        if (value) {
          return String(value).toLowerCase()
        }
      }
      const value = volume?.filesystem || volume?.fsType || volume?.fstype
      return value ? String(value).toLowerCase() : '-'
    },
    displayBackingVolumeFilesystem (volume, share = {}) {
      const value = this.currentBackingVolumeFilesystem(volume) ||
        share.filesystem ||
        share.fsType ||
        share.fstype ||
        volume?.filesystem ||
        volume?.fsType ||
        this.resource.filesystem ||
        ''
      return value ? String(value).toLowerCase() : '-'
    },
    currentBackingVolumeExportSummary (volume) {
      const shares = this.nfsExportsForVolume(volume)
      if (!shares.length) {
        return '-'
      }
      return shares.map(share => this.clientVisibleName(share.name || share.exportname, '-')).join(', ')
    },
    volumeForTarget (target) {
      const ids = [
        target.volumeid,
        target.volumeId,
        target.volumeuuid,
        target.volumeUuid
      ].filter(Boolean).map(value => String(value))
      const exact = this.storageService.backingVolumes.find(volume => {
        return ids.includes(String(volume.id)) || ids.includes(String(volume.uuid))
      })
      if (exact && this.belongsToCurrentServiceVm(exact)) {
        return exact
      }
      return {}
    },
    backingVolumeActionFields (volume = {}, resourceName = '-', fallbackSizeBytes = null) {
      const volumeId = volume.id || volume.uuid || ''
      const sizeValue = volumeId ? this.firstDefined(volume.size, fallbackSizeBytes) : null
      const currentSizeBytes = Number(sizeValue)
      const validSize = Number.isFinite(currentSizeBytes) && currentSizeBytes > 0
      const resizeAllowed = Boolean(volumeId) && validSize && this.belongsToCurrentServiceVm(volume)
      const currentSizeGiB = validSize ? Math.ceil(currentSizeBytes / (1024 * 1024 * 1024)) : null
      return {
        volumeid: resizeAllowed ? String(volumeId) : '',
        volumeUuid: volume.uuid || '',
        currentSizeBytes: validSize ? currentSizeBytes : null,
        currentSizeGiB,
        resizeAllowed,
        resizeDisabledReason: resizeAllowed ? '' : this.$t('message.storage.service.resize.backing.volume.identity.unavailable'),
        resourceName
      }
    },
    belongsToCurrentServiceVm (volume) {
      const vmIds = [
        this.resource.virtualmachineid,
        this.vm.id,
        this.storageService.instance?.virtualmachineid
      ].filter(Boolean).map(value => String(value))
      return !volume.virtualmachineid || vmIds.includes(String(volume.virtualmachineid))
    },
    nfsAclsForShare (share) {
      const ids = [share.id, share.uuid, share.resourceid, share.resourceuuid].filter(Boolean).map(value => String(value))
      return this.storageService.nfsAcls.filter(acl => {
        const aclIds = [
          acl.exportid,
          acl.shareid,
          acl.resourceid,
          acl.resourceuuid,
          acl.parentid,
          acl.fileshareid,
          acl.fileShareId
        ].filter(Boolean).map(value => String(value))
        return aclIds.some(id => ids.includes(id))
      })
    },
    nfsShareForAcl (acl) {
      const aclIds = [
        acl.exportid,
        acl.shareid,
        acl.resourceid,
        acl.resourceuuid,
        acl.parentid,
        acl.fileshareid,
        acl.fileShareId
      ].filter(Boolean).map(value => String(value))
      return this.storageService.nfsExports.find(share => {
        const shareIds = [share.id, share.uuid, share.resourceid, share.resourceuuid].filter(Boolean).map(value => String(value))
        return shareIds.some(id => aclIds.includes(id))
      }) || null
    },
    smbAclsForShare (share) {
      const ids = [share.id, share.uuid, share.resourceid, share.resourceuuid].filter(Boolean).map(value => String(value))
      return this.storageService.smbAcls.filter(acl => {
        const aclIds = [
          acl.shareid,
          acl.resourceid,
          acl.resourceuuid,
          acl.parentid,
          acl.fileshareid,
          acl.fileShareId
        ].filter(Boolean).map(value => String(value))
        return aclIds.some(id => ids.includes(id))
      })
    },
    smbShareForAcl (acl) {
      const aclIds = [
        acl.shareid,
        acl.resourceid,
        acl.resourceuuid,
        acl.parentid,
        acl.fileshareid,
        acl.fileShareId
      ].filter(Boolean).map(value => String(value))
      return this.storageService.smbShares.find(share => {
        const shareIds = [share.id, share.uuid, share.resourceid, share.resourceuuid].filter(Boolean).map(value => String(value))
        return shareIds.some(id => aclIds.includes(id))
      }) || null
    },
    blockAclsForTarget (target, rules) {
      const ids = [target.id, target.uuid, target.resourceid, target.resourceuuid].filter(Boolean).map(value => String(value))
      const groupKey = target.targetgroupkey || target.targetGroupKey || target.targetname || target.targetName
      return (rules || []).filter(acl => {
        const aclIds = [
          acl.targetid,
          acl.targetId,
          acl.resourceid,
          acl.resourceuuid,
          acl.parentid,
          acl.blocktargetid,
          acl.blockTargetId
        ].filter(Boolean).map(value => String(value))
        if (aclIds.some(id => ids.includes(id))) {
          return true
        }
        const aclGroupKey = acl.targetgroupkey || acl.targetGroupKey || acl.targetname || acl.targetName
        return groupKey && aclGroupKey && String(groupKey) === String(aclGroupKey)
      })
    },
    blockTargetForAcl (acl, targets) {
      const aclIds = [
        acl.targetid,
        acl.targetId,
        acl.resourceid,
        acl.resourceuuid,
        acl.parentid,
        acl.blocktargetid,
        acl.blockTargetId
      ].filter(Boolean).map(value => String(value))
      const exact = (targets || []).find(target => {
        const targetIds = [target.id, target.uuid, target.resourceid, target.resourceuuid].filter(Boolean).map(value => String(value))
        return targetIds.some(id => aclIds.includes(id))
      })
      if (exact) {
        return exact
      }
      const aclGroupKey = acl.targetgroupkey || acl.targetGroupKey || acl.targetname || acl.targetName
      return (targets || []).find(target => {
        const targetGroupKey = target.targetgroupkey || target.targetGroupKey || target.targetname || target.targetName
        return aclGroupKey && targetGroupKey && String(aclGroupKey) === String(targetGroupKey)
      }) || null
    },
    isProtocolEnabled (protocol) {
      const normalized = String(protocol || '').toUpperCase()
      const sources = [
        this.parsedHealth.protocols,
        this.parsedHealth.enabledProtocols,
        this.parsedInventory.protocols,
        this.parsedInventory.enabledProtocols
      ].filter(Boolean)
      return sources.some(source => {
        if (Array.isArray(source)) {
          return source.some(item => String(item.protocol || item.name || item).toUpperCase() === normalized && item.enabled !== false && item.state !== 'Disabled')
        }
        if (typeof source === 'object') {
          const value = source[normalized] || source[normalized.toLowerCase()]
          if (value === undefined) return false
          if (typeof value === 'object') return value.enabled !== false && value.state !== 'Disabled'
          return value !== false
        }
        return false
      })
    },
    aclSummary (acls) {
      if (!acls || acls.length === 0) {
        return '-'
      }
      return acls.map(acl => acl.principal || acl.cidr || acl.client || acl.id).filter(Boolean).join(', ')
    },
    capacityMultiplier (unit) {
      return this.capacityUnits.find(item => item.value === unit)?.multiplier || 1
    },
    toCapacityBytes (amount, unit) {
      if (amount === null || amount === undefined || amount === '') {
        return null
      }
      const numericAmount = Number(amount)
      if (Number.isNaN(numericAmount) || numericAmount < 0) {
        return null
      }
      return Math.round(numericAmount * this.capacityMultiplier(unit))
    },
    capacityAmountFromBytes (bytes, unit = 'GiB') {
      const value = Number(bytes)
      if (!Number.isFinite(value) || value <= 0) {
        return null
      }
      const divisor = this.capacityMultiplier(unit) || 1
      const amount = value / divisor
      return Number.isInteger(amount) ? amount : Number(amount.toFixed(2))
    },
    formatBytes (bytes) {
      const value = Number(bytes)
      if (!value || value < 0) {
        return '-'
      }
      const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
      let size = value
      let unitIndex = 0
      while (size >= 1024 && unitIndex < units.length - 1) {
        size = size / 1024
        unitIndex += 1
      }
      return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`
    },
    formatVolumeOption (volume) {
      const name = volume.name || volume.displayname || volume.id
      const size = volume.size ? this.formatBytes(volume.size) : '-'
      const zone = volume.zonename || this.resource.zonename || ''
      return [name, size, zone].filter(Boolean).join(' / ')
    },
    formatCurrentBackingVolumeOption (volume) {
      const name = volume.name || volume.displayname || volume.id
      const size = volume.size ? this.formatBytes(volume.size) : '-'
      const mountPath = this.currentBackingVolumeMountPath(volume)
      const exports = this.currentBackingVolumeExportSummary(volume)
      return [name, size, mountPath || this.$t('label.storage.service.mount.path'), exports !== '-' ? exports : null].filter(Boolean).join(' / ')
    },
    storagePoolLabel (pool) {
      const name = pool.name || pool.displaytext || pool.id
      const scope = pool.scope || ''
      const type = pool.type || pool.storagetype || ''
      const tags = this.extractStorageTags(pool)
      return [name, type, scope, tags.length ? tags.join(',') : ''].filter(Boolean).join(' / ')
    },
    extractStorageTags (item) {
      if (!item) {
        return []
      }
      const raw = item.tags ?? item.storageTags ?? item.storagetags ?? item.storagepooltags ?? item.storagePoolTags
      if (Array.isArray(raw)) {
        return raw.map(value => String(value).trim()).filter(Boolean)
      }
      if (raw && typeof raw === 'object') {
        return Object.values(raw).map(value => String(value).trim()).filter(Boolean)
      }
      return String(raw || '')
        .split(',')
        .map(value => value.trim())
        .filter(Boolean)
    },
    reconcileNfsNewVolumeStorage () {
      if (this.forms.nfsExport.volumemode !== 'NEW') {
        return
      }
      if (!this.filteredNfsNewVolumeStoragePools.some(pool => pool.id === this.forms.nfsExport.storageid)) {
        this.forms.nfsExport.storageid = this.filteredNfsNewVolumeStoragePools[0]?.id || ''
      }
    },
    reconcileSmbNewVolumeStorage () {
      if (this.forms.smbShare.volumemode !== 'NEW') {
        return
      }
      if (!this.filteredSmbNewVolumeStoragePools.some(pool => pool.id === this.forms.smbShare.storageid)) {
        this.forms.smbShare.storageid = this.filteredSmbNewVolumeStoragePools[0]?.id || ''
      }
    },
    reconcileIscsiNewVolumeStorage () {
      if (this.forms.iscsiTarget.volumemode !== 'NEW') {
        return
      }
      if (!this.filteredIscsiNewVolumeStoragePools.some(pool => pool.id === this.forms.iscsiTarget.storageid)) {
        this.forms.iscsiTarget.storageid = this.filteredIscsiNewVolumeStoragePools[0]?.id || ''
      }
    },
    reconcileNvmeNewVolumeStorage () {
      if (this.forms.nvmeNamespace.volumemode !== 'NEW') {
        return
      }
      if (!this.filteredNvmeNewVolumeStoragePools.some(pool => pool.id === this.forms.nvmeNamespace.storageid)) {
        this.forms.nvmeNamespace.storageid = this.filteredNvmeNewVolumeStoragePools[0]?.id || ''
      }
    },
    defaultCurrentBackingVolumeId () {
      if (this.currentBackingVolumes.length === 1) {
        return this.currentBackingVolumes[0].id
      }
      return ''
    },
    defaultCurrentIscsiBlockVolumeId () {
      if (this.currentIscsiBlockVolumes.length === 1) {
        return this.currentIscsiBlockVolumes[0].id
      }
      return ''
    },
    nfsExportPathForCurrentVolume (name) {
      const safeName = String(name || 'nfs01').trim().replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'nfs01'
      return this.defaultNfsExportPath(safeName)
    },
    defaultNewVolumeStorageId () {
      const preferred = this.volume.storageid || this.resource.storageid || this.storageService.backingVolumes?.[0]?.storageid
      if (preferred && this.filteredNfsNewVolumeStoragePools.some(pool => pool.id === preferred)) {
        return preferred
      }
      return this.filteredNfsNewVolumeStoragePools[0]?.id || preferred || ''
    },
    defaultSmbNewVolumeStorageId () {
      const preferred = this.volume.storageid || this.resource.storageid || this.storageService.backingVolumes?.[0]?.storageid
      if (preferred && this.filteredSmbNewVolumeStoragePools.some(pool => pool.id === preferred)) {
        return preferred
      }
      return this.filteredSmbNewVolumeStoragePools[0]?.id || preferred || ''
    },
    defaultIscsiNewVolumeStorageId () {
      const preferred = this.volume.storageid || this.resource.storageid || this.storageService.backingVolumes?.[0]?.storageid
      if (preferred && this.filteredIscsiNewVolumeStoragePools.some(pool => pool.id === preferred)) {
        return preferred
      }
      return this.filteredIscsiNewVolumeStoragePools[0]?.id || preferred || ''
    },
    defaultNvmeNewVolumeStorageId () {
      const preferred = this.volume.storageid || this.resource.storageid || this.storageService.backingVolumes?.[0]?.storageid
      if (preferred && this.filteredNvmeNewVolumeStoragePools.some(pool => pool.id === preferred)) {
        return preferred
      }
      return this.filteredNvmeNewVolumeStoragePools[0]?.id || preferred || ''
    },
    formatProtocolEndpoints (port, preferredIp = null) {
      const ips = preferredIp ? [preferredIp] : this.serviceEndpoints
      const values = ips.filter(ip => ip && !this.isWildcardListenIp(ip)).map(ip => `${ip}:${port}`)
      return values.length ? values.join(', ') : '-'
    },
    protocolListenerEntries (protocolName) {
      const protocol = String(protocolName || '').toUpperCase()
      const defaultPort = protocol === 'ISCSI' ? 3260 : protocol === 'NVME_OF' ? 4420 : this.defaultProtocolPort(protocol)
      const raw = (this.storageService.protocols || [])
        .filter(item => String(item.protocol || item.name || '').toUpperCase() === protocol)
        .filter(item => item.enabled === undefined || this.boolValue(item.enabled))
        .map(item => ({
          listenIp: String(item.listenip || item.listenIp || item.ipaddress || '0.0.0.0').trim() || '0.0.0.0',
          port: Number(item.port || item.listenPort || item.listenport || item.endpointPort || defaultPort),
          listenerType: String(item.listenertype || item.listenerType || '').toUpperCase(),
          primaryIp: item.primaryip || item.primaryIp || '',
          runtimePrimaryIp: item.runtimeprimaryip || item.runtimePrimaryIp || '',
          identityStatus: item.identitystatus || item.identityStatus || 'UNKNOWN',
          identityWarning: item.identitywarning || item.identityWarning || '',
          effectiveEndpoints: item.effectiveendpoints || item.effectiveEndpoints || [],
          runtimeState: item.runtimestate || item.runtimeState || item.state || '-',
          linkedResourceCount: Number(item.linkedresourcecount ?? item.linkedResourceCount ?? 0),
          raw: item
        }))
        .filter(item => Number.isFinite(item.port) && item.port > 0)
      const seen = new Set()
      const unique = raw.filter(item => {
        const key = `${item.listenIp}:${item.port}`
        if (seen.has(key)) {
          return false
        }
        seen.add(key)
        return true
      })
      const wildcardByPort = new Map()
      unique.forEach(item => {
        if (item.listenerType === 'WILDCARD' || this.isWildcardListenIp(item.listenIp)) {
          wildcardByPort.set(item.port, item)
        }
      })
      return unique.filter(item => {
        if (item.listenerType === 'WILDCARD' || this.isWildcardListenIp(item.listenIp)) return true
        const wildcard = wildcardByPort.get(item.port)
        if (!wildcard) return true
        return String(item.runtimeState || '') !== String(wildcard.runtimeState || '') ||
          Number(item.linkedResourceCount || 0) !== Number(wildcard.linkedResourceCount || 0)
      })
    },
    protocolListenerRows (protocolName) {
      const protocol = String(protocolName || '').toUpperCase()
      return this.protocolListenerEntries(protocol).map(entry => {
        const wildcard = entry.listenerType === 'WILDCARD' || this.isWildcardListenIp(entry.listenIp)
        const apiEndpoints = (Array.isArray(entry.effectiveEndpoints) ? entry.effectiveEndpoints : [])
          .map(item => {
            const ip = item?.ipaddress || item?.ipAddress || item?.ip || ''
            const port = Number(item?.port || entry.port)
            return ip && port ? `${ip}:${port}` : ''
          })
          .filter(Boolean)
        const inferredEndpoints = wildcard
          ? this.serviceEndpoints.map(ip => `${ip}:${entry.port}`)
          : [`${entry.listenIp}:${entry.port}`]
        const endpoints = apiEndpoints.length ? apiEndpoints : inferredEndpoints
        const linkedResourceCount = Number.isFinite(entry.linkedResourceCount) ? entry.linkedResourceCount : 0
        const canDelete = linkedResourceCount === 0
        return {
          key: `${protocol}:${entry.listenIp}:${entry.port}`,
          id: entry.raw?.id,
          listenIp: entry.listenIp,
          port: entry.port,
          type: wildcard
            ? this.$t('label.storage.service.listener.type.wildcard')
            : this.$t('label.storage.service.listener.type.dedicated'),
          effectiveEndpoints: [...new Set(endpoints)].join(', ') || '-',
          linkedResourceCount,
          state: entry.runtimeState || '-',
          status: entry.runtimeState || '-',
          protocol,
          canDelete,
          deleteDisabledReason: canDelete ? '' : this.$t('message.storage.service.listener.in.use'),
          raw: entry.raw || entry
        }
      })
    },
    protocolEndpointValues (rows) {
      const values = []
      ;(rows || []).forEach(row => {
        String(row?.effectiveEndpoints || '')
          .split(',')
          .map(value => value.trim())
          .filter(value => value && value !== '-' && !value.startsWith('0.0.0.0:'))
          .forEach(value => {
            if (!values.includes(value)) values.push(value)
          })
      })
      return values
    },
    splitEndpointValue (endpoint) {
      const value = String(endpoint || '').trim()
      const separator = value.lastIndexOf(':')
      if (separator <= 0) {
        return { ip: value, port: '' }
      }
      return {
        ip: value.slice(0, separator),
        port: value.slice(separator + 1)
      }
    },
    formatBlockProtocolListenerGroupEndpoints (protocolName, ports) {
      const normalized = this.normalizeListenerPorts(ports)
      if (!normalized.length) {
        return '-'
      }
      const entries = this.protocolListenerEntries(protocolName)
      const values = []
      const add = value => {
        if (value && !values.includes(value)) values.push(value)
      }
      normalized.forEach(port => {
        const matching = entries.filter(item => Number(item.port) === Number(port))
        if (!matching.length) {
          this.serviceEndpoints.forEach(ip => add(`${ip}:${port}`))
          return
        }
        matching.forEach(item => {
          if (this.isWildcardListenIp(item.listenIp)) {
            this.serviceEndpoints.forEach(ip => add(`${ip}:${port}`))
          } else {
            add(`${item.listenIp}:${port}`)
          }
        })
      })
      return values.length ? values.join(', ') : '-'
    },
    nvmeEffectiveEndpointsForListener (entry) {
      if (!entry || !entry.port) {
        return []
      }
      if (this.isWildcardListenIp(entry.listenIp)) {
        return this.serviceEndpoints.map(ip => ({
          ip,
          port: entry.port,
          endpoint: `${ip}:${entry.port}`,
          coveredByWildcard: true
        }))
      }
      return [{
        ip: entry.listenIp,
        port: entry.port,
        endpoint: `${entry.listenIp}:${entry.port}`,
        coveredByWildcard: false
      }]
    },
    nvmeHealthPortStatusMap () {
      const health = this.parsedHealth || {}
      const runtime = health.nvmeofRuntime || health.nvmeOfRuntime || health.nvmeof || health.nvmeOf || {}
      const normalizeStatus = value => {
        if (value && typeof value === 'object') {
          const status = String(value.status || value.state || '').toLowerCase()
          const listening = this.boolValue(value.listening ?? value.ready ?? value.ok) || ['ok', 'ready', 'running', 'listen', 'listening'].includes(status)
          const linked = this.boolValue(value.linked ?? value.exposed ?? value.inUse ?? value.inuse)
          const state = status || (linked ? (listening ? 'listening' : 'error') : 'unused')
          return {
            ...value,
            listening,
            linked,
            state: state.toUpperCase()
          }
        }
        const listening = this.boolValue(value) || String(value || '').toLowerCase() === 'ok'
        return {
          listening,
          linked: listening,
          state: listening ? 'LISTENING' : 'ERROR'
        }
      }
      const candidates = [
        runtime.portStatus,
        runtime.portstatus,
        runtime.ports,
        runtime.listeners,
        health.nvmeofPorts,
        health.nvmeOfPorts
      ]
      const map = {}
      candidates.filter(Boolean).forEach(source => {
        if (Array.isArray(source)) {
          source.forEach(item => {
            const port = Number(item.port || item.listenPort || item.listenport)
            if (Number.isFinite(port) && port > 0) {
              map[port] = normalizeStatus(item)
            }
          })
          return
        }
        if (typeof source === 'object') {
          Object.entries(source).forEach(([portKey, value]) => {
            const port = Number(portKey)
            if (!Number.isFinite(port) || port <= 0) {
              return
            }
            map[port] = normalizeStatus(value)
          })
        }
      })
      return map
    },
    nvmeListenerState (entry) {
      const statusMap = this.nvmeHealthPortStatusMap()
      if (Object.prototype.hasOwnProperty.call(statusMap, Number(entry.port))) {
        const status = statusMap[Number(entry.port)]
        const state = String(status?.state || '').toUpperCase()
        if (state === 'UNUSED') return 'Unused'
        return status?.listening ? 'Ready' : 'Error'
      }
      return 'Ready'
    },
    blockProtocolListenerConflictMessage (protocolName, listenIp, port) {
      const protocol = String(protocolName || '').toUpperCase()
      if (!['ISCSI', 'NVME_OF'].includes(protocol)) {
        return ''
      }
      const protocolLabel = protocol === 'NVME_OF' ? 'NVMe-oF' : 'iSCSI'
      const requestedIp = String(listenIp || '0.0.0.0').trim() || '0.0.0.0'
      const requestedPort = Number(port || this.defaultProtocolPort(protocol))
      if (!Number.isFinite(requestedPort) || requestedPort <= 0) {
        return ''
      }
      const requestedWildcard = this.isWildcardListenIp(requestedIp)
      const existing = (this.storageService.protocols || [])
        .filter(item => String(item.protocol || item.name || '').toUpperCase() === protocol)
        .filter(item => item.enabled === undefined || this.boolValue(item.enabled))
        .map(item => ({
          listenIp: String(item.listenip || item.listenIp || item.ipaddress || '0.0.0.0').trim() || '0.0.0.0',
          port: Number(item.port || item.listenPort || item.listenport || item.endpointPort || this.defaultProtocolPort(protocol))
        }))
      for (const item of existing) {
        if (Number(item.port) !== requestedPort || item.listenIp === requestedIp) {
          continue
        }
        const existingWildcard = this.isWildcardListenIp(item.listenIp)
        if (requestedWildcard && !existingWildcard) {
          return `${protocolLabel} ${this.$t('message.storage.service.listener.wildcard.conflict')} (${requestedIp}:${requestedPort} / ${item.listenIp}:${item.port})`
        }
        if (!requestedWildcard && existingWildcard) {
          if (protocol === 'NVME_OF') {
            continue
          }
          return `${protocolLabel} ${this.$t('message.storage.service.listener.covered.by.wildcard')} (${requestedIp}:${requestedPort} / ${item.listenIp}:${item.port})`
        }
      }
      return ''
    },
    blockProtocolListenerCoveredByWildcardMessage (protocolName, listenIp, port) {
      const protocol = String(protocolName || '').toUpperCase()
      if (protocol !== 'NVME_OF') {
        return ''
      }
      const protocolLabel = 'NVMe-oF'
      const requestedIp = String(listenIp || '0.0.0.0').trim() || '0.0.0.0'
      const requestedPort = Number(port || this.defaultProtocolPort(protocol))
      if (!Number.isFinite(requestedPort) || requestedPort <= 0 || this.isWildcardListenIp(requestedIp)) {
        return ''
      }
      const existing = (this.storageService.protocols || [])
        .filter(item => String(item.protocol || item.name || '').toUpperCase() === protocol)
        .filter(item => item.enabled === undefined || this.boolValue(item.enabled))
        .map(item => ({
          listenIp: String(item.listenip || item.listenIp || item.ipaddress || '0.0.0.0').trim() || '0.0.0.0',
          port: Number(item.port || item.listenPort || item.listenport || item.endpointPort || this.defaultProtocolPort(protocol))
        }))
      for (const item of existing) {
        if (Number(item.port) === requestedPort && this.isWildcardListenIp(item.listenIp)) {
          return `${protocolLabel} ${this.$t('message.storage.service.listener.covered.by.wildcard.reuse')} (${requestedIp}:${requestedPort} / ${item.listenIp}:${item.port})`
        }
      }
      return ''
    },
    isWildcardListenIp (ip) {
      const value = String(ip || '').trim()
      return value === '0.0.0.0' || value === '::' || value === '*'
    },
    normalizeEndpointIps (value) {
      if (!value) {
        return []
      }
      const values = Array.isArray(value) ? value : String(value).split(',')
      const seen = new Set()
      return values.map(item => String(item || '').trim())
        .filter(item => item && !this.isWildcardListenIp(item) && !seen.has(item) && seen.add(item))
    },
    normalizeListenerPorts (value) {
      if (value === undefined || value === null || value === '') {
        return []
      }
      const values = Array.isArray(value) ? value : String(value).split(',')
      const seen = new Set()
      return values.map(item => Number(String(item || '').trim()))
        .filter(port => Number.isFinite(port) && port > 0 && port <= 65535 && !seen.has(port) && seen.add(port))
    },
    defaultNfsListenerPort () {
      if (this.isNfsRuntimeDualMode) {
        return 2049
      }
      const options = this.nfsListenerGroupOptions || []
      return options[0]?.value || 2049
    },
    nfsExportListenerPorts (share, config = null) {
      if (this.isNfsRuntimeDualMode || this.nfsExportProtocolMode(share, config) === 'V3V4_DUAL') {
        return [2049]
      }
      const parsedConfig = config || this.parseStorageConfig(share?.config)
      const ports = this.normalizeListenerPorts(share?.listenerports ?? share?.listenerPorts ?? parsedConfig.listenerGroupPorts ?? parsedConfig.listenergroupports ?? parsedConfig.listenerPorts ?? parsedConfig.listenerports)
      return ports.length ? ports : [this.defaultNfsListenerPort()]
    },
    selectedNfsListenerPorts () {
      return this.normalizeListenerPorts(this.forms.nfsExport.listenerports)
    },
    formatNfsListenerGroupEndpoints (ports) {
      const normalizedPorts = this.normalizeListenerPorts(ports)
      const values = []
      normalizedPorts.forEach(port => {
        this.serviceEndpoints.forEach(ip => {
          if (ip) values.push(`${ip}:${port}`)
        })
      })
      return values.length ? values.join(', ') : '-'
    },
    nfsProtocolModeLabel (mode) {
      return String(mode || 'V4_ONLY').trim().toUpperCase() === 'V3V4_DUAL'
        ? this.$t('label.storage.service.nfs.protocol.mode.dual')
        : this.$t('label.storage.service.nfs.protocol.mode.v4only')
    },
    normalizeNfsProtocolModeValue (mode) {
      return String(mode || '').trim().toUpperCase() === 'V3V4_DUAL' ? 'V3V4_DUAL' : (mode ? 'V4_ONLY' : '')
    },
    nfsProtocolModeFromObject (source) {
      if (!source || typeof source !== 'object') {
        return ''
      }
      return this.normalizeNfsProtocolModeValue(source.protocolMode || source.protocolmode || source.mode || source.nfsProtocolMode || source.nfsprotocolmode)
    },
    nfsRuntimeProtocolEntries () {
      const items = []
      const seen = new Set()
      const add = (entry, inheritedMode = '') => {
        if (!entry || typeof entry !== 'object') {
          return
        }
        const protocol = String(entry.protocol || entry.service || entry.name || entry.type || '').trim().toUpperCase()
        if (protocol && protocol !== 'NFS') {
          return
        }
        const listenIp = String(entry.listenIp || entry.listenip || entry.ipaddress || entry.ip || entry.address || entry.listenAddress || entry.addr || '').trim()
        if (!listenIp) {
          return
        }
        const port = Number(entry.port || entry.listenPort || entry.listenport || entry.endpointPort || this.defaultProtocolPort('NFS'))
        const resolvedPort = Number.isFinite(port) && port > 0 ? port : this.defaultProtocolPort('NFS')
        const resolvedMode = this.nfsProtocolModeFromObject(entry) || this.normalizeNfsProtocolModeValue(inheritedMode) || 'V4_ONLY'
        const key = `${listenIp}:${resolvedPort}:${resolvedMode}`
        if (seen.has(key)) {
          return
        }
        seen.add(key)
        items.push({
          listenIp,
          port: resolvedPort,
          protocolMode: resolvedMode
        })
      }
      const addSource = (source, inheritedMode = '') => {
        if (!source) {
          return
        }
        if (Array.isArray(source)) {
          source.forEach(item => add(item, inheritedMode))
          return
        }
        if (typeof source === 'object') {
          const sourceMode = this.nfsProtocolModeFromObject(source) || inheritedMode
          if (source.protocol || source.service || source.name || source.listenIp || source.listenip || source.port || source.endpointMode || source.protocolMode || source.protocolmode) {
            add(source, sourceMode)
          }
          Object.values(source).forEach(value => {
            if (Array.isArray(value)) {
              value.forEach(item => add(item, sourceMode))
            } else if (value && typeof value === 'object') {
              add(value, sourceMode)
            }
          })
        }
      }
      addSource(this.storageService.protocols)
      addSource(this.storageService.enabledProtocols)
      addSource(this.storageService.protocol)
      addSource(this.parsedHealth.protocols)
      addSource(this.parsedHealth.enabledProtocols)
      addSource(this.parsedHealth.nfsGanesha)
      addSource(this.parsedHealth.nfsGanesha?.endpoints, this.nfsProtocolModeFromObject(this.parsedHealth.nfsGanesha))
      addSource(this.parsedInventory.protocols)
      addSource(this.parsedInventory.enabledProtocols)
      addSource(this.parsedInventory.nfsGaneshaRuntime)
      addSource(this.parsedInventory.nfsGaneshaExports, this.nfsProtocolModeFromObject(this.parsedInventory.nfsGaneshaRuntime))
      return items
    },
    nfsRuntimeProtocolMode () {
      const explicitSources = [
        this.storageService.protocols,
        this.storageService.enabledProtocols,
        this.storageService.protocol,
        this.parsedHealth.nfsGanesha,
        this.parsedInventory.nfsGaneshaRuntime
      ]
      for (const source of explicitSources) {
        const values = Array.isArray(source) ? source : (source ? [source] : [])
        for (const item of values) {
          const protocol = String(item?.protocol || item?.service || item?.name || item?.type || '').trim().toUpperCase()
          if (protocol && protocol !== 'NFS') {
            continue
          }
          const mode = this.nfsProtocolModeFromObject(item)
          if (mode) {
            return mode
          }
        }
      }
      return this.nfsRuntimeProtocolEntries()[0]?.protocolMode || 'V4_ONLY'
    },
    nfsRuntimePort () {
      return this.nfsRuntimeProtocolEntries()[0]?.port || this.defaultProtocolPort('NFS')
    },
    nfsRuntimeEndpointSummary () {
      const entries = this.nfsRuntimeProtocolEntries()
      if (!entries.length) {
        return ''
      }
      const endpoints = []
      const add = (ip, port) => {
        if (!ip) return
        const normalizedIp = String(ip).trim()
        if (!normalizedIp || this.isWildcardListenIp(normalizedIp)) return
        const value = `${normalizedIp}:${port || this.defaultProtocolPort('NFS')}`
        if (!endpoints.includes(value)) endpoints.push(value)
      }
      entries.forEach(entry => {
        if (this.isWildcardListenIp(entry.listenIp)) {
          this.serviceEndpoints.forEach(ip => add(ip, entry.port))
        } else {
          add(entry.listenIp, entry.port)
        }
      })
      return endpoints.join(', ')
    },
    nfsExportProtocolMode (share, config = null) {
      const parsedConfig = config || this.parseStorageConfig(share?.config)
      const mode = String(share?.protocolmode || share?.protocolMode || parsedConfig.protocolMode || parsedConfig.protocolmode || 'V4_ONLY').trim().toUpperCase()
      return mode === 'V3V4_DUAL' ? 'V3V4_DUAL' : 'V4_ONLY'
    },
    nfsExportEndpointDetails (share) {
      const protocolMode = this.nfsExportProtocolMode(share)
      const ports = this.nfsExportListenerPorts(share)
      return ports.flatMap(port => this.nfsExportEndpointIps(share).map(ip => ({
        listenIp: ip,
        port,
        protocolMode
      })))
    },
    nfsExportEndpointIps (share) {
      return this.serviceEndpoints
    },
    nfsExportEndpointMode (share, config = null, rawListenIps = null) {
      const parsedConfig = config || this.parseStorageConfig(share?.config)
      const rawMode = share?.endpointmode ?? share?.endpointMode ?? parsedConfig.endpointMode ?? parsedConfig.endpointmode
      const mode = String(rawMode || '').trim().toUpperCase()
      if (mode === 'ALL' || mode === 'SELECTED' || mode === 'LISTENER_GROUP') {
        return mode
      }
      const listenIps = rawListenIps || this.normalizeEndpointIps(share?.listenips ?? share?.listenIps ?? parsedConfig.listenIps ?? parsedConfig.listenips)
      return listenIps.length ? 'SELECTED' : 'ALL'
    },
    formatNfsExportEndpoints (share, port = 2049) {
      if (this.isNfsRuntimeDualMode) {
        return this.nfsServiceWideEndpointSummary
      }
      const values = this.nfsExportEndpointDetails(share).map(item => `${item.listenIp}:${item.port || port}`)
      return values.length ? values.join(', ') : '-'
    },
    formatNfsClientMountRoots (share, name) {
      const values = this.nfsExportEndpointIps(share).map(ip => `${ip}:/${name}`)
      return values.length ? values.join(', ') : `<${this.$t('label.storage.service.endpoint.ip.placeholder')}>:/${name}`
    },
    selectedNfsExportListenIps () {
      if (this.forms.nfsExport.endpointmode !== 'SELECTED') {
        return []
      }
      return this.normalizeEndpointIps(this.forms.nfsExport.listenips)
    },
    nfsExportImportMode () {
      if (this.forms.nfsExport.volumemode === 'NEW') {
        return 'FORMAT_EMPTY'
      }
      if (this.forms.nfsExport.volumemode === 'EXISTING') {
        return 'MOUNT_EXISTING'
      }
      return 'FORMAT_IF_EMPTY'
    },
    smbShareImportMode () {
      if (this.forms.smbShare.volumemode === 'NEW') {
        return 'FORMAT_EMPTY'
      }
      if (this.forms.smbShare.volumemode === 'EXISTING') {
        return 'MOUNT_EXISTING'
      }
      return 'FORMAT_IF_EMPTY'
    },
    nextNfsExportName () {
      let index = this.storageService.nfsExports.length + 1
      const used = new Set(this.storageService.nfsExports.map(share => this.clientVisibleName(share.name || share.exportname, '').toLowerCase()).filter(Boolean))
      let name = `nfs${String(index).padStart(2, '0')}`
      while (used.has(name.toLowerCase())) {
        index += 1
        name = `nfs${String(index).padStart(2, '0')}`
      }
      return name
    },
    defaultNfsExportPath (name) {
      const safeName = String(name || 'nfs01').trim().replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'nfs01'
      return `/export/${safeName}`
    },
    isValidNfsExportName (name) {
      const value = String(name || '').trim()
      return !!value && value !== '.' && value !== '..' && /^[A-Za-z0-9._-]+$/.test(value)
    },
    validateNfsExportNameAndPath () {
      const name = String(this.forms.nfsExport.name || '').trim()
      if (!this.isValidNfsExportName(name)) {
        this.$message.error(this.$t('message.storage.service.nfs.name.invalid'))
        return false
      }
      const expectedPath = `/export/${name}`
      const path = String(this.forms.nfsExport.path || '').trim().replace(/\/+$/g, '')
      if (path !== expectedPath) {
        this.$message.error(this.$t('message.storage.service.nfs.path.must.match.name', { path: expectedPath }))
        return false
      }
      return true
    },
    syncNfsExportPathToCurrentVolume () {
      if (this.actionModal.type === 'editNfsExport' || this.forms.nfsExport.volumemode !== 'CURRENT' || !this.selectedCurrentBackingVolume) {
        return
      }
      const name = this.forms.nfsExport.name || this.nextNfsExportName()
      const nextPath = this.defaultNfsExportPath(name)
      const defaultPath = this.defaultNfsExportPath(name)
      const currentPath = this.forms.nfsExport.path || ''
      if (!currentPath || currentPath === defaultPath || currentPath.endsWith(`/${name}`)) {
        this.forms.nfsExport.path = nextPath
      }
    },
    capacityBytesToInput (bytes) {
      const value = Number(bytes)
      if (!value || value < 0) {
        return { amount: null, unit: 'GiB' }
      }
      const units = [...this.capacityUnits].reverse()
      const exact = units.find(unit => unit.multiplier > 0 && value >= unit.multiplier && value % unit.multiplier === 0)
      const unit = exact || this.capacityUnits.find(item => item.value === 'GiB') || this.capacityUnits[0]
      const amount = unit.multiplier ? value / unit.multiplier : value
      return { amount, unit: unit.value }
    },
    defaultIscsiListenerPort () {
      const options = this.iscsiListenerGroupOptions || []
      return options.length ? options[0].value : 3260
    },
    formatIscsiListenerGroupEndpoints (ports) {
      const normalized = this.normalizeListenerPorts(ports)
      if (!normalized.length) {
        return '-'
      }
      return this.formatBlockProtocolListenerGroupEndpoints('ISCSI', normalized)
    },
    defaultNvmeListenerPort () {
      const options = this.nvmeListenerGroupOptions || []
      return options.length ? options[0].value : 4420
    },
    formatNvmeListenerGroupEndpoints (ports) {
      return this.nvmeListenerGroupSummary(ports).effectiveEndpoints
    },
    formatNvmeListenerGroupLabel (ports) {
      return this.nvmeListenerGroupSummary(ports).listenerGroupLabel
    },
    nvmeListenerGroupSummary (ports) {
      const normalized = this.normalizeListenerPorts(ports)
      if (!normalized.length) {
        return {
          listenerGroupLabel: '-',
          effectiveEndpoints: '-',
          scopeLabel: '-'
        }
      }
      const entries = this.protocolListenerEntries('NVME_OF')
      const groups = normalized.map(port => {
        const matching = entries.filter(item => Number(item.port) === Number(port))
        const wildcard = matching.some(item => this.isWildcardListenIp(item.listenIp)) || !matching.length
        const dedicatedIps = matching
          .map(item => item.listenIp)
          .filter(ip => ip && !this.isWildcardListenIp(ip))
          .filter((ip, index, values) => values.indexOf(ip) === index)
        const scopeLabel = wildcard
          ? this.$t('label.storage.service.nvme.wildcard.listener')
          : (dedicatedIps.length ? dedicatedIps.join(', ') : this.$t('label.storage.service.nvme.dedicated.listener'))
        const endpoints = this.formatBlockProtocolListenerGroupEndpoints('NVME_OF', [port])
        return {
          port,
          scopeLabel,
          label: `${this.$t('label.port')} ${port} / ${scopeLabel}`,
          endpoints
        }
      })
      const endpointValues = []
      groups.forEach(group => {
        String(group.endpoints || '').split(',').map(item => item.trim()).filter(Boolean).forEach(endpoint => {
          if (endpoint !== '-' && !endpointValues.includes(endpoint)) {
            endpointValues.push(endpoint)
          }
        })
      })
      return {
        listenerGroupLabel: groups.map(group => group.label).join(', '),
        effectiveEndpoints: endpointValues.length ? endpointValues.join(', ') : '-',
        scopeLabel: groups.map(group => group.scopeLabel).join(', ')
      }
    },
    selectedIscsiListenerPorts () {
      return this.normalizeListenerPorts(this.forms.iscsiTarget.listenerports)
    },
    selectedNvmeListenerPorts () {
      return this.normalizeListenerPorts(this.forms.nvmeNamespace.listenerports)
    },
    resetNvmeSubsystemForm () {
      this.forms.nvmeSubsystem = {
        id: '',
        subsystemnqn: `nqn.2026-06.local.storage:${this.resource.name || 'subsystem'}`,
        allowanyhost: false,
        engine: 'KERNEL_NVMET',
        transport: 'tcp'
      }
    },
    populateNvmeSubsystemForm (record) {
      const raw = record?.raw || record || {}
      const config = this.parseStorageConfig(raw.config || raw.configjson || raw.configJson)
      this.forms.nvmeSubsystem = {
        id: raw.id || record?.id || '',
        subsystemnqn: raw.targetname || raw.targetName || raw.subsystemnqn || record?.targetName || '',
        allowanyhost: this.boolValue(raw.allowanyhost ?? raw.allowAnyHost ?? config.allowAnyHost ?? config.allowanyhost),
        engine: raw.engine || config.engine || 'KERNEL_NVMET',
        transport: raw.transport || config.transport || 'tcp'
      }
    },
    resetIscsiTargetForm () {
      this.forms.iscsiTarget = {
        id: '',
        targetname: `iqn.2026-05.local.storage:${this.resource.name || 'target'}-${Date.now().toString().slice(-4)}`,
        lun: '0',
        volumeid: this.defaultCurrentIscsiBlockVolumeId(),
        volumemode: this.currentIscsiBlockVolumes.length ? 'CURRENT' : 'EXISTING',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultIscsiNewVolumeStorageId(),
        newvolumesize: null,
        backingpath: '',
        endpointmode: 'LISTENER_GROUP',
        listenerports: [this.defaultIscsiListenerPort()]
      }
    },
    populateIscsiTargetForm (record) {
      const raw = record?.raw || record || {}
      const config = this.parseStorageConfig(raw.config)
      this.forms.iscsiTarget = {
        id: raw.id || record?.id || '',
        targetname: raw.targetname || raw.targetName || record?.targetName || '',
        lun: raw.lunornamespace || raw.lunOrNamespace || raw.lun || record?.lun || '0',
        volumeid: raw.volumeid || raw.volumeId || '',
        volumemode: 'EXISTING',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultIscsiNewVolumeStorageId(),
        newvolumesize: null,
        backingpath: raw.backingpath || raw.backingPath || config.backingPath || '',
        endpointmode: raw.endpointmode || raw.endpointMode || config.endpointMode || 'LISTENER_GROUP',
        listenerports: this.normalizeListenerPorts(raw.listenerports || raw.listenerPorts || config.listenerGroupPorts || config.listenerports || this.defaultIscsiListenerPort())
      }
    },
    resetIscsiAclForm () {
      this.forms.iscsiAcl = {
        targetid: this.storageService.iscsiTargets[0]?.id || '',
        initiatoriqn: '',
        permission: 'READ_WRITE',
        chapenabled: false,
        chapusername: '',
        chapsecret: '',
        mutualchapenabled: false,
        mutualchapusername: '',
        mutualchapsecret: ''
      }
    },
    populateIscsiAclForm (record) {
      const raw = record?.raw || record || {}
      const config = this.parseStorageConfig(raw.config)
      this.forms.iscsiAcl = {
        id: raw.id || record?.id || '',
        targetid: raw.resourceid || raw.resourceId || raw.targetid || raw.targetId || '',
        initiatoriqn: raw.principal || raw.initiatoriqn || record?.principal || '',
        permission: raw.permission || 'READ_WRITE',
        chapenabled: raw.chapenabled ?? raw.chapEnabled ?? config.chapEnabled ?? false,
        chapusername: raw.chapusername || raw.chapUsername || config.chapUsername || '',
        chapsecret: '',
        mutualchapenabled: raw.mutualchapenabled ?? raw.mutualChapEnabled ?? config.mutualChapEnabled ?? false,
        mutualchapusername: raw.mutualchapusername || raw.mutualChapUsername || config.mutualChapUsername || '',
        mutualchapsecret: ''
      }
    },
    resetNvmeNamespaceForm () {
      this.forms.nvmeNamespace = {
        id: '',
        subsystemid: this.nvmeSubsystemTargets[0]?.id || '',
        namespaceid: '1',
        volumeid: this.currentNvmeBlockVolumes.length === 1 ? this.currentNvmeBlockVolumes[0].id : '',
        volumemode: this.currentNvmeBlockVolumes.length ? 'CURRENT' : 'EXISTING',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultNvmeNewVolumeStorageId(),
        newvolumesize: null,
        backingpath: '',
        listenerports: [this.defaultNvmeListenerPort()]
      }
    },
    populateNvmeNamespaceForm (record) {
      const raw = record?.raw || record || {}
      const config = this.parseStorageConfig(raw.config)
      const subsystem = this.nvmeSubsystemTargets.find(item => (item.targetname || item.targetName) === (raw.targetname || raw.targetName || record?.targetName))
      this.forms.nvmeNamespace = {
        id: raw.id || record?.id || '',
        subsystemid: subsystem?.id || raw.subsystemid || raw.subsystemId || '',
        namespaceid: raw.lunornamespace || raw.lunOrNamespace || raw.namespaceid || raw.namespaceId || record?.namespace || '1',
        volumeid: raw.volumeid || raw.volumeId || '',
        volumemode: 'EXISTING',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultNvmeNewVolumeStorageId(),
        newvolumesize: null,
        backingpath: raw.backingpath || raw.backingPath || config.backingPath || '',
        listenerports: this.normalizeListenerPorts(raw.listenerports || raw.listenerPorts || config.listenerGroupPorts || config.listenerports || this.defaultNvmeListenerPort())
      }
    },
    resetNvmeHostAclForm () {
      const firstExplicitSubsystem = this.nvmeSubsystemTargets.find(target => !this.nvmeSubsystemAllowAnyHost(target)) || null
      this.forms.nvmeHostAcl = {
        id: '',
        subsystemid: firstExplicitSubsystem?.id || '',
        hostnqn: '',
        dhchapenabled: false,
        dhchapkey: '',
        dhchapctrlenabled: false,
        dhchapctrlkey: ''
      }
    },
    populateNvmeHostAclForm (record) {
      const raw = record?.raw || record || {}
      const config = this.parseStorageConfig(raw.config || raw.configjson || raw.configJson)
      const target = this.blockTargetForAcl(raw, this.storageService.nvmeSubsystems) || this.nvmeSubsystemTargets.find(item => (item.targetname || item.targetName) === (record?.targetName || raw.targetname || raw.targetName))
      this.forms.nvmeHostAcl = {
        id: raw.id || record?.id || '',
        subsystemid: target?.id || raw.resourceid || raw.resourceId || '',
        hostnqn: raw.principal || raw.hostnqn || raw.hostNqn || record?.principal || '',
        dhchapenabled: raw.dhchapenabled ?? raw.dhChapEnabled ?? config.dhChapEnabled ?? false,
        dhchapkey: '',
        dhchapctrlenabled: raw.dhchapctrlenabled ?? raw.dhChapCtrlEnabled ?? config.dhChapCtrlEnabled ?? false,
        dhchapctrlkey: ''
      }
    },
    resetNfsExportForm () {
      Object.assign(this.forms.nfsExport, {
        name: '',
        path: '',
        volumeid: this.defaultCurrentBackingVolumeId(),
        volumemode: 'CURRENT',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultNewVolumeStorageId(),
        newvolumesize: null,
        filesystem: 'xfs',
        relativepath: '',
        createdirectory: true,
        quotaamount: null,
        quotaunit: 'GiB',
        protocolmode: this.nfsRuntimeProtocolMode(),
        endpointmode: 'LISTENER_GROUP',
        listenips: [],
        listenerports: [this.defaultNfsListenerPort()],
        readonly: false,
        rootsquash: true,
        allsquash: false,
        anonuid: 65534,
        anongid: 65534,
        owneruid: 65534,
        ownergid: 65534,
        mode: '0775',
        recursivepermission: false,
        sync: true,
        secure: false
      })
    },
    resetNfsAclForm () {
      Object.assign(this.forms.nfsAcl, {
        exportid: '',
        principaltype: 'CIDR',
        principal: '',
        principals: [],
        permission: 'READ_WRITE',
        rootsquash: true,
        allsquash: false,
        anonuid: null,
        anongid: null,
        sync: true,
        secure: false
      })
    },
    nextSmbShareName () {
      let index = this.storageService.smbShares.length + 1
      const used = new Set(this.storageService.smbShares.map(share => this.clientVisibleName(share.name || share.sharename, '').toLowerCase()).filter(Boolean))
      let name = `smb${String(index).padStart(2, '0')}`
      while (used.has(name.toLowerCase())) {
        index += 1
        name = `smb${String(index).padStart(2, '0')}`
      }
      return name
    },
    defaultSmbSharePath (name) {
      const safeName = String(name || 'smb01').trim().replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'smb01'
      return `/export/${safeName}`
    },
    resetSmbShareForm () {
      const name = this.nextSmbShareName()
      Object.assign(this.forms.smbShare, {
        name,
        path: this.defaultSmbSharePath(name),
        volumeid: this.defaultCurrentBackingVolumeId(),
        volumemode: 'CURRENT',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultSmbNewVolumeStorageId(),
        newvolumesize: null,
        filesystem: 'xfs',
        quotaamount: null,
        quotaunit: 'GiB',
        readonly: false,
        browseable: true,
        guestok: false,
        createdirectory: true,
        crossprotocol: false,
        directorymode: '0770'
      })
    },
    populateSmbShareForm (record) {
      const share = record?.raw || record || {}
      const config = this.parseStorageConfig(share.config || share.configjson || share.configJson)
      const quota = this.capacityBytesToInput(share.quotabytes || share.quotaBytes || share.capacitybytes || share.sizebytes)
      const volumeId = share.volumeid || share.volumeId || ''
      const currentVolume = this.currentBackingVolumes.find(volume => String(volume.id) === String(volumeId))
      Object.assign(this.forms.smbShare, {
        name: this.clientVisibleName(share.name || share.sharename, ''),
        path: share.path || share.mountpath || share.backingpath || '',
        volumeid: volumeId,
        volumemode: currentVolume || !volumeId ? 'CURRENT' : 'EXISTING',
        newvolumename: '',
        diskofferingid: '',
        storageid: this.defaultSmbNewVolumeStorageId(),
        newvolumesize: null,
        filesystem: share.filesystem || share.fsType || 'xfs',
        quotaamount: quota.amount,
        quotaunit: quota.unit,
        readonly: this.boolValue(share.readonly ?? share.readOnly ?? config.readOnly),
        browseable: this.boolValue(share.browseable ?? config.browseable),
        guestok: this.boolValue(share.guestok ?? share.guestOk ?? config.guestOk),
        createdirectory: config.createDirectory === undefined && config.createdirectory === undefined ? true : this.boolValue(config.createDirectory ?? config.createdirectory),
        crossprotocol: this.boolValue(config.crossProtocol ?? config.crossprotocol),
        directorymode: config.directoryMode || config.directorymode || '0770'
      })
    },
    resetSmbAclForm () {
      Object.assign(this.forms.smbAcl, {
        id: '',
        shareid: this.storageService.smbShares[0]?.id || '',
        principaltype: String(this.smbDomainState || '').toUpperCase() === 'JOINED' ? 'AD_USER' : 'LOCAL_USER',
        principal: '',
        permission: 'READ_WRITE',
        password: ''
      })
    },
    populateSmbAclForm (record) {
      const acl = record?.raw || record || {}
      Object.assign(this.forms.smbAcl, {
        id: acl.id || '',
        shareid: acl.resourceid || acl.resourceId || acl.shareid || acl.shareId || this.smbShareForAcl(acl)?.id || '',
        principaltype: acl.principaltype || acl.principalType || 'LOCAL_USER',
        principal: acl.principal || acl.username || acl.account || '',
        permission: acl.permission || acl.access || 'READ_WRITE',
        password: ''
      })
    },
    populateNfsExportForm (record) {
      const share = record?.raw || record || {}
      const config = this.effectiveNfsExportConfig(this.parseStorageConfig(share.config || share.configjson || share.configJson))
      const quota = this.capacityBytesToInput(share.quotabytes || share.quotaBytes || share.capacitybytes || share.sizebytes)
      const rawListenIps = this.normalizeEndpointIps(share.listenips ?? share.listenIps ?? config.listenIps ?? config.listenips)
      const endpointMode = this.nfsExportEndpointMode(share, config, rawListenIps)
      const listenerPorts = this.nfsExportListenerPorts(share, config)
      const volumeId = share.volumeid || share.volumeId || ''
      const currentVolume = this.currentBackingVolumes.find(volume => String(volume.id) === String(volumeId))
      const protocolMode = this.nfsRuntimeProtocolMode()
      Object.assign(this.forms.nfsExport, {
        name: this.clientVisibleName(share.name || share.exportname, ''),
        path: share.path || share.mountpath || share.backingpath || '',
        volumeid: volumeId,
        volumemode: currentVolume || !volumeId ? 'CURRENT' : 'EXISTING',
        newvolumename: '',
        diskofferingid: '',
        newvolumesize: null,
        filesystem: share.filesystem || share.fsType || 'xfs',
        relativepath: '',
        createdirectory: config.createDirectory === undefined && config.createdirectory === undefined ? true : this.boolValue(config.createDirectory ?? config.createdirectory),
        quotaamount: quota.amount,
        quotaunit: quota.unit,
        protocolmode: protocolMode,
        endpointmode: endpointMode,
        listenips: endpointMode === 'SELECTED' ? rawListenIps : [],
        listenerports: listenerPorts,
        readonly: this.boolValue(config.readOnly ?? config.readonly),
        rootsquash: this.boolValue(config.rootSquash ?? config.rootsquash),
        allsquash: this.boolValue(config.allSquash ?? config.allsquash),
        anonuid: config.anonUid ?? config.anonuid ?? null,
        anongid: config.anonGid ?? config.anongid ?? null,
        owneruid: config.ownerUid ?? config.owneruid ?? null,
        ownergid: config.ownerGid ?? config.ownergid ?? null,
        mode: config.mode || '',
        recursivepermission: this.boolValue(config.recursivePermission ?? config.recursivepermission),
        sync: this.boolValue(config.sync),
        secure: this.boolValue(config.secure)
      })
    },
    populateNfsAclForm (record) {
      const acl = record?.raw || record || {}
      const config = this.parseStorageConfig(acl.config || acl.configjson || acl.configJson)
      Object.assign(this.forms.nfsAcl, {
        exportid: acl.resourceid || acl.resourceId || acl.exportid || acl.exportId || this.nfsShareForAcl(acl)?.id || '',
        principaltype: acl.principaltype || acl.principalType || 'CIDR',
        principal: acl.principal || acl.cidr || acl.client || '',
        principals: [acl.principal || acl.cidr || acl.client].filter(Boolean),
        permission: acl.permission || acl.access || 'READ_WRITE',
        rootsquash: this.boolValue(acl.rootsquash ?? acl.rootSquash ?? config.rootSquash),
        allsquash: this.boolValue(acl.allsquash ?? acl.allSquash ?? config.allSquash),
        anonuid: config.anonUid ?? config.anonuid ?? null,
        anongid: config.anonGid ?? config.anongid ?? null,
        sync: this.boolValue(acl.sync ?? config.sync),
        secure: this.boolValue(acl.secure ?? config.secure)
      })
    },
    nfsAclPrincipals () {
      let values
      if (this.actionModal.type === 'editNfsAcl') {
        values = String(this.forms.nfsAcl.principal || '').split(',')
      } else if (Array.isArray(this.forms.nfsAcl.principals) && this.forms.nfsAcl.principals.length) {
        values = this.forms.nfsAcl.principals
      } else {
        values = String(this.forms.nfsAcl.principal || '').split(',')
      }
      const seen = new Set()
      return values.map(value => String(value || '').trim())
        .filter(value => value && !seen.has(value) && seen.add(value))
    },
    openActionModal (type, context = null) {
      this.actionModal.type = type
      this.actionModal.context = context
      this.actionModal.visible = true
      if (type === 'enableProtocol' && context?.protocol) {
        this.forms.enableProtocol.protocol = context.protocol
        this.forms.enableProtocol.protocolmode = String(context.protocol || '').toUpperCase() === 'NFS' ? this.nfsRuntimeProtocolMode() : 'V4_ONLY'
        this.forms.enableProtocol.port = String(context.protocol || '').toUpperCase() === 'NFS' ? this.nfsRuntimePort() : this.defaultProtocolPort(context.protocol)
        if (String(context.protocol || '').toUpperCase() === 'NFS' && this.forms.enableProtocol.protocolmode === 'V3V4_DUAL') {
          this.forms.enableProtocol.listenipmode = 'NEW'
          this.forms.enableProtocol.listenip = ''
          this.forms.enableProtocol.port = 2049
        }
      }
      if (type === 'nfsExport') {
        this.resetNfsExportForm()
        this.fetchDiskOfferings()
        this.fetchStoragePools()
        if (!this.forms.nfsExport.name) {
          this.forms.nfsExport.name = this.nextNfsExportName()
        }
        if (!this.forms.nfsExport.path) {
          this.forms.nfsExport.path = this.defaultNfsExportPath(this.forms.nfsExport.name)
        }
        this.forms.nfsExport.endpointmode = this.isNfsRuntimeDualMode ? 'ALL' : 'LISTENER_GROUP'
        this.forms.nfsExport.listenips = []
        this.forms.nfsExport.listenerports = [this.defaultNfsListenerPort()]
        this.applyNfsWritableDefaults()
      }
      if (type === 'editNfsExport') {
        this.populateNfsExportForm(context)
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'nfsAcl') {
        this.resetNfsAclForm()
        if (context?.exportid) {
          this.forms.nfsAcl.exportid = context.exportid
        } else if (this.storageService.nfsExports.length > 0) {
          this.forms.nfsAcl.exportid = this.storageService.nfsExports[0].id
        }
      }
      if (type === 'editNfsAcl') {
        this.populateNfsAclForm(context)
      }
      if (type === 'smbShare') {
        this.resetSmbShareForm()
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'editSmbShare') {
        this.populateSmbShareForm(context)
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'smbAcl') {
        this.resetSmbAclForm()
        if (context?.shareid) {
          this.forms.smbAcl.shareid = context.shareid
        }
      }
      if (type === 'editSmbAcl') {
        this.populateSmbAclForm(context)
      }
      if (type === 'adJoin' || type === 'adRejoin') {
        this.forms.adJoin = {
          domainname: this.smbDomainName !== '-' ? this.smbDomainName : '',
          username: '',
          password: '',
          organizationalunit: this.smbOrganizationalUnit !== '-' ? this.smbOrganizationalUnit : '',
          dnsservers: this.smbDnsServers !== '-' ? this.smbDnsServers : '',
          workgroup: this.smbWorkgroup !== '-' ? this.smbWorkgroup : ''
        }
      }
      if (type === 'adLeave') {
        this.forms.adLeave = {
          confirmation: '',
          username: '',
          password: ''
        }
      }
      if (type === 'iscsiTarget') {
        this.resetIscsiTargetForm()
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'editIscsiTarget') {
        this.populateIscsiTargetForm(context)
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'iscsiAcl') {
        this.resetIscsiAclForm()
        if (context?.targetid) {
          this.forms.iscsiAcl.targetid = context.targetid
        } else if (this.storageService.iscsiTargets.length > 0) {
          this.forms.iscsiAcl.targetid = this.storageService.iscsiTargets[0].id
        }
      }
      if (type === 'editIscsiAcl') {
        this.populateIscsiAclForm(context)
      }
      if (type === 'nvmeSubsystem') {
        this.resetNvmeSubsystemForm()
      }
      if (type === 'editNvmeSubsystem') {
        this.populateNvmeSubsystemForm(context)
      }
      if (type === 'nvmeNamespace') {
        this.resetNvmeNamespaceForm()
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'editNvmeNamespace') {
        this.populateNvmeNamespaceForm(context)
        this.fetchDiskOfferings()
        this.fetchStoragePools()
      }
      if (type === 'nvmeHostAcl') {
        this.resetNvmeHostAclForm()
        if (context?.subsystemid) {
          this.forms.nvmeHostAcl.subsystemid = context.subsystemid
        }
      }
      if (type === 'editNvmeHostAcl') {
        this.populateNvmeHostAclForm(context)
      }
      if (type === 'resizeShare' && context?.id) {
        this.forms.resizeShare.id = context.id
      }
      if (type === 'resizeBackingVolume') {
        this.populateResizeBackingVolumeForm(context)
      }
      if (type === 'detachBackingVolume' && context?.id) {
        this.forms.detachBackingVolume = {
          volumeid: context.id,
          confirmation: false
        }
      }
      if (type === 'disconnectSession' && context) {
        this.forms.disconnectSession = {
          protocol: context.protocol || context.service || '',
          peer: context.peer || '',
          local: context.local || '',
          sessionid: context.sessionId || '',
          force: true
        }
      }
      if (type === 'deleteEndpoint') {
        const firstEndpoint = this.removableServiceEndpoints[0] || {}
        this.forms.deleteEndpoint = {
          protocol: context?.protocol || 'NFS',
          listenip: firstEndpoint.ipaddress || '',
          confirmation: ''
        }
      }
    },
    openDeleteModal (resourceType, record) {
      const raw = record?.raw || record || {}
      const names = {
        protocol: record?.name || record?.protocol,
        nfsExport: record?.name || this.clientVisibleName(raw.name || raw.exportname, ''),
        nfsAcl: record?.principal || raw.principal || raw.cidr || raw.client,
        smbShare: record?.name || this.clientVisibleName(raw.name || raw.sharename, ''),
        smbAcl: record?.principal || raw.principal || raw.username || raw.account,
        iscsiTarget: record?.targetName || raw.targetname || raw.targetName,
        iscsiAcl: record?.principal || raw.principal || raw.initiatoriqn,
        protocolListener: `${record?.listenIp || raw.listenIp || raw.listenip || '0.0.0.0'}:${record?.port || raw.port || '-'}`,
        nvmeListener: `${record?.listenIp || raw.listenIp || raw.listenip || '0.0.0.0'}:${record?.port || raw.port || this.defaultNvmeListenerPort()}`,
        nvmeSubsystem: record?.targetName || raw.targetname || raw.targetName || raw.subsystemnqn,
        nvmeNamespace: `${record?.targetName || raw.targetname || raw.targetName || '-'} / ${record?.namespace || raw.lunornamespace || raw.lunOrNamespace || '1'}`,
        nvmeHostAcl: record?.principal || raw.principal || raw.hostnqn || raw.hostNqn
      }
      const commands = {
        protocol: 'deleteStorageServiceProtocol',
        nfsExport: 'deleteStorageNfsExport',
        nfsAcl: 'deleteStorageNfsAcl',
        smbShare: 'deleteStorageSmbShare',
        smbAcl: 'deleteStorageSmbAcl',
        iscsiTarget: 'deleteStorageIscsiTarget',
        iscsiAcl: 'deleteStorageIscsiAcl',
        protocolListener: 'deleteStorageServiceProtocol',
        nvmeListener: 'deleteStorageServiceProtocol',
        nvmeSubsystem: 'deleteStorageNvmeOfSubsystem',
        nvmeNamespace: 'deleteStorageNvmeOfNamespace',
        nvmeHostAcl: 'deleteStorageNvmeOfHostAcl'
      }
      this.forms.deleteConfirm = {
        resourceType,
        command: commands[resourceType],
        id: raw.id || record?.id || '',
        protocol: raw.protocol || record?.protocol || '',
        listenip: raw.listenIp || raw.listenip || record?.listenIp || '',
        port: raw.port || record?.port || '',
        confirmation: ''
      }
      this.actionModal.type = 'deleteConfirm'
      this.actionModal.context = {
        resourceType,
        name: names[resourceType] || raw.id || record?.id || '',
        raw
      }
      this.actionModal.visible = true
    },
    openDeleteConfirm (resourceType, record) {
      this.openDeleteModal(resourceType, record)
    },
    closeActionModal () {
      this.actionModal.visible = false
      this.actionModal.type = ''
      this.actionModal.context = null
      this.actionModal.loading = false
    },
    async submitActionModal () {
      const actions = {
        enableProtocol: this.enableProtocol,
        nfsExport: this.createNfsExport,
        editNfsExport: this.updateNfsExport,
        nfsAcl: this.createNfsAcl,
        editNfsAcl: this.updateNfsAcl,
        deleteConfirm: this.deleteStorageResource,
        deleteEndpoint: this.deleteStorageEndpoint,
        smbShare: this.createSmbShare,
        editSmbShare: this.updateSmbShare,
        smbAcl: this.createSmbAcl,
        editSmbAcl: this.updateSmbAcl,
        adJoin: this.joinAdDomain,
        adRejoin: this.joinAdDomain,
        adLeave: this.leaveAdDomain,
        iscsiTarget: this.createIscsiTarget,
        editIscsiTarget: this.updateIscsiTarget,
        iscsiAcl: this.createIscsiAcl,
        editIscsiAcl: this.updateIscsiAcl,
        nvmePrepare: this.prepareNvmeOf,
        nvmeSubsystem: this.createNvmeSubsystem,
        editNvmeSubsystem: this.updateNvmeSubsystem,
        nvmeNamespace: this.createNvmeNamespace,
        editNvmeNamespace: this.updateNvmeNamespace,
        nvmeHostAcl: this.createNvmeHostAcl,
        editNvmeHostAcl: this.updateNvmeHostAcl,
        attachVolume: this.attachExistingVolume,
        resizeShare: this.resizeFileShare,
        resizeBackingVolume: this.resizeBackingVolume,
        detachBackingVolume: this.detachBackingVolume,
        disconnectSession: this.disconnectSession
      }
      const action = actions[this.actionModal.type]
      if (!action) {
        this.closeActionModal()
        return
      }
      this.actionModal.loading = true
      await action.call(this)
      this.closeActionModal()
    },
    enableProtocol () {
      if (!this.validateListenIpSelection()) {
        return Promise.resolve()
      }
      return this.runStorageAction('enableProtocol', 'enableStorageServiceProtocol', {
        instanceid: this.storageService.instance.id,
        protocol: this.forms.enableProtocol.protocol,
        listenip: this.forms.enableProtocol.listenip,
        port: this.forms.enableProtocol.port,
        protocolmode: this.forms.enableProtocol.protocol === 'NFS' ? this.forms.enableProtocol.protocolmode : undefined
      }, this.$t('label.storage.service.enable.protocol'))
    },
    async createNfsExport () {
      if (!this.forms.nfsExport.name) {
        this.forms.nfsExport.name = this.nextNfsExportName()
      }
      if (!this.forms.nfsExport.path) {
        this.forms.nfsExport.path = this.defaultNfsExportPath(this.forms.nfsExport.name)
      }
      if (!this.validateNfsExportNameAndPath()) {
        return Promise.resolve()
      }
      const dualMode = this.forms.nfsExport.protocolmode === 'V3V4_DUAL' || this.isNfsRuntimeDualMode
      const listenerPorts = dualMode ? [2049] : this.selectedNfsListenerPorts()
      if (!dualMode && listenerPorts.length === 0) {
        this.$message.error(this.$t('message.storage.service.nfs.listener.group.required'))
        return Promise.resolve()
      }
      this.applyNfsWritableDefaults()
      const volumeId = await this.prepareNfsExportVolume()
      if (volumeId === false) {
        return Promise.resolve()
      }
      return this.runStorageAction('nfsExport', 'createStorageNfsExport', {
        instanceid: this.storageService.instance.id,
        name: this.forms.nfsExport.name,
        path: this.forms.nfsExport.path,
        createdirectory: this.forms.nfsExport.createdirectory,
        volumeid: volumeId,
        filesystem: this.forms.nfsExport.filesystem,
        protocolmode: this.forms.nfsExport.protocolmode,
        importmode: this.nfsExportImportMode(),
        quotabytes: this.toCapacityBytes(this.forms.nfsExport.quotaamount, this.forms.nfsExport.quotaunit),
        readonly: this.forms.nfsExport.readonly,
        rootsquash: this.forms.nfsExport.rootsquash,
        allsquash: this.forms.nfsExport.allsquash,
        anonuid: this.forms.nfsExport.anonuid,
        anongid: this.forms.nfsExport.anongid,
        owneruid: this.forms.nfsExport.owneruid,
        ownergid: this.forms.nfsExport.ownergid,
        mode: this.forms.nfsExport.mode,
        recursivepermission: this.forms.nfsExport.recursivepermission,
        sync: this.forms.nfsExport.sync,
        secure: this.forms.nfsExport.secure,
        endpointmode: dualMode ? undefined : 'LISTENER_GROUP',
        listenips: undefined,
        listenerports: listenerPorts.join(','),
        cleanupvolumeonfailure: this.forms.nfsExport.volumemode === 'NEW' && !!volumeId
      }, this.$t('label.storage.service.create.nfs.export'))
    },
    async prepareNfsExportVolume () {
      if (this.forms.nfsExport.volumemode === 'CURRENT') {
        if (!this.forms.nfsExport.volumeid) {
          this.$message.error(this.$t('message.storage.service.current.volume.required'))
          return false
        }
        return this.forms.nfsExport.volumeid
      }
      if (this.forms.nfsExport.volumemode === 'EXISTING') {
        if (!this.forms.nfsExport.volumeid) {
          this.$message.error(this.$t('message.storage.service.existing.volume.required'))
          return false
        }
        return this.forms.nfsExport.volumeid
      }
      if (!this.forms.nfsExport.storageid) {
        this.forms.nfsExport.storageid = this.defaultNewVolumeStorageId()
      }
      if (!this.forms.nfsExport.diskofferingid || !this.forms.nfsExport.storageid || !this.forms.nfsExport.newvolumesize) {
        this.$message.error(this.$t('message.storage.service.new.volume.required'))
        return false
      }
      const params = {
        name: this.forms.nfsExport.newvolumename || `${this.forms.nfsExport.name}-volume`,
        zoneid: this.resource.zoneid,
        diskofferingid: this.forms.nfsExport.diskofferingid,
        storageid: this.forms.nfsExport.storageid,
        size: this.forms.nfsExport.newvolumesize
      }
      const json = await postAPI('createVolume', this.cleanParams(params))
      const response = json.createvolumeresponse || {}
      const jobResult = response.jobid ? await this.waitStorageServiceJob(response.jobid, 'createVolume', 180) : null
      const jobVolume = jobResult?.jobresult?.volume || jobResult?.volume || {}
      const id = jobVolume.id || response.id || response.volume?.id
      if (!id) {
        this.$message.error(this.$t('message.storage.service.new.volume.create.failed'))
        return false
      }
      await this.waitVolumeAttachable(id)
      return id
    },
    createNfsAcl () {
      const principals = this.nfsAclPrincipals()
      if (!principals.length) {
        this.$message.error(this.$t('message.storage.service.nfs.acl.required'))
        return Promise.resolve()
      }
      return this.runStorageAction('nfsAcl', 'createStorageNfsAcl', {
        exportid: this.forms.nfsAcl.exportid,
        principaltype: this.forms.nfsAcl.principaltype,
        principal: principals[0],
        principals: principals.join(','),
        permission: this.forms.nfsAcl.permission,
        rootsquash: this.forms.nfsAcl.rootsquash,
        allsquash: this.forms.nfsAcl.allsquash,
        anonuid: this.forms.nfsAcl.anonuid,
        anongid: this.forms.nfsAcl.anongid,
        sync: this.forms.nfsAcl.sync,
        secure: this.forms.nfsAcl.secure
      }, this.$t('label.storage.service.create.nfs.acl'))
    },
    updateNfsExport () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      const dualMode = this.forms.nfsExport.protocolmode === 'V3V4_DUAL' || this.isNfsRuntimeDualMode
      const listenerPorts = dualMode ? [2049] : this.selectedNfsListenerPorts()
      if (!dualMode && listenerPorts.length === 0) {
        this.$message.error(this.$t('message.storage.service.nfs.listener.group.required'))
        return Promise.resolve()
      }
      if (!this.validateNfsExportNameAndPath()) {
        return Promise.resolve()
      }
      this.applyNfsWritableDefaults()
      return this.runStorageAction('editNfsExport', 'updateStorageNfsExport', {
        id: context.id || this.actionModal.context?.id,
        name: this.forms.nfsExport.name,
        path: this.forms.nfsExport.path,
        createdirectory: this.forms.nfsExport.createdirectory,
        volumeid: this.forms.nfsExport.volumeid,
        filesystem: this.forms.nfsExport.filesystem,
        protocolmode: this.forms.nfsExport.protocolmode,
        importmode: this.nfsExportImportMode(),
        quotabytes: this.toCapacityBytes(this.forms.nfsExport.quotaamount, this.forms.nfsExport.quotaunit),
        readonly: this.forms.nfsExport.readonly,
        rootsquash: this.forms.nfsExport.rootsquash,
        allsquash: this.forms.nfsExport.allsquash,
        anonuid: this.forms.nfsExport.anonuid,
        anongid: this.forms.nfsExport.anongid,
        owneruid: this.forms.nfsExport.owneruid,
        ownergid: this.forms.nfsExport.ownergid,
        mode: this.forms.nfsExport.mode,
        recursivepermission: this.forms.nfsExport.recursivepermission,
        sync: this.forms.nfsExport.sync,
        secure: this.forms.nfsExport.secure,
        endpointmode: dualMode ? undefined : 'LISTENER_GROUP',
        listenips: undefined,
        listenerports: listenerPorts.join(',')
      }, this.$t('label.storage.service.update.nfs.export'))
    },
    updateNfsAcl () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      const principals = this.nfsAclPrincipals()
      if (principals.length !== 1) {
        this.$message.error(this.$t('message.storage.service.nfs.acl.edit.single.required'))
        return Promise.resolve()
      }
      return this.runStorageAction('editNfsAcl', 'updateStorageNfsAcl', {
        id: context.id || this.actionModal.context?.id,
        principal: principals[0],
        permission: this.forms.nfsAcl.permission,
        rootsquash: this.forms.nfsAcl.rootsquash,
        allsquash: this.forms.nfsAcl.allsquash,
        anonuid: this.forms.nfsAcl.anonuid,
        anongid: this.forms.nfsAcl.anongid,
        sync: this.forms.nfsAcl.sync,
        secure: this.forms.nfsAcl.secure
      }, this.$t('label.storage.service.update.nfs.acl'))
    },
    deleteStorageResource () {
      if (!this.deleteConfirmationMatched) {
        this.$message.error(this.$t('message.storage.service.delete.confirm.input'))
        return Promise.resolve()
      }
      const type = this.forms.deleteConfirm.resourceType
      const command = this.forms.deleteConfirm.command
      const key = (type === 'protocol' || type === 'protocolListener' || type === 'nvmeListener') ? 'enableProtocol' : type
      let params = { id: this.forms.deleteConfirm.id }
      if (type === 'protocol') {
        params = { instanceid: this.storageService.instance.id, protocol: this.forms.deleteConfirm.protocol }
      } else if (type === 'protocolListener' || type === 'nvmeListener') {
        params = {
          instanceid: this.storageService.instance.id,
          protocol: type === 'nvmeListener' ? 'NVME_OF' : this.forms.deleteConfirm.protocol,
          listenip: this.forms.deleteConfirm.listenip,
          port: this.forms.deleteConfirm.port
        }
      }
      return this.runStorageAction(key, command, params, this.$t('label.storage.service.delete.confirm'))
    },
    deleteStorageEndpoint () {
      if (!this.deleteEndpointConfirmationMatched) {
        this.$message.error(this.$t('message.storage.service.delete.endpoint.confirm.input'))
        return Promise.resolve()
      }
      return this.runStorageAction('deleteEndpoint', 'deleteStorageServiceProtocol', {
        instanceid: this.storageService.instance.id,
        protocol: this.forms.deleteEndpoint.protocol,
        listenip: this.forms.deleteEndpoint.listenip,
        port: this.forms.deleteEndpoint.port
      }, this.$t('label.storage.service.delete.endpoint'))
    },
    async prepareSmbShareVolume () {
      if (this.forms.smbShare.volumemode === 'CURRENT') {
        if (!this.forms.smbShare.volumeid) {
          this.$message.error(this.$t('message.storage.service.current.volume.required'))
          return false
        }
        return this.forms.smbShare.volumeid
      }
      if (this.forms.smbShare.volumemode === 'EXISTING') {
        if (!this.forms.smbShare.volumeid) {
          this.$message.error(this.$t('message.storage.service.existing.volume.required'))
          return false
        }
        return this.forms.smbShare.volumeid
      }
      if (!this.forms.smbShare.storageid) {
        this.forms.smbShare.storageid = this.defaultSmbNewVolumeStorageId()
      }
      if (!this.forms.smbShare.diskofferingid || !this.forms.smbShare.storageid || !this.forms.smbShare.newvolumesize) {
        this.$message.error(this.$t('message.storage.service.new.volume.required'))
        return false
      }
      const params = {
        name: this.forms.smbShare.newvolumename || `${this.forms.smbShare.name}-volume`,
        zoneid: this.resource.zoneid,
        diskofferingid: this.forms.smbShare.diskofferingid,
        storageid: this.forms.smbShare.storageid,
        size: this.forms.smbShare.newvolumesize
      }
      const json = await postAPI('createVolume', this.cleanParams(params))
      const response = json.createvolumeresponse || {}
      const jobResult = response.jobid ? await this.waitStorageServiceJob(response.jobid, 'createVolume', 180) : null
      const jobVolume = jobResult?.jobresult?.volume || jobResult?.volume || {}
      const id = jobVolume.id || response.id || response.volume?.id
      if (!id) {
        this.$message.error(this.$t('message.storage.service.new.volume.create.failed'))
        return false
      }
      await this.waitVolumeAttachable(id)
      return id
    },
    async createSmbShare () {
      const volumeId = await this.prepareSmbShareVolume()
      if (volumeId === false) {
        return Promise.resolve()
      }
      return this.runStorageAction('smbShare', 'createStorageSmbShare', {
        instanceid: this.storageService.instance.id,
        name: this.forms.smbShare.name,
        path: this.forms.smbShare.path,
        volumeid: volumeId,
        filesystem: this.forms.smbShare.filesystem,
        importmode: this.smbShareImportMode(),
        quotabytes: this.toCapacityBytes(this.forms.smbShare.quotaamount, this.forms.smbShare.quotaunit),
        readonly: this.forms.smbShare.readonly,
        browseable: this.forms.smbShare.browseable,
        guestok: this.forms.smbShare.guestok,
        createdirectory: this.forms.smbShare.createdirectory,
        crossprotocol: this.forms.smbShare.crossprotocol,
        directorymode: this.forms.smbShare.directorymode,
        cleanupvolumeonfailure: this.forms.smbShare.volumemode === 'NEW' && !!volumeId
      }, this.$t('label.storage.service.create.smb.share'))
    },
    updateSmbShare () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      return this.runStorageAction('editSmbShare', 'updateStorageSmbShare', {
        id: context.id || this.actionModal.context?.id,
        name: this.forms.smbShare.name,
        path: this.forms.smbShare.path,
        volumeid: this.forms.smbShare.volumeid,
        filesystem: this.forms.smbShare.filesystem,
        importmode: this.smbShareImportMode(),
        quotabytes: this.toCapacityBytes(this.forms.smbShare.quotaamount, this.forms.smbShare.quotaunit),
        readonly: this.forms.smbShare.readonly,
        browseable: this.forms.smbShare.browseable,
        guestok: this.forms.smbShare.guestok,
        createdirectory: this.forms.smbShare.createdirectory,
        crossprotocol: this.forms.smbShare.crossprotocol,
        directorymode: this.forms.smbShare.directorymode
      }, this.$t('label.storage.service.update.smb.share'))
    },
    createSmbAcl () {
      const result = this.runStorageAction('smbAcl', 'createStorageSmbAcl', this.forms.smbAcl, this.$t('label.storage.service.create.smb.acl'))
      this.forms.smbAcl.password = ''
      return result
    },
    updateSmbAcl () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      const result = this.runStorageAction('editSmbAcl', 'updateStorageSmbAcl', {
        id: context.id || this.forms.smbAcl.id,
        principal: this.forms.smbAcl.principal,
        permission: this.forms.smbAcl.permission,
        password: this.forms.smbAcl.password
      }, this.$t('label.storage.service.update.smb.acl'))
      this.forms.smbAcl.password = ''
      return result
    },
    joinAdDomain () {
      const key = this.actionModal.type === 'adRejoin' ? 'adRejoin' : 'adJoin'
      const result = this.runStorageAction(key, 'joinStorageServiceToAdDomain', {
        instanceid: this.storageService.instance.id,
        ...this.forms.adJoin
      }, this.$t(key === 'adRejoin' ? 'label.storage.service.rejoin.ad.domain' : 'label.storage.service.join.ad.domain'))
      this.forms.adJoin.password = ''
      return result
    },
    leaveAdDomain () {
      if (!this.adLeaveConfirmationMatched) {
        this.$message.error(this.$t('message.storage.service.ad.leave.confirm.input'))
        return Promise.resolve()
      }
      const result = this.runStorageAction('adLeave', 'leaveStorageServiceFromAdDomain', {
        instanceid: this.storageService.instance.id,
        username: this.forms.adLeave.username,
        password: this.forms.adLeave.password
      }, this.$t('label.storage.service.leave.ad.domain'))
      this.forms.adLeave.password = ''
      return result
    },
    async checkAdDomainStatus () {
      if (!this.storageService.instance || this.actionLoading.adStatus) {
        return
      }
      this.actionLoading.adStatus = true
      this.storageService.refreshing = true
      try {
        await Promise.all([
          this.fetchCollection('listStorageServiceDomainStatus', 'domains', 'storageidentitydomain'),
          this.fetchRuntime('listStorageServiceHealth', 'health'),
          this.fetchRuntime('listStorageServiceInventory', 'inventory'),
          this.fetchRuntime('listStorageServiceSessions', 'sessions')
        ])
        this.$message.success(this.$t('message.storage.service.ad.status.refreshed'))
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.storageService.refreshing = false
        this.actionLoading.adStatus = false
      }
    },
    async prepareIscsiTargetVolume () {
      if (this.forms.iscsiTarget.volumemode === 'CURRENT' || this.forms.iscsiTarget.volumemode === 'EXISTING') {
        if (!this.forms.iscsiTarget.volumeid) {
          this.$message.error(this.$t(this.forms.iscsiTarget.volumemode === 'CURRENT' ? 'message.storage.service.current.volume.required' : 'message.storage.service.existing.volume.required'))
          return false
        }
        return this.forms.iscsiTarget.volumeid
      }
      if (!this.forms.iscsiTarget.storageid) {
        this.forms.iscsiTarget.storageid = this.defaultIscsiNewVolumeStorageId()
      }
      if (!this.forms.iscsiTarget.diskofferingid || !this.forms.iscsiTarget.storageid || !this.forms.iscsiTarget.newvolumesize) {
        this.$message.error(this.$t('message.storage.service.new.volume.required'))
        return false
      }
      const params = {
        name: this.forms.iscsiTarget.newvolumename || `${this.forms.iscsiTarget.targetname || 'iscsi'}-volume`,
        zoneid: this.resource.zoneid,
        diskofferingid: this.forms.iscsiTarget.diskofferingid,
        storageid: this.forms.iscsiTarget.storageid,
        size: this.forms.iscsiTarget.newvolumesize
      }
      const json = await postAPI('createVolume', this.cleanParams(params))
      const response = json.createvolumeresponse || {}
      const jobResult = response.jobid ? await this.waitStorageServiceJob(response.jobid, 'createVolume', 180) : null
      const jobVolume = jobResult?.jobresult?.volume || jobResult?.volume || {}
      const id = jobVolume.id || response.id || response.volume?.id
      if (!id) {
        this.$message.error(this.$t('message.storage.service.new.volume.create.failed'))
        return false
      }
      await this.waitVolumeAttachable(id)
      return id
    },
    async createIscsiTarget () {
      const volumeId = await this.prepareIscsiTargetVolume()
      if (volumeId === false) {
        return Promise.resolve()
      }
      const listenerPorts = this.selectedIscsiListenerPorts()
      if (!listenerPorts.length) {
        this.$message.error(this.$t('message.storage.service.iscsi.listener.group.required'))
        return Promise.resolve()
      }
      return this.runStorageAction('iscsiTarget', 'createStorageIscsiTarget', {
        instanceid: this.storageService.instance.id,
        targetname: this.forms.iscsiTarget.targetname,
        lun: this.forms.iscsiTarget.lun,
        volumeid: volumeId,
        backingpath: this.forms.iscsiTarget.backingpath,
        endpointmode: 'LISTENER_GROUP',
        listenerports: listenerPorts.join(','),
        cleanupvolumeonfailure: this.forms.iscsiTarget.volumemode === 'NEW' && !!volumeId
      }, this.$t('label.storage.service.create.iscsi.target'))
    },
    updateIscsiTarget () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      const listenerPorts = this.selectedIscsiListenerPorts()
      if (!listenerPorts.length) {
        this.$message.error(this.$t('message.storage.service.iscsi.listener.group.required'))
        return Promise.resolve()
      }
      return this.runStorageAction('editIscsiTarget', 'updateStorageIscsiTarget', {
        id: context.id || this.forms.iscsiTarget.id,
        targetname: this.forms.iscsiTarget.targetname,
        lun: this.forms.iscsiTarget.lun,
        volumeid: this.forms.iscsiTarget.volumeid,
        backingpath: this.forms.iscsiTarget.backingpath,
        endpointmode: 'LISTENER_GROUP',
        listenerports: listenerPorts.join(',')
      }, this.$t('label.storage.service.update.iscsi.target'))
    },
    async prepareNvmeNamespaceVolume () {
      if (this.forms.nvmeNamespace.volumemode === 'CURRENT' || this.forms.nvmeNamespace.volumemode === 'EXISTING') {
        if (!this.forms.nvmeNamespace.volumeid) {
          this.$message.error(this.$t(this.forms.nvmeNamespace.volumemode === 'CURRENT' ? 'message.storage.service.current.volume.required' : 'message.storage.service.existing.volume.required'))
          return false
        }
        return this.forms.nvmeNamespace.volumeid
      }
      if (!this.forms.nvmeNamespace.storageid) {
        this.forms.nvmeNamespace.storageid = this.defaultNvmeNewVolumeStorageId()
      }
      if (!this.forms.nvmeNamespace.diskofferingid || !this.forms.nvmeNamespace.storageid || !this.forms.nvmeNamespace.newvolumesize) {
        this.$message.error(this.$t('message.storage.service.new.volume.required'))
        return false
      }
      const subsystem = this.nvmeSubsystemTargets.find(item => String(item.id) === String(this.forms.nvmeNamespace.subsystemid))
      const params = {
        name: this.forms.nvmeNamespace.newvolumename || `${(subsystem?.targetname || subsystem?.targetName || 'nvme').split(':').pop()}-ns${this.forms.nvmeNamespace.namespaceid || '1'}`,
        zoneid: this.resource.zoneid,
        diskofferingid: this.forms.nvmeNamespace.diskofferingid,
        storageid: this.forms.nvmeNamespace.storageid,
        size: this.forms.nvmeNamespace.newvolumesize
      }
      const json = await postAPI('createVolume', this.cleanParams(params))
      const response = json.createvolumeresponse || {}
      const jobResult = response.jobid ? await this.waitStorageServiceJob(response.jobid, 'createVolume', 180) : null
      const jobVolume = jobResult?.jobresult?.volume || jobResult?.volume || {}
      const id = jobVolume.id || response.id || response.volume?.id
      if (!id) {
        this.$message.error(this.$t('message.storage.service.new.volume.create.failed'))
        return false
      }
      await this.waitVolumeAttachable(id)
      return id
    },
    async createNvmeNamespace () {
      const volumeId = await this.prepareNvmeNamespaceVolume()
      if (volumeId === false) {
        return Promise.resolve()
      }
      const listenerPorts = this.selectedNvmeListenerPorts()
      if (!listenerPorts.length) {
        this.$message.error(this.$t('message.storage.service.nvme.listener.group.required'))
        return Promise.resolve()
      }
      return this.runStorageAction('nvmeNamespace', 'createStorageNvmeOfNamespace', {
        subsystemid: this.forms.nvmeNamespace.subsystemid,
        namespaceid: this.forms.nvmeNamespace.namespaceid,
        volumeid: volumeId,
        backingpath: this.forms.nvmeNamespace.backingpath,
        listenerports: listenerPorts.join(','),
        cleanupvolumeonfailure: this.forms.nvmeNamespace.volumemode === 'NEW' && !!volumeId
      }, this.$t('label.storage.service.create.nvme.namespace'))
    },
    updateNvmeNamespace () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      const listenerPorts = this.selectedNvmeListenerPorts()
      if (!listenerPorts.length) {
        this.$message.error(this.$t('message.storage.service.nvme.listener.group.required'))
        return Promise.resolve()
      }
      return this.runStorageAction('editNvmeNamespace', 'updateStorageNvmeOfNamespace', {
        id: context.id || this.forms.nvmeNamespace.id,
        namespaceid: this.forms.nvmeNamespace.namespaceid,
        volumeid: this.forms.nvmeNamespace.volumeid,
        backingpath: this.forms.nvmeNamespace.backingpath,
        listenerports: listenerPorts.join(',')
      }, this.$t('label.storage.service.update.nvme.namespace'))
    },
    validateIscsiChapForm () {
      if (this.forms.iscsiAcl.chapenabled) {
        if (!this.forms.iscsiAcl.chapusername || !this.forms.iscsiAcl.chapsecret) {
          this.$message.error(this.$t('message.storage.service.iscsi.chap.credential.required'))
          return false
        }
        if (this.forms.iscsiAcl.mutualchapenabled && (!this.forms.iscsiAcl.mutualchapusername || !this.forms.iscsiAcl.mutualchapsecret)) {
          this.$message.error(this.$t('message.storage.service.iscsi.mutual.chap.credential.required'))
          return false
        }
      }
      return true
    },
    createIscsiAcl () {
      if (!this.validateIscsiChapForm()) {
        return Promise.resolve()
      }
      const result = this.runStorageAction('iscsiAcl', 'createStorageIscsiAcl', {
        targetid: this.forms.iscsiAcl.targetid,
        initiatoriqn: this.forms.iscsiAcl.initiatoriqn,
        permission: this.forms.iscsiAcl.permission,
        chapenabled: this.forms.iscsiAcl.chapenabled,
        chapusername: this.forms.iscsiAcl.chapenabled ? this.forms.iscsiAcl.chapusername : '',
        chapsecret: this.forms.iscsiAcl.chapenabled ? this.forms.iscsiAcl.chapsecret : '',
        mutualchapenabled: this.forms.iscsiAcl.chapenabled && this.forms.iscsiAcl.mutualchapenabled,
        mutualchapusername: this.forms.iscsiAcl.chapenabled && this.forms.iscsiAcl.mutualchapenabled ? this.forms.iscsiAcl.mutualchapusername : '',
        mutualchapsecret: this.forms.iscsiAcl.chapenabled && this.forms.iscsiAcl.mutualchapenabled ? this.forms.iscsiAcl.mutualchapsecret : ''
      }, this.$t('label.storage.service.create.iscsi.acl'))
      this.forms.iscsiAcl.chapsecret = ''
      this.forms.iscsiAcl.mutualchapsecret = ''
      return result
    },
    updateIscsiAcl () {
      if (!this.validateIscsiChapForm()) {
        return Promise.resolve()
      }
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      const result = this.runStorageAction('editIscsiAcl', 'updateStorageIscsiAcl', {
        id: context.id || this.forms.iscsiAcl.id,
        initiatoriqn: this.forms.iscsiAcl.initiatoriqn,
        permission: this.forms.iscsiAcl.permission,
        chapenabled: this.forms.iscsiAcl.chapenabled,
        chapusername: this.forms.iscsiAcl.chapenabled ? this.forms.iscsiAcl.chapusername : '',
        chapsecret: this.forms.iscsiAcl.chapenabled ? this.forms.iscsiAcl.chapsecret : '',
        mutualchapenabled: this.forms.iscsiAcl.chapenabled && this.forms.iscsiAcl.mutualchapenabled,
        mutualchapusername: this.forms.iscsiAcl.chapenabled && this.forms.iscsiAcl.mutualchapenabled ? this.forms.iscsiAcl.mutualchapusername : '',
        mutualchapsecret: this.forms.iscsiAcl.chapenabled && this.forms.iscsiAcl.mutualchapenabled ? this.forms.iscsiAcl.mutualchapsecret : ''
      }, this.$t('label.storage.service.update.iscsi.acl'))
      this.forms.iscsiAcl.chapsecret = ''
      this.forms.iscsiAcl.mutualchapsecret = ''
      return result
    },
    validateNvmeHostAclForm () {
      if (!this.forms.nvmeHostAcl.subsystemid || !this.forms.nvmeHostAcl.hostnqn) {
        this.$notification.error({ message: this.$t('message.storage.service.nvme.host.acl.required') })
        return false
      }
      if (this.selectedNvmeHostAclAllowsAnyHost) {
        this.$notification.error({ message: this.$t('message.storage.service.nvme.host.acl.allow.any.host.blocked') })
        return false
      }
      return true
    },
    prepareNvmeOf () {
      return this.runStorageAction('nvmePrepare', 'prepareStorageServiceNvmeOfVm', {
        instanceid: this.storageService.instance.id,
        ...this.forms.nvmePrepare
      }, this.$t('label.storage.service.prepare.nvmeof'))
    },
    createNvmeSubsystem () {
      return this.runStorageAction('nvmeSubsystem', 'createStorageNvmeOfSubsystem', {
        instanceid: this.storageService.instance.id,
        ...this.forms.nvmeSubsystem
      }, this.$t('label.storage.service.create.nvme.subsystem'))
    },
    updateNvmeSubsystem () {
      const context = this.actionModal.context?.raw || this.actionModal.context || {}
      return this.runStorageAction('editNvmeSubsystem', 'updateStorageNvmeOfSubsystem', {
        id: context.id || this.forms.nvmeSubsystem.id,
        subsystemnqn: this.forms.nvmeSubsystem.subsystemnqn,
        allowanyhost: this.forms.nvmeSubsystem.allowanyhost,
        engine: this.forms.nvmeSubsystem.engine,
        transport: this.forms.nvmeSubsystem.transport
      }, this.$t('label.storage.service.update.nvme.subsystem'))
    },
    createNvmeHostAcl () {
      if (!this.validateNvmeHostAclForm()) {
        return Promise.resolve()
      }
      if (!this.nvmeDhChapSupported) {
        this.forms.nvmeHostAcl.dhchapenabled = false
        this.forms.nvmeHostAcl.dhchapctrlenabled = false
        this.forms.nvmeHostAcl.dhchapkey = ''
        this.forms.nvmeHostAcl.dhchapctrlkey = ''
      }
      const result = this.runStorageAction('nvmeHostAcl', 'createStorageNvmeOfHostAcl', {
        subsystemid: this.forms.nvmeHostAcl.subsystemid,
        hostnqn: this.forms.nvmeHostAcl.hostnqn,
        dhchapenabled: this.forms.nvmeHostAcl.dhchapenabled,
        dhchapkey: this.forms.nvmeHostAcl.dhchapenabled ? this.forms.nvmeHostAcl.dhchapkey : '',
        dhchapctrlenabled: this.forms.nvmeHostAcl.dhchapenabled && this.forms.nvmeHostAcl.dhchapctrlenabled,
        dhchapctrlkey: this.forms.nvmeHostAcl.dhchapenabled && this.forms.nvmeHostAcl.dhchapctrlenabled ? this.forms.nvmeHostAcl.dhchapctrlkey : ''
      }, this.$t('label.storage.service.create.nvme.host.acl'))
      this.forms.nvmeHostAcl.dhchapkey = ''
      this.forms.nvmeHostAcl.dhchapctrlkey = ''
      return result
    },
    updateNvmeHostAcl () {
      if (!this.validateNvmeHostAclForm()) {
        return Promise.resolve()
      }
      if (!this.nvmeDhChapSupported) {
        this.forms.nvmeHostAcl.dhchapenabled = false
        this.forms.nvmeHostAcl.dhchapctrlenabled = false
        this.forms.nvmeHostAcl.dhchapkey = ''
        this.forms.nvmeHostAcl.dhchapctrlkey = ''
      }
      const result = this.runStorageAction('editNvmeHostAcl', 'updateStorageNvmeOfHostAcl', {
        id: this.forms.nvmeHostAcl.id,
        hostnqn: this.forms.nvmeHostAcl.hostnqn,
        dhchapenabled: this.forms.nvmeHostAcl.dhchapenabled,
        dhchapkey: this.forms.nvmeHostAcl.dhchapenabled ? this.forms.nvmeHostAcl.dhchapkey : '',
        dhchapctrlenabled: this.forms.nvmeHostAcl.dhchapenabled && this.forms.nvmeHostAcl.dhchapctrlenabled,
        dhchapctrlkey: this.forms.nvmeHostAcl.dhchapenabled && this.forms.nvmeHostAcl.dhchapctrlenabled ? this.forms.nvmeHostAcl.dhchapctrlkey : ''
      }, this.$t('label.storage.service.update.nvme.host.acl'))
      this.forms.nvmeHostAcl.dhchapkey = ''
      this.forms.nvmeHostAcl.dhchapctrlkey = ''
      return result
    },
    attachExistingVolume () {
      return this.runStorageAction('attachVolume', 'attachStorageVolumeToFileShare', this.forms.attachVolume, this.$t('label.storage.service.attach.existing.volume'))
    },
    resizeFileShare () {
      return this.runStorageAction('resizeShare', 'resizeStorageFileShare', {
        id: this.forms.resizeShare.id,
        size: this.forms.resizeShare.size,
        quotabytes: this.toCapacityBytes(this.forms.resizeShare.quotaamount, this.forms.resizeShare.quotaunit),
        resizevolume: this.forms.resizeShare.resizevolume
      }, this.$t('label.storage.service.resize.file.share'))
    },
    populateResizeBackingVolumeForm (context = {}) {
      const volumeId = context.volumeid || context.volumeId || ''
      const currentSizeBytes = Number(context.currentSizeBytes)
      const currentSizeGiB = Number(context.currentSizeGiB)
      const validSize = Number.isFinite(currentSizeBytes) && currentSizeBytes > 0 && Number.isFinite(currentSizeGiB) && currentSizeGiB > 0
      this.forms.resizeBackingVolume = {
        volumeid: volumeId,
        size: null,
        currentSizeBytes: validSize ? currentSizeBytes : null,
        currentSizeGiB: validSize ? currentSizeGiB : null,
        minSizeGiB: validSize ? currentSizeGiB + 1 : 1,
        name: context.name || '-',
        currentSize: validSize ? this.formatCapacityValue(currentSizeBytes) : '-',
        diskOffering: context.diskOffering || '-',
        storagePool: context.storagePool || '-',
        resourceName: context.resourceName || context.exportName || context.shareName || context.targetName || context.namespaceName || '-'
      }
    },
    resizeBackingVolume () {
      return this.runStorageAction('resizeBackingVolume', 'resizeStorageServiceBackingVolume', {
        instanceid: this.storageService.instance.id,
        volumeid: this.forms.resizeBackingVolume.volumeid,
        size: this.forms.resizeBackingVolume.size
      }, this.$t('label.storage.service.resize.volume'))
    },
    detachBackingVolume () {
      return this.runStorageAction('detachBackingVolume', 'detachStorageServiceBackingVolume', {
        instanceid: this.storageService.instance.id,
        volumeid: this.forms.detachBackingVolume.volumeid
      }, this.$t('label.storage.service.detach.backing.volume'))
    },
    disconnectSession () {
      return this.runStorageAction('disconnectSession', 'disconnectStorageServiceSession', {
        instanceid: this.storageService.instance.id,
        protocol: this.forms.disconnectSession.protocol,
        peer: this.forms.disconnectSession.peer,
        local: this.forms.disconnectSession.local,
        sessionid: this.forms.disconnectSession.sessionid,
        force: this.forms.disconnectSession.force
      }, this.$t('label.storage.service.disconnect.session'))
    },
    parseRuntimeResult (item) {
      if (!item) {
        return {}
      }
      if (item.resultjson && typeof item.resultjson === 'string') {
        try {
          return JSON.parse(item.resultjson.replace(/\\=/g, '='))
        } catch (e) {
          return {}
        }
      }
      return item.resultjson || item
    },
    protocolSessions (protocol) {
      return this.allSessions.filter(session => {
        const sessionProtocol = (session.protocol || session.service || '').toUpperCase()
        return sessionProtocol === protocol
      })
    },
    possibleSessionValues (values, key) {
      if (!Array.isArray(values)) {
        return ''
      }
      const resolved = values.map(item => {
        if (item === null || item === undefined) {
          return ''
        }
        if (typeof item === 'object') {
          const lowerKey = String(key || '').toLowerCase()
          return item[key] || item[lowerKey] || item.value || ''
        }
        return item
      }).map(item => String(item || '').trim()).filter(Boolean)
      return [...new Set(resolved)].join(', ')
    },
    normalizeSessionAddress (value) {
      const text = String(value || '').trim()
      if (!text || text === '-') {
        return text || '-'
      }
      if (text.startsWith('[::ffff:') && text.includes(']:')) {
        return text.replace('[::ffff:', '').replace(']', '')
      }
      if (text.startsWith('::ffff:')) {
        return text.replace('::ffff:', '')
      }
      return text
    },
    formatSessionEndpoint (value) {
      return this.normalizeSessionAddress(value)
    },
    nfsSessionResourceName (session) {
      const direct = session.resourceName || session.exportName || session.share
      if (direct) {
        return this.clientVisibleName(direct, '-')
      }
      const candidates = Array.isArray(session.possibleExports) ? session.possibleExports : []
      const names = candidates
        .map(item => this.clientVisibleName(item.resourceName || item.exportName || item.name || item.clientPath, ''))
        .filter(Boolean)
      if (names.length === 1) {
        return names[0]
      }
      if (names.length > 1) {
        return `${this.$t('label.storage.service.possible.exports')} (${names.join(', ')})`
      }
      return '-'
    },
    runtimeColor (item) {
      const status = String(item.status || '').toUpperCase()
      if (item.success === true || status === 'OK' || status === 'READY') {
        return 'green'
      }
      if (status === 'PREPARATION_REQUIRED' || status === 'NOT_ATTACHED') {
        return 'orange'
      }
      if (item.success === false) {
        return 'red'
      }
      return 'blue'
    },
    nicLabel (nic) {
      return [nic.networkname, nic.ipaddress, nic.netmask || nic.cidr].filter(Boolean).join(' / ')
    },
    shareNameLabel (share) {
      return this.clientVisibleName(share?.name || share?.exportname || share?.id, '-')
    },
    shareLabel (share) {
      return [share.protocol, share.name || share.id, share.path].filter(Boolean).join(' / ')
    },
    uidGidSummary (uid, gid) {
      const left = uid === undefined || uid === null || uid === '' ? '-' : uid
      const right = gid === undefined || gid === null || gid === '' ? '-' : gid
      return `${left}:${right}`
    },
    posixPermissionSummary (config = {}) {
      const owner = this.uidGidSummary(config.ownerUid ?? config.owneruid, config.ownerGid ?? config.ownergid)
      const mode = config.mode || '-'
      const recursive = this.boolValue(config.recursivePermission ?? config.recursivepermission) ? this.$t('label.yes') : this.$t('label.no')
      return `${owner} / ${mode} / ${recursive}`
    },
    effectiveNfsExportConfig (config = {}) {
      const next = { ...config }
      const readOnly = this.boolValue(next.readOnly ?? next.readonly)
      const rootSquash = next.rootSquash === undefined && next.rootsquash === undefined ? true : this.boolValue(next.rootSquash ?? next.rootsquash)
      if (!readOnly && rootSquash) {
        if (next.anonUid === undefined && next.anonuid === undefined) next.anonUid = 65534
        if (next.anonGid === undefined && next.anongid === undefined) next.anonGid = 65534
        if (next.ownerUid === undefined && next.owneruid === undefined) next.ownerUid = next.anonUid ?? next.anonuid ?? 65534
        if (next.ownerGid === undefined && next.ownergid === undefined) next.ownerGid = next.anonGid ?? next.anongid ?? 65534
        if (!next.mode) next.mode = '0775'
        if (next.recursivePermission === undefined && next.recursivepermission === undefined) next.recursivePermission = false
      }
      return next
    },
    applyNfsWritableDefaults () {
      if (this.forms.nfsExport.readonly || !this.forms.nfsExport.rootsquash) {
        return
      }
      if (this.forms.nfsExport.anonuid === null || this.forms.nfsExport.anonuid === undefined || this.forms.nfsExport.anonuid === '') {
        this.forms.nfsExport.anonuid = 65534
      }
      if (this.forms.nfsExport.anongid === null || this.forms.nfsExport.anongid === undefined || this.forms.nfsExport.anongid === '') {
        this.forms.nfsExport.anongid = 65534
      }
      if (this.forms.nfsExport.owneruid === null || this.forms.nfsExport.owneruid === undefined || this.forms.nfsExport.owneruid === '') {
        this.forms.nfsExport.owneruid = this.forms.nfsExport.anonuid
      }
      if (this.forms.nfsExport.ownergid === null || this.forms.nfsExport.ownergid === undefined || this.forms.nfsExport.ownergid === '') {
        this.forms.nfsExport.ownergid = this.forms.nfsExport.anongid
      }
      if (!this.forms.nfsExport.mode) {
        this.forms.nfsExport.mode = '0775'
      }
    },
    clientVisibleName (value, fallback) {
      const normalized = String(value || fallback || '').trim().replace(/^\/+|\/+$/g, '')
        .replace(/[^A-Za-z0-9_.-]+/g, '-')
        .replace(/^[.-]+|[.-]+$/g, '')
      return normalized || fallback
    },
    targetLabel (target) {
      return [target.targetname || target.subsystemnqn || target.id, target.lunornamespace].filter(Boolean).join(' / ')
    },
    formatRuntime (items) {
      if (!items || items.length === 0) {
        return '{}'
      }
      return JSON.stringify(items.map(item => {
        const clone = { ...item }
        if (clone.resultjson) {
          try {
            clone.resultjson = JSON.parse(clone.resultjson)
          } catch (e) {}
        }
        return clone
      }), null, 2)
    }
  }
}
</script>

<style lang="scss" scoped>
  .page-header-wrapper-grid-content-main {
    width: 100%;
    height: 100%;
    min-height: 100%;
    transition: 0.3s;
  }
  .info {
    font-size: 0.8rem;
  }
  .storage-service-tabs {
    width: 100%;

    :deep(.ant-tabs-nav) {
      margin-bottom: 16px;
    }

    :deep(.ant-tabs-tab) {
      padding: 10px 14px;
    }

    :deep(.ant-tabs-content-holder) {
      min-width: 0;
    }
  }
  .storage-service {
    color: inherit;
  }
  .storage-service--overview {
    margin-top: 8px;
  }
  .storage-service--protocol {
    width: 100%;
    min-height: calc(100vh - 178px);
  }
  .storage-service--wide {
    min-height: calc(100vh - 156px);
  }
  .storage-service__section-title {
    margin: 18px 0 8px;
    font-size: 15px;
    font-weight: 600;
  }
  .storage-service__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;

    h3 {
      margin-bottom: 4px;
    }

    p {
      margin: 0;
      color: rgba(127, 127, 127, 0.95);
    }
  }
  .storage-service__alert {
    margin-bottom: 16px;
  }
  .storage-identity-repair-action {
    display: flex;
    justify-content: flex-end;
    margin: -8px 0 16px;
  }
  .storage-service-error-list {
    margin: 4px 0 0;
    padding-left: 18px;
    color: inherit;
  }
  .storage-service-error-list li + li {
    margin-top: 4px;
  }
  .storage-service__grid,
  .storage-service__inner-tabs {
    margin-top: 16px;
  }
  .storage-protocol-topbar {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 12px;
  }
  .protocol-toolbar--right {
    justify-content: flex-end;
    margin-left: auto;
  }
  .storage-protocol-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.35fr) minmax(280px, 0.65fr);
    gap: 16px;
    margin-bottom: 16px;
  }
  .storage-detail-list {
    margin: 0;
  }
  .storage-detail-list__row {
    display: grid;
    grid-template-columns: minmax(140px, 22%) minmax(0, 1fr);
    gap: 16px;
    padding: 11px 0;
    border-top: 1px solid rgba(127, 127, 127, 0.22);

    dt {
      font-weight: 600;
      color: rgba(127, 127, 127, 0.95);
    }

    dd {
      min-width: 0;
      margin: 0;
      overflow-wrap: anywhere;
    }
  }
  .storage-panel {
    min-height: 100%;
    padding: 16px;
    border: 1px solid rgba(127, 127, 127, 0.22);
    border-radius: 6px;
    background: rgba(127, 127, 127, 0.055);
  }
  .storage-panel__title {
    margin-bottom: 12px;
    font-weight: 600;
    color: inherit;
  }
  .storage-panel__description {
    margin: -4px 0 12px;
    color: rgba(127, 127, 127, 0.95);
  }
  .storage-kv {
    display: grid;
    grid-template-columns: minmax(96px, auto) minmax(0, 1fr);
    gap: 8px 12px;
    margin: 0;

    dt {
      color: rgba(127, 127, 127, 0.95);
    }

    dd {
      min-width: 0;
      margin: 0;
      overflow-wrap: anywhere;
    }
  }
  .storage-kv--compact {
    grid-template-columns: minmax(112px, auto) minmax(0, 1fr);
  }
  .command-line {
    min-width: 0;
    margin-top: 8px;
    padding: 9px 10px;
    overflow: hidden;
    color: inherit;
    font-family: SFMono-Regular, Consolas, 'Liberation Mono', monospace;
    font-size: 12px;
    line-height: 1.45;
    text-overflow: ellipsis;
    white-space: nowrap;
    border: 1px solid rgba(127, 127, 127, 0.18);
    border-radius: 4px;
    background: rgba(127, 127, 127, 0.07);
  }
  .storage-table-section {
    margin-top: 16px;
    padding: 14px 16px 16px;
    border: 1px solid rgba(127, 127, 127, 0.22);
    border-radius: 6px;
    background: rgba(127, 127, 127, 0.04);
  }
  .storage-table-section__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 12px;

    h4 {
      margin: 0 0 4px;
      font-size: 14px;
      font-weight: 600;
      color: inherit;
    }

    p {
      margin: 0;
      color: rgba(127, 127, 127, 0.95);
    }
  }
  .storage-section-actions {
    flex: 0 0 auto;
    justify-content: flex-end;
  }
  .storage-data-table {
    width: 100%;
    color: inherit;

    :deep(.ant-table) {
      color: inherit;
      background: transparent;
    }

    :deep(.ant-table-container) {
      border-color: rgba(127, 127, 127, 0.2);
    }

    :deep(.ant-table-thead > tr > th) {
      color: inherit;
      font-weight: 600;
      background: rgba(127, 127, 127, 0.08);
      border-bottom-color: rgba(127, 127, 127, 0.24);
    }

    :deep(.ant-table-tbody > tr > td) {
      color: inherit;
      border-bottom-color: rgba(127, 127, 127, 0.16);
    }

    :deep(.ant-table-tbody > tr:hover > td) {
      background: rgba(24, 144, 255, 0.08);
    }

    :deep(.ant-table-cell-fix-left),
    :deep(.ant-table-cell-fix-right) {
      color: inherit;
      background: rgba(255, 255, 255, 0.98);
    }

    :deep(.ant-table-tbody > tr:hover > td.ant-table-cell-fix-right),
    :deep(.ant-table-tbody > tr:hover > td.ant-table-cell-fix-left) {
      background: #eef7ff;
    }

    :deep(.storage-table-actions-column) {
      text-align: right;
      white-space: nowrap;
      background-clip: padding-box;
      box-shadow: -10px 0 14px -14px rgba(0, 0, 0, 0.55);
    }

    :deep(.ant-table-cell-scrollbar) {
      background: transparent;
      box-shadow: none;
    }

    :deep(.storage-table-actions-column .ant-table-column-title) {
      display: block;
      text-align: right;
    }

    :deep(.ant-empty-normal) {
      color: rgba(127, 127, 127, 0.95);
    }

    :deep(.ant-empty-description) {
      color: rgba(127, 127, 127, 0.95);
    }

    :deep(.ant-table-body) {
      scrollbar-width: thin;
      scrollbar-color: var(--ui-scroll-thumb) var(--ui-scroll-track);
    }

    :deep(.ant-table-body::-webkit-scrollbar) {
      width: 6px;
      height: 6px;
    }

    :deep(.ant-table-body::-webkit-scrollbar-track) {
      background: var(--ui-scroll-track);
    }

    :deep(.ant-table-body::-webkit-scrollbar-thumb) {
      border-radius: 4px;
      background: var(--ui-scroll-thumb);
    }
  }
  .storage-table-actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    width: 100%;
    min-height: 28px;
    white-space: nowrap;
  }
  .storage-table-actions__space {
    display: flex;
    justify-content: flex-end;
    width: 100%;
    white-space: nowrap;
  }
  .storage-table-actions__empty {
    display: inline-block;
    width: 100%;
    text-align: right;
    color: rgba(127, 127, 127, 0.95);
  }
  .storage-ellipsis {
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    vertical-align: bottom;
    white-space: nowrap;
  }
  .storage-ellipsis--code {
    font-family: SFMono-Regular, Consolas, 'Liberation Mono', monospace;
    font-size: 12px;
  }
  .runtime-row {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 28px;
    overflow-wrap: anywhere;
  }
  .storage-empty {
    color: rgba(127, 127, 127, 0.95);
  }
  .storage-input-number {
    width: 100%;
  }
  :global(.storage-service-action-modal) {
    overflow: hidden;
  }
  :global(.storage-service-action-modal .ant-modal) {
    top: 0;
    max-width: calc(100vw - 32px);
    padding-bottom: 0;
  }
  :global(.storage-service-action-modal .ant-modal-content) {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 48px);
    overflow: hidden;
  }
  :global(.storage-service-action-modal .ant-modal-header),
  :global(.storage-service-action-modal .ant-modal-footer) {
    flex: 0 0 auto;
  }
  :global(.storage-service-action-modal .ant-modal-body) {
    flex: 1 1 auto;
    min-width: 0;
    min-height: 0;
    overflow: hidden !important;
    overflow-x: hidden !important;
    overflow-y: hidden !important;
  }
  .storage-modal-body {
    box-sizing: border-box;
    width: 100%;
    max-width: 100%;
    max-height: calc(100vh - 172px);
    overflow-x: hidden;
    overflow-y: auto;
    padding: 0 8px 20px 0;
    scrollbar-width: thin;
    scrollbar-color: var(--ui-scroll-thumb) var(--ui-scroll-track);
  }
  .storage-modal-body :deep(.ant-form),
  .storage-modal-body :deep(.ant-form-item),
  .storage-modal-body :deep(.ant-form-item-control),
  .storage-modal-body :deep(.ant-form-item-control-input),
  .storage-modal-body :deep(.ant-form-item-control-input-content),
  .storage-modal-body :deep(.ant-input),
  .storage-modal-body :deep(.ant-input-number),
  .storage-modal-body :deep(.ant-select),
  .storage-modal-body :deep(.ant-alert),
  .storage-modal-body :deep(.ant-radio-group),
  .storage-modal-body :deep(.ant-checkbox-group) {
    box-sizing: border-box;
    max-width: 100%;
    min-width: 0;
  }
  .storage-modal-body::-webkit-scrollbar {
    width: 6px;
    height: 6px;
  }
  .storage-modal-body::-webkit-scrollbar-track {
    background: var(--ui-scroll-track);
  }
  .storage-modal-body::-webkit-scrollbar-thumb {
    border-radius: 4px;
    background: var(--ui-scroll-thumb);
  }
  .storage-action-form--vertical {
    display: flex;
    flex-direction: column;
    gap: 12px;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    overflow: visible;

    :deep(.tooltip-icon) {
      margin-left: 4px;
      color: #409eff;
    }

    :deep(.ant-divider) {
      margin: 4px 0 8px;
      color: inherit;
      border-color: rgba(127, 127, 127, 0.22);
    }

    :deep(.ant-form-item) {
      display: block;
      width: 100%;
      max-width: 100%;
      margin-bottom: 0;
    }

    :deep(.ant-form-item-label),
    :deep(.ant-form-item-control) {
      display: block;
      width: 100%;
      max-width: 100%;
      text-align: left;
    }
  }
  .storage-action-section {
    box-sizing: border-box;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    padding: 12px 14px;
    border: 1px solid rgba(127, 127, 127, 0.22);
    border-radius: 6px;
    background: rgba(127, 127, 127, 0.045);
  }
  .storage-action-section__title {
    margin-bottom: 10px;
    color: inherit;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.35;
  }
  .storage-action-policy-box {
    box-sizing: border-box;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    margin-top: 18px;
    padding: 12px 14px;
    border: 1px solid rgba(127, 127, 127, 0.18);
    border-radius: 6px;
    background: rgba(127, 127, 127, 0.035);
  }
  .storage-action-policy-box__title {
    margin-bottom: 10px;
    color: rgba(127, 127, 127, 0.98);
    font-size: 12px;
    font-weight: 600;
    line-height: 1.35;
  }
  .storage-action-policy-box__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    min-width: 0;

    :deep(.tooltip-label) {
      min-width: 0;
    }
  }
  .storage-action-checkbox-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 14px;
  }
  .storage-action-context-alert {
    margin-top: 12px;
    margin-bottom: 0;
  }
  .storage-action-summary-box {
    display: grid;
    margin-bottom: 8px;
    overflow: hidden;
    border: 1px solid rgba(127, 127, 127, 0.18);
    border-radius: 6px;
    background: rgba(127, 127, 127, 0.035);
  }
  .storage-action-summary-row {
    display: grid;
    grid-template-columns: minmax(160px, 0.34fr) minmax(0, 1fr);
    min-height: 38px;

    & + .storage-action-summary-row {
      border-top: 1px solid rgba(127, 127, 127, 0.14);
    }

    span,
    code {
      display: flex;
      align-items: center;
      min-width: 0;
      padding: 8px 12px;
      overflow-wrap: anywhere;
    }

    span {
      color: rgba(127, 127, 127, 0.98);
      font-weight: 600;
      background: rgba(127, 127, 127, 0.045);
    }

    code {
      color: inherit;
      background: transparent;
    }
  }
  .storage-listener-option {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;

    span,
    small {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    small {
      color: #8c8c8c;
      font-size: 12px;
      line-height: 1.3;
    }
  }
  .capacity-input-group {
    display: flex;
    width: 100%;

    :deep(.ant-input-number) {
      flex: 1 1 auto;
      width: auto;
    }

    :deep(.ant-select) {
      flex: 0 0 88px;
    }
  }
  .storage-action-row {
    margin-top: 16px;
  }
  .storage-list {
    margin-top: 16px;
  }
  .storage-service-card-grid {
    margin-top: 0;
  }
  .storage-list__item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 0;
    border-top: 1px solid rgba(127, 127, 127, 0.18);

    div {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }

    strong,
    span {
      overflow-wrap: anywhere;
    }

    span {
      color: rgba(127, 127, 127, 0.95);
    }
  }
  .runtime-json {
    max-height: 360px;
    margin: 0;
    overflow: auto;
    color: inherit;
    background: transparent;
  }
  :global(body.dark-mode) .storage-data-table {
    color: rgba(229, 236, 246, 0.9);
  }
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-tbody > tr > td),
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-cell-fix-left),
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-cell-fix-right) {
    color: rgba(229, 236, 246, 0.9);
    background: #1f272f;
  }
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-thead > tr > th.ant-table-cell-fix-left),
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-thead > tr > th.ant-table-cell-fix-right) {
    background: #222b35;
  }
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-tbody > tr:hover > td.ant-table-cell-fix-left),
  :global(body.dark-mode) .storage-data-table :deep(.ant-table-tbody > tr:hover > td.ant-table-cell-fix-right) {
    background: #243447;
  }
  :global(body.dark-mode) .storage-data-table :deep(.ant-empty-normal),
  :global(body.dark-mode) .storage-data-table :deep(.ant-empty-description),
  :global(body.dark-mode) .storage-table-actions__empty {
    color: rgba(229, 236, 246, 0.72);
  }
  :global(body.dark-mode) {
    .storage-service-tabs {
      :deep(.ant-tabs-nav::before) {
        border-bottom-color: rgba(255, 255, 255, 0.14);
      }

      :deep(.ant-tabs-tab) {
        color: rgba(229, 236, 246, 0.72);
      }

      :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
        color: #40a9ff;
      }
    }

    .storage-table-section,
    .storage-panel {
      background: rgba(255, 255, 255, 0.025);
      border-color: rgba(255, 255, 255, 0.14);
    }

    .storage-data-table {
      :deep(.ant-table-thead > tr > th) {
        color: rgba(229, 236, 246, 0.92);
        background: rgba(255, 255, 255, 0.055);
        border-bottom-color: rgba(255, 255, 255, 0.16);
      }

      :deep(.ant-table-tbody > tr > td) {
        color: rgba(229, 236, 246, 0.9);
        border-bottom-color: rgba(255, 255, 255, 0.1);
      }

      :deep(.ant-table-tbody > tr:hover > td) {
        background: rgba(24, 144, 255, 0.12);
      }

      :deep(.ant-table-body) {
        scrollbar-color: var(--ui-scroll-thumb) var(--ui-scroll-track);
      }

      :deep(.ant-table-body::-webkit-scrollbar-thumb) {
        background: var(--ui-scroll-thumb);
      }

      :deep(.ant-table-cell-fix-left),
      :deep(.ant-table-cell-fix-right),
      :deep(.storage-table-actions-column) {
        color: rgba(229, 236, 246, 0.9);
        background: #1f272f;
      }

      :deep(.ant-empty-normal),
      :deep(.ant-empty-description) {
        color: rgba(229, 236, 246, 0.72);
      }

      :deep(.ant-empty-img-simple-ellipse),
      :deep(.ant-empty-img-simple-path) {
        fill: rgba(229, 236, 246, 0.18);
      }

      :deep(.ant-empty-img-simple-g) {
        stroke: rgba(229, 236, 246, 0.34);
      }
    }
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-section) {
    background: rgba(255, 255, 255, 0.025);
    border-color: rgba(255, 255, 255, 0.14);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-policy-box) {
    background: rgba(255, 255, 255, 0.025);
    border-color: rgba(255, 255, 255, 0.12);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-policy-box__title) {
    color: rgba(229, 236, 246, 0.76);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-summary-box) {
    background: rgba(255, 255, 255, 0.025);
    border-color: rgba(255, 255, 255, 0.12);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-summary-row + .storage-action-summary-row) {
    border-top-color: rgba(255, 255, 255, 0.1);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-summary-row span) {
    color: rgba(229, 236, 246, 0.76);
    background: rgba(255, 255, 255, 0.035);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-context-alert) {
    color: rgba(229, 236, 246, 0.92);
    background: rgba(24, 144, 255, 0.12);
    border-color: rgba(24, 144, 255, 0.34);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-context-alert .ant-alert-message),
  :global(body.dark-mode .storage-service-action-modal .storage-action-context-alert .ant-alert-icon) {
    color: rgba(229, 236, 246, 0.92);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-delete-alert) {
    color: rgba(255, 239, 186, 0.94);
    background: rgba(250, 173, 20, 0.12);
    border-color: rgba(250, 173, 20, 0.34);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-action-delete-alert .ant-alert-message),
  :global(body.dark-mode .storage-service-action-modal .storage-action-delete-alert .ant-alert-icon) {
    color: rgba(255, 239, 186, 0.94);
  }
  .storage-service-inline-alert {
    margin-bottom: 16px;
  }
  :global(body.dark-mode .storage-service-action-modal .storage-service-inline-alert) {
    color: rgba(214, 234, 255, 0.94);
    background: rgba(24, 144, 255, 0.12);
    border-color: rgba(64, 169, 255, 0.35);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-service-inline-alert .ant-alert-message),
  :global(body.dark-mode .storage-service-action-modal .storage-service-inline-alert .ant-alert-icon) {
    color: rgba(214, 234, 255, 0.94);
  }
  :global(body.dark-mode .storage-service-action-modal .storage-fixed-value),
  :global(body.dark-mode .storage-service-action-modal .storage-fixed-value.ant-input),
  :global(body.dark-mode .storage-service-action-modal .storage-fixed-value .ant-input-number-input) {
    color: rgba(229, 236, 246, 0.88) !important;
    background: rgba(255, 255, 255, 0.045) !important;
    border-color: rgba(255, 255, 255, 0.16) !important;
  }
  :global(body.dark-mode .storage-service-action-modal .storage-fixed-value.ant-input-number-disabled) {
    background: rgba(255, 255, 255, 0.045) !important;
    border-color: rgba(255, 255, 255, 0.16) !important;
  }
  :global(body.dark-mode .storage-service-action-modal .ant-input::placeholder),
  :global(body.dark-mode .storage-service-action-modal .ant-select-selection-placeholder) {
    color: rgba(229, 236, 246, 0.36) !important;
  }
  @media (max-width: 991px) {
    .storage-protocol-topbar {
      flex-direction: column;
    }

    .protocol-toolbar--right {
      justify-content: flex-start;
      margin-left: 0;
    }

    .storage-protocol-grid {
      grid-template-columns: 1fr;
    }

    .storage-table-section__header {
      flex-direction: column;
    }

    .storage-section-actions {
      justify-content: flex-start;
    }
  }
  @media (max-width: 640px) {
    .storage-action-checkbox-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
