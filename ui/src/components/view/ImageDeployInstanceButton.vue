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
  <span>
    <a-dropdown-button
      type="primary"
      :loading="loading"
      :visible="zoneMenuVisible"
      @visibleChange="zoneMenuVisible = $event"
      @click="handleMainClick"
      v-if="allowed"
      :disabled="!zones || zones.length === 0">
        <rocket-outlined />
        {{ $t('label.create.vm') }}
        <template #icon><down-outlined /></template>
        <template #overlay>
          <a-menu type="primary"  @click="handleDeployInstanceMenu">
              <a-menu-item v-for="zone in zones" :key="zone.id">
              <span v-if="zone.icon && zone.icon.base64image">
                  <resource-icon :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
              </span>
              <global-outlined v-else style="margin-right: 5px" />
              {{ zone.name }}
              </a-menu-item>
          </a-menu>
        </template>
    </a-dropdown-button>
  </span>
</template>

<script>
import { getAPI } from '@/api'
import { readTemplateResource } from '@/utils/templateSearch'
import ResourceIcon from '@/components/view/ResourceIcon'

export default {
  name: 'ImageDeployInstanceButton',
  components: {
    ResourceIcon
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    osCategoryId: {
      type: String,
      default: null
    }
  },
  emits: ['update-zones'],
  data () {
    return {
      imageApi: 'listTemplates',
      loading: false,
      zones: [],
      zoneMenuVisible: false,
      requestVersion: 0
    }
  },
  mounted () {
    if (this.$route.meta.name === 'iso') {
      this.imageApi = 'listIsos'
    }
    this.fetchData()
  },
  beforeUnmount () { this.requestVersion++ },
  watch: {
    readScope () { this.fetchData() }
  },
  computed: {
    readScope () {
      const g = this.$store.getters
      return JSON.stringify([this.resource?.id, this.resource?.isready, this.resource?.bootable, this.$route.meta.name, g.userInfo?.id, g.userInfo?.roletype, g.userInfo?.roleid, g.project?.id, g.listAllProjects, this.$store.state?.user?.token, Object.keys(g.apis || {}).sort()])
    },
    allowed () {
      return 'deployVirtualMachine' in (this.$store.getters.apis || {}) &&
        (this.$route.meta.name === 'template' || (this.$route.meta.name === 'iso' && this.resource?.bootable)) && !!this.resource?.isready
    }
  },
  methods: {
    fetchData () {
      this.fetchResourceData()
    },
    async fetchResourceData () {
      const version = ++this.requestVersion; const scope = this.readScope
      const current = () => version === this.requestVersion && scope === this.readScope
      this.zoneMenuVisible = false; this.zones = []; this.loading = false
      if (!this.resource?.id || !this.allowed) return
      this.loading = true
      const params = { id: this.resource.id, listall: true }
      try {
        const json = this.imageApi === 'listTemplates'
          ? await readTemplateResource(getAPI, params, this.$store.getters, current)
          : await getAPI(this.imageApi, { ...params, isofilter: 'executable' })
        if (!current()) return
        const rows = json?.[this.imageApi.toLowerCase() + 'response']?.[this.$route.meta.name] || []
        this.zones = [...new Map(rows.filter(i => i.zoneid && i.isready !== false).map(i => [i.zoneid, { id: i.zoneid, name: i.zonename }])).values()]
        if (!this.zones.length) return
        this.$emit('update-zones', this.zones)
        const icons = await getAPI('listZones', { showicon: true, ids: this.zones.map(z => z.id).join(',') })
        if (!current()) return
        const byId = new Map((icons.listzonesresponse?.zone || []).map(z => [z.id, z]))
        this.zones = this.zones.map(z => ({ ...z, icon: byId.get(z.id)?.icon }))
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.loading = false
      }
    },
    handleMainClick () {
      if (!this.allowed || this.loading || !this.zones.length) return
      if (this.zones.length === 1) this.handleDeployInstanceMenu({ key: this.zones[0].id })
      else this.zoneMenuVisible = true
    },
    handleDeployInstanceMenu (e) {
      if (!this.allowed || this.loading || !this.zones.some(z => z.id === e.key)) return
      this.zoneMenuVisible = false
      const query = { zoneid: e.key }
      query[this.$route.meta.name + 'id'] = this.resource.id
      if (this.resource.arch) {
        query.arch = this.resource.arch
      }
      if (this.osCategoryId) {
        query.oscategoryid = this.osCategoryId
      }
      this.$router.push({
        path: '/action/deployVirtualMachine',
        query: query
      })
    }
  }
}
</script>
