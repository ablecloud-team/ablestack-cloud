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
  <aside class="kubernetes-create-summary">
    <div class="network-form-group-label">{{ $t('label.kubernetes.ui.summary') }}</div>
    <div class="kubernetes-summary-name"><render-icon :svgIcon="kubernetesIcon" />{{ name || '—' }}</div>
    <a-divider />
    <div v-for="item in items" :key="item.label" class="kubernetes-summary-item"><strong>{{ $t(item.label) }}</strong><div>{{ item.value === undefined || item.value === null || item.value === '' ? '—' : item.value }}</div></div>
  </aside>
</template>
<script>
import kubernetesIcon from '@/assets/icons/kubernetes.svg?inline'
import RenderIcon from '@/utils/renderIcon'
export default { name: 'KubernetesCreateSummary', components: { RenderIcon }, props: { name: { type: String, default: '' }, items: { type: Array, default: () => [] } }, data () { return { kubernetesIcon } } }
</script>
