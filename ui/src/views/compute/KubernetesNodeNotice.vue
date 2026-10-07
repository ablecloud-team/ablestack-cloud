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
  <a-alert v-if="cluster" type="warning" show-icon>
    <template #message>{{ $t('message.kubernetes.node.maintenance') }}</template>
    <template #description>
      <router-link :to="'/kubernetes/' + cluster.id">{{ cluster.name }}</router-link>
      <p>{{ $t('message.kubernetes.node.drain') }}</p>
      <p>{{ $t('message.kubernetes.node.recovery') }}</p>
    </template>
  </a-alert>
  <a-alert v-else-if="failed" type="warning" show-icon :message="$t('message.kubernetes.node.lookup.failed')" />
</template>
<script>
import { getAPI } from '@/api'
import { listKubernetesNetworkResources } from '@/utils/kubernetesLoadBalancers'
export default {
  name: 'KubernetesNodeNotice',
  props: { resource: { type: Object, required: true } },
  data () { return { cluster: null, failed: false, request: 0 } },
  watch: { 'resource.id' () { this.fetchCluster() } },
  created () { this.fetchCluster() },
  beforeUnmount () { this.request++ },
  methods: {
    async fetchCluster () {
      const request = ++this.request
      const resource = { ...this.resource }
      this.cluster = null
      this.failed = false
      if (!resource.id || !this.$store.getters.apis.listKubernetesClusters) return
      const scope = resource.projectid ? { projectid: resource.projectid } : { account: resource.account, domainid: resource.domainid }
      try {
        const clusters = await listKubernetesNetworkResources(getAPI, 'listKubernetesClusters', 'listkubernetesclustersresponse', 'kubernetescluster', scope)
        if (request === this.request && resource.id === this.resource.id) this.cluster = clusters.find(cluster => cluster.clustertype === 'CloudManaged' && (cluster.virtualmachines || []).some(vm => vm.id === resource.id)) || null
      } catch (_) { if (request === this.request && resource.id === this.resource.id) this.failed = true }
    }
  }
}
</script>
