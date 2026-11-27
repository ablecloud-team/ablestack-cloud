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

<template>
  <div>
    <a-alert
      v-if="providerLoadError"
      type="error"
      show-icon
      :message="$t(providerLoadError)">
      <template #description>
        <p v-if="providerLoaded">{{ $t('message.zone.service.refresh.stale') }}</p>
        <a-button size="small" :loading="fetchLoading" @click="fetchData">{{ $t('label.retry') }}</a-button>
      </template>
    </a-alert>
    <a-spin :spinning="fetchLoading && !providerLoaded">
      <div v-if="providerLoaded || !providerLoadError" class="service-directory">
        <resource-table-toolbar
          v-model:search="serviceSearch"
          searchable
          :search-label="$t('label.zone.service.search')"
          :loading="fetchLoading"
          @refresh="fetchData()">
          <div class="service-filter">
            <label for="zone-service-provider-state">{{ $t('label.zone.service.provider.state') }}</label>
            <a-select
              id="zone-service-provider-state"
              v-model:value="serviceFilter"
              :options="providerStates"
              :virtual="false" />
          </div>
          <template #actions>
            <a-button :disabled="fetchLoading || !!providerLoadError || !providerRows.length" @click="openProviderManagement">
              {{ $t('label.zone.provider.management') }}
            </a-button>
          </template>
        </resource-table-toolbar>
        <detail-tab-table
          size="small"
          row-key="name"
          :columns="serviceColumns"
          :data-source="visibleServices"
          :pagination="{ pageSize: 6, hideOnSinglePage: true, showSizeChanger: false }"
          table-layout="fixed">
          <template #bodyCell="{ column, record }">
            <a-button v-if="column.key === 'name'" type="link" @click="openService(record.name)">
              {{ serviceName(record.name) }}
            </a-button>
            <span v-else-if="column.key === 'providers'">{{ record.providers.map(provider => provider.name).join(', ') }}</span>
            <span v-else>{{ record.activeProviders }} / {{ record.providers.length }}</span>
          </template>
        </detail-tab-table>
      </div>
    </a-spin>

    <mold-dialog
      :visible="serviceDialogOpen"
      :title="requestedService ? $t('label.zone.service.details', { service: serviceName(requestedService) }) : $t('label.zone.provider.management')"
      size="lg"
      focus-guard
      :closable="!showFormAction && !actionLoading"
      :keyboard="!showFormAction && !actionLoading"
      @cancel="closeServiceDialog">
      <template v-if="serviceDialogOpen">
        <a-alert v-if="providerLoadError" type="error" show-icon :message="$t(providerLoadError)">
          <template #description>
            <a-button size="small" :loading="fetchLoading" @click="fetchData">{{ $t('label.retry') }}</a-button>
          </template>
        </a-alert>
        <a-spin v-else :spinning="fetchLoading && !providerLoaded">
          <div v-if="activeProvider">
            <div class="service-provider-selector">
              <label for="zone-service-provider">{{ $t('label.provider') }}</label>
              <a-select
                id="zone-service-provider"
                :value="activeProvider.name"
                :options="providerOptions"
                :disabled="fetchLoading || showFormAction || actionLoading"
                show-search
                option-filter-prop="label"
                @change="selectProvider" />
            </div>
            <section class="provider-details" :aria-label="activeProvider.name">
              <provider-item
                :key="resource.id + ':' + activeProvider.name"
                :loading="actionLoading"
                :actions-disabled="loading || fetchLoading"
                :item-nsp="activeProvider.item"
                :nsp="nsps[activeProvider.name]"
                :resource-id="resource.id"
                :zone-id="resource.zoneid" />
            </section>
          </div>
          <a-empty v-else-if="!fetchLoading" :description="$t('message.zone.service.unavailable')" />
        </a-spin>
      </template>
    </mold-dialog>

    <div v-if="showFormAction">
      <mold-dialog
        v-if="currentAction.confirm"
        :visible="showFormAction"
        :title="$t(currentAction.label)"
        size="sm"
        form-actions
        focus-guard
        @cancel="onCloseAction">
        <p><strong>{{ actionTarget?.name }}</strong></p>
        <p>{{ $t(currentAction.confirm) }}</p>
        <p v-if="requestedService">{{ $t('message.zone.service.provider.scope') }}</p>
        <mold-dialog-actions :busy="actionLoading">
          <a-button @click="onCloseAction">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" :danger="currentAction.label === 'label.disable.provider'" :loading="actionLoading" @click="executeConfirmAction">
            {{ $t(currentAction.label) }}
          </a-button>
        </mold-dialog-actions>
      </mold-dialog>
      <keep-alive v-else-if="currentAction.component">
        <mold-dialog
          size="md"
          form-actions
          focus-guard
          :title="$t(currentAction.label)"
          :visible="showFormAction"
          :closable="true"
          :maskClosable="false"
          @cancel="onCloseAction"
          :confirmLoading="actionLoading"
          centered>
          <keep-alive>
            <component
              :is="currentAction.component"
              :resource="actionTarget"
              :action="currentAction" />
          </keep-alive>
        </mold-dialog>
      </keep-alive>
      <mold-dialog
        size="md"
        form-actions
        focus-guard
        v-else
        :title="$t(currentAction.label)"
        :visible="showFormAction"
        :confirmLoading="actionLoading"
        :closable="true"
        :maskClosable="false"
          @cancel="onCloseAction"
        centered
      >
        <a-form
          :ref="formRef"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          v-ctrl-enter="handleSubmit"
          layout="vertical"
         >
          <a-form-item
            :name="field.name"
            :ref="field.name"
            v-for="(field, index) in currentAction.fieldParams"
            :key="index"
            :label="$t('label.' + field.name)">
            <span v-if="field.name==='password'">
              <a-input-password
                v-focus="index===0"
                v-model:value="form[field.name]"
                :placeholder="field.description" />
            </span>
            <span v-else-if="field.type==='boolean'">
              <a-switch
                v-focus="index===0"
                v-model:checked="form[field.name]"
                :placeholder="field.description"
              />
            </span>
            <span v-else-if="field.type==='uuid'">
              <a-select
                v-focus="index===0"
                v-model:value="form[field.name]"
                :loading="field.loading"
                :placeholder="field.description"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option
                  v-for="(opt, idx) in field.opts"
                  :key="idx"
                  :label="opt.name || opt.description">{{ opt.name || opt.description }}</a-select-option>
              </a-select>
            </span>
            <span v-else>
              <a-input
                v-focus="index===0"
                v-model:value="form[field.name]"
                :placeholder="field.description" />
            </span>
          </a-form-item>

          <mold-dialog-actions :busy="actionLoading">
            <a-button @click="onCloseAction">{{ $t('label.cancel') }}</a-button>
            <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
          </mold-dialog-actions>
        </a-form>
      </mold-dialog>
    </div>
  </div>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { ref, reactive, toRaw, shallowRef, defineAsyncComponent } from 'vue'
import store from '@/store'
import { getAPI, postAPI } from '@/api'
import ProviderItem from '@/views/infra/network/providers/ProviderItem'

export default {
  name: 'ServiceProvidersTab',
  components: {
    MoldDialog,
    MoldDialogActions,
    ResourceTableToolbar,
    ProviderItem
  },
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
  data () {
    return {
      nsps: {},
      nsp: {},
      fetchLoading: true,
      providerLoaded: false,
      providerLoadError: '',
      providerLoadSequence: 0,
      actionLoading: false,
      showFormAction: false,
      currentAction: {},
      actionTarget: null,
      serviceSearch: '',
      serviceFilter: 'all',
      registeredExtensions: []
    }
  },
  computed: {
    serviceColumns () {
      return [
        { key: 'name', title: this.$t('label.service'), width: '30%' },
        { key: 'providers', title: this.$t('label.providers') },
        { key: 'active', title: this.$t('label.zone.service.active.providers'), width: '26%' }
      ]
    },
    providerRows () {
      const known = new Map(this.hardcodedNsps.map(item => [item.title, item]))
      this.registeredExtensions.forEach(extension => known.set(extension.name, this.extensionNspItem(extension.name)))
      Object.keys(this.nsps).forEach(name => {
        if (!known.has(name)) known.set(name, { title: name, details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'], actions: [] })
      })
      return [...known].map(([name, item]) => {
        const provider = this.nsps[name]
        return {
          name,
          item,
          linked: !!provider?.id,
          state: provider?.supported === false ? 'unsupported' : provider?.state || 'unregistered'
        }
      }).sort((a, b) => Number(b.linked) - Number(a.linked) || a.name.localeCompare(b.name))
    },
    services () {
      const services = new Map()
      for (const provider of this.providerRows) {
        for (const name of new Set(this.nsps[provider.name]?.servicelist || [])) {
          if (!services.has(name)) services.set(name, { name, providers: [], activeProviders: 0 })
          const service = services.get(name)
          service.providers.push(provider)
          if (provider.state === 'Enabled') service.activeProviders++
        }
      }
      return [...services.values()].sort((left, right) => this.serviceName(left.name).localeCompare(this.serviceName(right.name)))
    },
    providerStates () {
      const states = [...new Set(this.services.flatMap(service => service.providers.map(provider => provider.state)))]
      return [{ value: 'all', label: this.$t('label.all') }, ...states.map(state => ({ value: state, label: this.providerStateLabel(state) }))]
    },
    visibleServices () {
      const search = this.serviceSearch.trim().toLowerCase()
      return this.services.filter(service => {
        const matchesSearch = [service.name, this.serviceName(service.name), ...service.providers.map(provider => provider.name)]
          .some(name => name.toLowerCase().includes(search))
        return matchesSearch && (this.serviceFilter === 'all' || service.providers.some(provider => provider.state === this.serviceFilter))
      })
    },
    requestedService () { return typeof this.$route.query.service === 'string' ? this.$route.query.service : '' },
    requestedProvider () { return typeof this.$route.query.provider === 'string' ? this.$route.query.provider : '' },
    serviceDialogOpen () {
      return this.$route.meta?.name === 'physicalnetwork' && this.$route.params?.id === this.resource.id &&
        this.$route.query.tab === 'network.service.providers' && !!(this.requestedService || this.requestedProvider)
    },
    dialogProviders () {
      if (!this.requestedService) return this.providerRows
      return this.services.find(service => service.name === this.requestedService)?.providers || []
    },
    activeProvider () {
      return this.dialogProviders.find(provider => provider.name === this.requestedProvider) ||
        this.dialogProviders.find(provider => provider.state === 'Enabled') || this.dialogProviders[0]
    },
    activeProviderName () { return this.activeProvider?.name },
    providerOptions () {
      return this.dialogProviders.map(provider => ({ value: provider.name, label: provider.name + ' · ' + this.providerStateLabel(provider.state) }))
    },
    hardcodedNsps () {
      return [
        {
          title: 'BaremetalDhcpProvider',
          actions: [
            {
              api: 'addBaremetalDhcp',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.baremetal.dhcp.device',
              args: ['url', 'username', 'password'],
              mapping: {
                dhcpservertype: {
                  value: (record) => { return 'DHCPD' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.baremetal.dhcp.devices',
              api: 'listBaremetalDhcp',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['url']
            }
          ]
        },
        {
          title: 'BaremetalPxeProvider',
          actions: [
            {
              api: 'addBaremetalPxeKickStartServer',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.baremetal.pxe.device',
              args: ['url', 'username', 'password', 'tftpdir'],
              mapping: {
                pxeservertype: {
                  value: (record) => { return 'KICK_START' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.baremetal.pxe.devices',
              api: 'listBaremetalPxeServers',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['url']
            }
          ]
        },
        {
          title: 'BigSwitchBcf',
          actions: [
            {
              api: 'addBigSwitchBcfDevice',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.bigswitchbcf.device',
              args: ['hostname', 'username', 'password', 'nat']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listBigSwitchBcfDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'actions']
            }
          ]
        },
        {
          title: 'BrocadeVcs',
          actions: [
            {
              api: 'addBrocadeVcsDevice',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.brocadevcs.device',
              args: ['hostname', 'username', 'password']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listBrocadeVcsDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'actions']
            }
          ]
        },
        {
          title: 'CiscoVnmc',
          actions: [
            {
              api: 'addCiscoVnmcResource',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.vnmc.device',
              args: ['hostname', 'username', 'password']
            },
            {
              api: 'addCiscoAsa1000vResource',
              listView: true,
              icon: 'plus-circle-outlined',
              label: 'label.add.ciscoasa1000v',
              args: ['hostname', 'insideportprofile', 'clusterid'],
              mapping: {
                zoneid: {
                  params: (record) => { return record.zoneid }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.list.ciscovnmc',
              api: 'listCiscoVnmcResources',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['resource', 'provider']
            },
            {
              title: 'label.list.ciscoasa1000v',
              api: 'listCiscoAsa1000vResources',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'insideportprofile', 'actions']
            }
          ]
        },
        {
          title: 'ConfigDrive',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist', 'physicalnetworkid']
        },
        {
          title: 'GloboDns',
          actions: [
            {
              api: 'addGloboDnsHost',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.globo.dns.configuration',
              args: ['url', 'username', 'password']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist']
        },
        {
          title: 'InternalLbVm',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'physicalnetworkid', 'destinationphysicalnetworkid', 'servicelist'],
          lists: [
            {
              title: 'label.instances',
              api: 'listInternalLoadBalancerVMs',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'zonename', 'type', 'state']
            }
          ]
        },
        {
          title: 'Netscaler',
          actions: [
            {
              api: 'addNetscalerLoadBalancer',
              icon: 'plus-outlined',
              listView: true,
              label: 'label.add.netscaler.device',
              component: shallowRef(defineAsyncComponent(() => import('@/views/infra/network/providers/AddNetscalerLoadBalancer.vue')))
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listNetscalerLoadBalancers',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['ipaddress', 'lbdevicestate', 'actions']
            }
          ]
        },
        {
          title: 'NiciraNvp',
          actions: [
            {
              api: 'addNiciraNvpDevice',
              icon: 'plus-outlined',
              listView: true,
              label: 'label.add.niciranvp.device',
              component: shallowRef(defineAsyncComponent(() => import('@/views/infra/network/providers/AddNiciraNvpDevice.vue')))
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listNiciraNvpDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'transportzoneuuid', 'l3gatewayserviceuuid', 'l2gatewayserviceuuid', 'actions']
            }
          ]
        },
        {
          title: 'Opendaylight',
          actions: [
            {
              api: 'addOpenDaylightController',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.opendaylight.device',
              args: ['url', 'username', 'password']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.opendaylight.controllers',
              api: 'listOpenDaylightControllers',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['name', 'url', 'username', 'actions']
            }
          ]
        },
        {
          title: 'Ovs',
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'listOvsElements',
              api: 'listOvsElements',
              mapping: {
                nspid: {
                  value: (record) => { return record.id }
                }
              },
              columns: ['account', 'domain', 'enabled', 'project', 'actions']
            }
          ]
        },
        {
          title: 'PaloAlto',
          actions: [
            {
              api: 'addPaloAltoFirewall',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.pa.device',
              component: shallowRef(defineAsyncComponent(() => import('@/views/infra/network/providers/AddPaloAltoFirewall.vue')))
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listPaloAltoFirewalls',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['ipaddress', 'fwdevicestate', 'type', 'actions']
            }
          ]
        },
        {
          title: 'SecurityGroupProvider',
          details: ['name', 'state', 'id', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ]
        },
        {
          title: 'VirtualRouter',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.instances',
              api: 'listRouters',
              mapping: {
                listAll: {
                  value: (record) => { return true }
                },
                zoneid: {
                  value: (record) => { return record.zoneid }
                },
                forvpc: {
                  value: (record) => { return false }
                }
              },
              columns: ['name', 'state', 'hostname', 'zonename']
            }
          ]
        },
        {
          title: 'VpcVirtualRouter',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.instances',
              api: 'listRouters',
              mapping: {
                forvpc: {
                  value: (record) => { return true }
                },
                zoneid: {
                  value: (record) => { return record.zoneid }
                },
                listAll: {
                  value: () => { return true }
                }
              },
              columns: ['name', 'state', 'hostname', 'zonename']
            }
          ]
        },
        {
          title: 'Tungsten',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          lists: [
            {
              title: 'label.tungsten.fabric.provider',
              api: 'listTungstenFabricProviders',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'tungstenproviderhostname', 'tungstenproviderport', 'tungstengateway', 'tungstenprovidervrouterport', 'tungstenproviderintrospectport']
            }
          ]
        },
        {
          title: 'Nsx',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              // show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              // show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          lists: [
            {
              title: 'label.nsx.provider',
              api: 'listNsxControllers',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'hostname', 'port', 'tier0gateway', 'edgecluster', 'transportzone']
            }
          ]
        },
        {
          title: 'Netris',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          lists: [
            {
              title: 'label.netris.provider',
              api: 'listNetrisProviders',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'netrisurl', 'site', 'tenantname', 'netristag']
            }
          ]
        }
      ]
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  watch: {
    serviceDialogOpen (open) {
      if (!open) this.onCloseAction()
    },
    activeProviderName (name) {
      if (this.actionTarget && this.actionTarget.name !== name) this.onCloseAction()
    },
    'resource.id' () {
      this.onCloseAction()
      this.providerLoaded = false
      this.nsps = {}
      this.registeredExtensions = []
      this.fetchData()
    },
    loading (newData, oldData) {
      if (!newData && this.resource.id) {
        this.fetchData()
      }
    }
  },
  inject: ['parentPollActionCompletion'],
  provide () {
    return {
      provideSetNsp: this.setNsp,
      provideExecuteAction: this.executeAction,
      provideCloseAction: this.onCloseAction,
      provideReload: this.fetchData
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
    },
    fetchData () {
      if (!this.resource || !('id' in this.resource)) {
        return
      }
      this.fetchRegisteredExtensions(this.resource.id)
      return this.fetchServiceProvider(this.resource.id)
    },
    fetchRegisteredExtensions (networkId) {
      // Load NetworkOrchestrator extensions registered to this physical network
      getAPI('listExtensions', {
        type: 'NetworkOrchestrator',
        details: 'resource'
      }).then(json => {
        if (networkId !== this.resource.id) return
        const exts = (json?.listextensionsresponse?.extension) || []
        this.registeredExtensions = exts.filter(ext =>
          (ext.resources || []).some(r => r.type === 'PhysicalNetwork' && r.id === networkId)
        )
      }).catch(() => {
        if (networkId === this.resource.id) this.registeredExtensions = []
      })
    },
    extensionNspItem (extName) {
      // Build a ProviderItem-compatible itemNsp descriptor for extension-backed NSPs.
      // Mirrors the structure of hardcoded entries in hardcodedNsps.
      return {
        title: extName,
        details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
        actions: [
          {
            api: 'updateNetworkServiceProvider',
            icon: 'play-circle-outlined',
            listView: true,
            label: 'label.enable.provider',
            confirm: 'message.confirm.enable.provider',
            show: (record) => record && record.id && record.state === 'Disabled',
            mapping: {
              state: { value: () => 'Enabled' }
            }
          },
          {
            api: 'updateNetworkServiceProvider',
            icon: 'stop-outlined',
            listView: true,
            label: 'label.disable.provider',
            confirm: 'message.confirm.disable.provider',
            show: (record) => record && record.id && record.state === 'Enabled',
            mapping: {
              state: { value: () => 'Disabled' }
            }
          }
        ]
      }
    },
    async fetchServiceProvider (networkId) {
      const sequence = ++this.providerLoadSequence
      this.fetchLoading = true
      this.providerLoadError = ''
      try {
        const json = await getAPI('listNetworkServiceProviders', { physicalnetworkid: networkId }, { preserveOnFailure: true })
        if (sequence !== this.providerLoadSequence || networkId !== this.resource.id) return
        const providers = json.listnetworkserviceprovidersresponse.networkserviceprovider || []
        this.nsps = Object.fromEntries(providers.map(provider => [provider.name, provider]))
        this.providerLoaded = true
      } catch (error) {
        if (sequence !== this.providerLoadSequence || networkId !== this.resource.id) return
        if ([401, 403].includes(error.response?.status)) {
          this.nsps = {}
          this.providerLoaded = false
        }
        this.providerLoadError = error.response?.status === 403
          ? 'message.zone.providers.forbidden'
          : 'message.zone.providers.load.failed'
      } finally {
        if (sequence === this.providerLoadSequence && networkId === this.resource.id) this.fetchLoading = false
      }
    },
    providerStateLabel (state) {
      return this.$t({
        Enabled: 'label.enabled',
        Disabled: 'label.disabled',
        Shutdown: 'label.shutdown',
        unsupported: 'label.zone.provider.unsupported',
        unregistered: 'label.not.added'
      }[state] || 'label.unknown')
    },
    serviceName (name) {
      const key = 'label.' + name.toLowerCase()
      const label = this.$t(key)
      return label === key ? name : label
    },
    openService (service) {
      this.$router.push({ query: { ...this.$route.query, service, provider: undefined } })
    },
    openProviderManagement () {
      const provider = this.providerRows.find(row => row.state === 'Enabled') || this.providerRows[0]
      if (provider) this.$router.push({ query: { ...this.$route.query, service: undefined, provider: provider.name } })
    },
    selectProvider (provider) {
      this.$router.push({ query: { ...this.$route.query, provider } })
    },
    closeServiceDialog () {
      if (this.showFormAction || this.actionLoading) return
      this.$router.replace({ query: { ...this.$route.query, service: undefined, provider: undefined } })
    },
    setNsp (nsp) {
      this.nsp = nsp
    },
    handleSubmit () {
      if (this.currentAction.confirm) {
        this.executeConfirmAction()
        return
      }

      const action = this.currentAction
      const target = this.actionTarget
      if (this.actionLoading || !target || target.physicalnetworkid !== this.resource.id) return
      this.formRef.value.validate().then(async () => {
        if (this.actionLoading || action !== this.currentAction || target !== this.actionTarget || target.physicalnetworkid !== this.resource.id) return
        const values = toRaw(this.form)
        const params = {}
        params.physicalnetworkid = target.physicalnetworkid
        for (const key in values) {
          const input = values[key]
          for (const param of this.currentAction.fieldParams) {
            if (param.name !== key) {
              continue
            }
            if (param.type === 'uuid') {
              params[key] = param.opts[input].id
            } else if (param.type === 'list') {
              params[key] = input.map(e => { return param.opts[e].id }).reduce((str, name) => { return str + ',' + name })
            } else {
              params[key] = input
            }
          }
        }
        if (this.currentAction.mapping) {
          for (const key in this.currentAction.mapping) {
            if (!this.currentAction.mapping[key].value) {
              continue
            }
            params[key] = this.currentAction.mapping[key].value(this.resource, params)
          }
        }
        this.actionLoading = true

        try {
          if (!target.id) {
            const serviceParams = {}
            serviceParams.name = target.name
            serviceParams.physicalnetworkid = target.physicalnetworkid
            const networkServiceProvider = await this.addNetworkServiceProvider(serviceParams)
            Object.assign(target, networkServiceProvider)
          }
          params.id = target.id
          const hasJobId = await this.executeApi(this.currentAction.api, params)
          if (!hasJobId) {
            await this.$message.success('Success')
            await this.fetchData()
          }
          this.actionLoading = false
          this.onCloseAction()
        } catch (error) {
          this.actionLoading = false
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: error
          })
        }
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    onCloseAction () {
      if (this.actionLoading) return
      this.currentAction = {}
      this.actionTarget = null
      this.showFormAction = false
    },
    addNetworkServiceProvider (args) {
      return new Promise((resolve, reject) => {
        let message = ''
        postAPI('addNetworkServiceProvider', args).then(async json => {
          const jobId = json.addnetworkserviceproviderresponse.jobid
          if (jobId) {
            const result = await this.pollJob(jobId)
            if (result.jobstatus === 2) {
              message = result.jobresult.errortext
              reject(message)
              return
            }
            resolve(result.jobresult.networkserviceprovider)
          }
        }).catch(error => {
          message = (error.response && error.response.headers && error.response.headers['x-description']) || error.message
          reject(message)
        })
      })
    },
    async pollJob (jobId) {
      return new Promise(resolve => {
        const asyncJobInterval = setInterval(() => {
          getAPI('queryAsyncJobResult', { jobId }).then(async json => {
            const result = json.queryasyncjobresultresponse
            if (result.jobstatus === 0) {
              return
            }

            clearInterval(asyncJobInterval)
            resolve(result)
          })
        }, 1000)
      })
    },
    executeAction (action) {
      if (this.showFormAction || this.actionLoading || this.fetchLoading || this.providerLoadError || this.nsp.name !== this.activeProviderName || this.nsp.physicalnetworkid !== this.resource.id) return
      this.currentAction = { ...action }
      this.actionTarget = { ...this.nsp }
      this.showFormAction = true
      if (action.confirm || action.component) return
      const apiParams = this.$store.getters.apis[action.api]?.params || []
      this.currentAction.fieldParams = (action.args || []).map(arg => {
        const param = apiParams.find(param => param.name === arg)
        return param ? { ...param } : null
      }).filter(Boolean)
      this.currentAction.fieldParams.forEach(field => {
        if (field.type === 'uuid') this.listFieldOpts(field)
      })
      this.setFormRules()
    },
    setFormRules () {
      this.form = reactive({})
      this.rules = reactive({})
      this.currentAction.fieldParams.forEach(field => {
        this.rules[field.name] = []
        const rule = {}
        rule.required = field.required
        if (field.type === 'uuid') {
          rule.message = this.$t('message.error.select')
        } else {
          rule.message = this.$t('message.error.required.input')
        }
        this.rules[field.name].push(rule)
      })
    },
    listFieldOpts (field) {
      const paramName = field.name
      const params = { listall: true }
      const possibleName = 'list' + paramName.replace('ids', '').replace('id', '').toLowerCase() + 's'
      let possibleApi
      for (const api in store.getters.apis) {
        if (api.toLowerCase().startsWith(possibleName)) {
          possibleApi = api
          break
        }
      }
      if (this.currentAction.mapping) {
        Object.keys(this.currentAction.mapping).forEach(key => {
          if (this.currentAction.mapping[key].params) {
            params[key] = this.currentAction.mapping[key].params(this.resource)
          }
        })
      }
      if (!possibleApi) {
        return
      }
      field.loading = true
      field.opts = []
      postAPI(possibleApi, params).then(json => {
        field.loading = false
        for (const obj in json) {
          if (obj.includes('response')) {
            for (const res in json[obj]) {
              if (res === 'count') {
                continue
              }
              field.opts = json[obj][res]
              break
            }
            break
          }
        }
      }).catch(error => {
        console.log(error.stack)
        field.loading = false
      })
    },
    async executeConfirmAction () {
      if (this.actionLoading || !this.actionTarget?.id || this.actionTarget.name !== this.activeProviderName || this.actionTarget.physicalnetworkid !== this.resource.id) return
      const params = { id: this.actionTarget.id }
      if (this.currentAction.mapping) {
        for (const key in this.currentAction.mapping) {
          if (!this.currentAction.mapping[key].value) {
            continue
          }
          params[key] = this.currentAction.mapping[key].value(this.resource, params)
        }
      }
      this.actionLoading = true

      try {
        const hasJobId = await this.executeApi(this.currentAction.api, params)
        if (!hasJobId) {
          await this.fetchData()
        }
        this.actionLoading = false
        this.onCloseAction()
      } catch (message) {
        this.actionLoading = false
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: message
        })
      }
    },
    async executeApi (apiName, args) {
      const action = this.currentAction
      const target = this.actionTarget
      try {
        const json = await postAPI(apiName, args, { preserveOnFailure: true })
        const job = Object.entries(json).find(([key, response]) =>
          (key.includes('response') || key.includes(apiName)) && response?.jobid)?.[1]
        if (!job) return false
        if (apiName === 'updateNetworkServiceProvider' && args.state) {
          const result = await this.$pollJob({
            jobId: job.jobid,
            title: this.$t(action.label),
            name: target.name,
            showLoading: false
          })
          if (result.jobstatus !== 1) throw new Error(result.jobresult?.errortext || this.$t('message.job.result.unknown'))
          return false
        }
        this.parentPollActionCompletion(job.jobid, action, target.name).then(() => {
          if (target.physicalnetworkid === this.resource.id) this.fetchData()
        })
        return true
      } catch (error) {
        throw Object.values(error.response?.data || {}).find(response => response?.errortext)?.errortext ||
          error.response?.headers?.['x-description'] || error.message
      }
    }
  }
}
</script>

<style scoped>
.service-filter,
.service-provider-selector {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.service-filter :deep(.ant-select) { width: 8rem; }
.service-provider-selector { margin-bottom: 16px; }
.service-provider-selector :deep(.ant-select) { width: min(100%, 26rem); }
.service-directory :deep(td),
.provider-details {
  overflow-wrap: anywhere;
}
.service-directory :deep(.ant-btn-link) {
  height: auto;
  min-height: 32px;
  max-width: 100%;
  padding-inline: 0;
  white-space: normal;
  text-align: start;
}
</style>
