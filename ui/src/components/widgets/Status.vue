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
  <div style="display: inline-flex;">
    <a-tooltip placement="bottom" :title="getTooltip(text)">
      <a-badge
        :style="getStyle()"
        :title="text"
        :color="getStatusColor(text)"
        :status="getBadgeStatus(text)"
        :text="getText()" />
    </a-tooltip>
  </div>
</template>

<script>

export default {
  name: 'Status',
  props: {
    text: {
      type: String,
      required: true
    },
    displayText: {
      type: Boolean,
      default: false
    },
    styles: {
      type: Object,
      default: () => {}
    }
  },
  methods: {
    getText () {
      if (this.displayText && this.text) {
        var state = this.text
        switch (state.toLowerCase()) {
          case 'enabled':
            state = this.$t('state.enabled')
            break
          case 'disabled':
            state = this.$t('state.disabled')
            break
          case 'running':
            state = this.$t('state.running')
            break
          case 'stopped':
            state = this.$t('state.stopped')
            break
          case 'starting':
            state = this.$t('state.starting')
            break
          case 'stopping':
            state = this.$t('state.stopping')
            break
          case 'suspended':
            state = this.$t('state.suspended')
            break
          case 'pending':
            state = this.$t('state.pending')
            break
          case 'migrating':
            state = this.$t('state.migrating')
            break
          case 'expunging':
            state = this.$t('state.expunging')
            break
          case 'error':
            state = this.$t('state.error')
            break
          case 'readonly':
            state = this.$t('state.readonly')
            break
          case 'readwrite':
            state = this.$t('state.readwrite')
            break
          case 'inprogress':
            state = this.$t('state.inprogress')
            break
          case 'down':
            state = this.$t('state.down')
            break
          case 'up':
            state = this.$t('state.up')
            break
          case 'prepareformaintenance':
            state = this.$t('state.prepareformaintenance')
            break
          case 'maintenance':
            state = this.$t('state.maintenance')
            break
          case 'active':
            state = this.$t('state.enabled')
            break
          case 'inactive':
            state = this.$t('state.disabled')
            break
          case 'disconnected':
            state = this.$t('state.disconnected')
            break
          case 'connecting':
            state = this.$t('state.connecting')
            break
          case 'ready':
            state = this.$t('state.ready')
            break
          case 'not ready':
            state = this.$t('state.notready')
            break
          case 'setup':
            state = this.$t('state.setup')
            break
          case 'allocated':
            state = this.$t('state.allocated')
            break
          case 'free':
            state = this.$t('state.free')
            break
          case 'destroyed':
            state = this.$t('state.destroyed')
            break
          case 'snapshotting':
            state = this.$t('state.snapshotting')
            break
          case 'backedup':
            state = this.$t('state.backedup')
            break
          case 'backingup':
            state = this.$t('state.backingup')
            break
          case 'destroying':
            state = this.$t('state.destroying')
            break
          case 'scheduled':
            state = this.$t('state.scheduled')
            break
          case 'started':
            state = this.$t('state.started')
            break
          case 'completed':
            state = this.$t('state.completed')
            break
        }
        return state.charAt(0).toUpperCase() + state.slice(1)
      }
      return ''
    },
    getBadgeStatus (state) {
      var status = 'default'
      switch (state.toLowerCase()) {
        case 'active':
        case 'backedup':
        case 'completed':
        case 'connected':
        case 'download complete':
        case 'enabled':
        case 'implemented':
        case 'on':
        case 'readwrite':
        case 'ready':
        case 'running':
        case 'setup':
        case 'started':
        case 'successfully installed':
        case 'true':
        case 'up':
        case 'success':
        case 'poweron':
        case 'syncing':
          status = 'success'
          break
        case 'alert':
        case 'declined':
        case 'disabled':
        case 'disconnected':
        case 'down':
        case 'error':
        case 'false':
        case 'off':
        case 'readonly':
        case 'poweroff':
        case 'stopped':
        case 'failed':
          status = 'error'
          break
        case 'migrating':
        case 'scaling':
        case 'starting':
        case 'stopping':
        case 'upgrading':
        case 'inprogress':
        case 'connecting':
        case 'prepareformaintenance':
        case 'snapshotting':
        case 'backingup':
        case 'destroying':
          status = 'processing'
          break
        case 'allocated':
          if (this.$route.path.startsWith('/publicip') || this.$route.path.startsWith('/guestvlans')) {
            status = 'success'
          } else {
            status = 'warning'
          }
          break
        case 'created':
        case 'maintenance':
        case 'pending':
        case 'unsecure':
        case 'warning':
        case 'free':
        case 'scheduled':
          status = 'warning'
          break
      }
      return status
    },
    getStatusColor (state) {
      switch (state.toLowerCase()) {
        case 'scheduled':
          return 'orange'
        case 'started':
          return 'blue'
        case 'reserved':
          return 'orange'
        default:
          return null
      }
    },
    getTooltip (state) {
      if (!(state && this.displayText)) {
        return ''
      }
      let result
      if (this.$route.path === '/vmsnapshot' || this.$route.path.includes('/vmsnapshot/')) {
        result = this.$t('message.vmsnapshot.state.' + state.toLowerCase())
      } else if (this.$route.path === '/vm' || this.$route.path.includes('/vm/')) {
        result = this.$t('message.vm.state.' + state.toLowerCase())
      } else if (this.$route.path === '/volume' || this.$route.path.includes('/volume/')) {
        result = this.$t('message.volume.state.' + state.toLowerCase())
      } else if (this.$route.path === '/guestnetwork' || this.$route.path.includes('/guestnetwork/')) {
        result = this.$t('message.guestnetwork.state.' + state.toLowerCase())
      } else if (this.$route.path === '/publicip' || this.$route.path.includes('/publicip/')) {
        result = this.$t('message.publicip.state.' + state.toLowerCase())
      }

      if (!result || (result.startsWith('message.') && result.endsWith('.state.' + state.toLowerCase()))) {
        // Nothing for snapshots, vpcs, gateways, vnpnconn, vpnuser, kubectl, event, project, account, infra. They're all self explanatory
        result = this.$t(state)
      }
      return result
    },
    getStyle () {
      let styles = { display: 'inline-flex' }
      if (this.styles && typeof this.styles === 'object') {
        styles = Object.assign({}, styles, this.styles)
      }

      return styles
    }
  }
}
</script>

<style scoped lang="less">
:deep(.ant-badge-status-dot) {
  width: 12px;
  height: 12px;
  margin-top: 5px;
}

.status {
  margin-top: -5px;

  &--end {
    margin-left: 5px;
  }
}
</style>
