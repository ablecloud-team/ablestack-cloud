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
  <kubernetes-create-summary :name="name" :icon="icon" :items="rows">
    <slot />
  </kubernetes-create-summary>
</template>

<script>
import KubernetesCreateSummary from '@/components/view/KubernetesCreateSummary.vue'

export default {
  name: 'ResourceCreateSummaryFields',
  components: { KubernetesCreateSummary },
  props: {
    resource: { type: Object, required: true },
    name: { type: String, default: '' },
    icon: { type: String, required: true },
    architecture: { type: String, default: '' }
  },
  computed: {
    rows () {
      const resource = this.resource
      const networks = (resource.networks || []).map(network => network.name).filter(Boolean).join(', ')
      const diskSize = resource.disksizetotalgb
      return [
        { label: 'label.zoneid', value: resource.zonename },
        { label: 'label.arch', value: this.architecture || resource.arch },
        { label: 'label.image', value: resource.isodisplaytext || resource.isoname || resource.templatename },
        { label: 'label.ostypeid', value: resource.ostypename },
        { label: 'label.serviceofferingid', value: resource.serviceofferingname },
        { label: 'label.rootdisk', value: resource.rootdiskofferingdisplaytext },
        { label: 'label.data.disk', value: resource.datadiskofferingdisplaytext || resource.diskofferingname },
        { label: 'label.disk.size', value: diskSize ? (typeof diskSize === 'number' ? diskSize + ' GB' : diskSize) : undefined },
        { label: 'label.networks', value: networks }
      ]
    }
  }
}
</script>
