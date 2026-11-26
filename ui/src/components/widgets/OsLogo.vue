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
  <a-tooltip placement="bottom">
    <template #title>
      {{ name }}
    </template>
    <font-awesome-icon
      :icon="logo"
      :size="size"
      :style="[$store.getters.darkMode ? { color: 'rgba(255, 255, 255, 0.65)' } : { color: '#666' }]"
      />
  </a-tooltip>
</template>

<script>
import { getAPI } from '@/api'

const CACHE_TTL_MS = 30_000
const osTypeCaches = new WeakMap()

function osTypeCacheFor (store) {
  const generation = store.state?.user?.discoveryGeneration
  const cached = osTypeCaches.get(store)
  if (cached && cached.generation === generation) return cached.types
  const types = new Map()
  osTypeCaches.set(store, { generation, types })
  return types
}

export default {
  name: 'OsLogo',
  props: {
    osId: {
      type: String,
      default: ''
    },
    osName: {
      type: String,
      default: ''
    },
    size: {
      type: String,
      default: 'lg'
    },
    useCache: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return { name: '', osLogo: ['fas', 'image'] }
  },
  computed: { logo () { return this.osLogo } },
  mounted () { this.fetchData() },
  watch: {
    osId () { this.fetchData() },
    osName () { this.fetchData() }
  },
  methods: {
    async fetchOsTypeName (osId, useCache = this.useCache) {
      const readName = () => getAPI('listOsTypes', { id: osId }).then(json => {
        const type = json?.listostypesresponse?.ostype?.[0]
        return type?.description || type?.osdisplayname || 'Linux'
      })
      if (!useCache) return readName()

      const cache = osTypeCacheFor(this.$store)
      const cached = cache.get(osId)
      if (cached?.value && (Date.now() - cached.ts) < CACHE_TTL_MS) return cached.value
      if (cached?.promise) return cached.promise
      const promise = readName().then(name => {
        cache.set(osId, { ts: Date.now(), value: name })
        return name
      }).catch(error => {
        cache.delete(osId)
        throw error
      })
      cache.set(osId, { promise })
      return promise
    },
    fetchData () {
      if (this.osName) {
        this.discoverOsLogo(this.osName)
      } else if (this.osId && ('listOsTypes' in this.$store.getters.apis)) {
        const id = this.osId
        const generation = this.$store.state?.user?.discoveryGeneration
        const showName = name => {
          if (id === this.osId && !this.osName && generation === this.$store.state?.user?.discoveryGeneration) this.discoverOsLogo(name)
        }
        this.fetchOsTypeName(id).then(showName).catch(() => showName('Linux'))
      }
    },
    discoverOsLogo (name) {
      this.name = name
      const osname = (name || '').toLowerCase()
      const logos = [
        { name: 'centos' },
        { name: 'debian' },
        { name: 'ubuntu' },
        { name: 'suse' },
        { name: 'redhat' },
        { name: 'fedora' },
        { name: 'linux' },
        { name: 'bsd', alternate: 'freebsd' },
        { name: 'apple' },
        { name: 'macos', alternate: 'apple' },
        { name: 'window', alternate: 'windows' },
        { name: 'dos', alternate: 'windows' },
        { name: 'oracle', alternate: 'java' }
      ]
      const match = logos.find(entry => osname.includes(entry.name))
      if (match) {
        this.osLogo = ['fab', match.alternate ? match.alternate : match.name]
        return
      }
      this.osLogo = ['fas', 'image']
    }
  }
}
</script>
