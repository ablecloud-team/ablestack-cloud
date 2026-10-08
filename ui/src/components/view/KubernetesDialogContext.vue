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
  <a-descriptions class="mold-dialog-summary" size="small" bordered :column="2">
    <a-descriptions-item :label="$t('label.name')">{{ resource.name || '—' }}</a-descriptions-item>
    <a-descriptions-item :label="$t('label.state')"><status :text="resource.state || ''" displayText /></a-descriptions-item>
    <a-descriptions-item :label="$t('label.clustertype')">{{ $t(resource.clustertype === 'ExternalManaged' ? 'label.external.managed' : 'label.cloud.managed') }}</a-descriptions-item>
    <a-descriptions-item :label="$t('label.kubernetesversionid')">{{ resource.kubernetesversionname || '—' }}</a-descriptions-item>
    <a-descriptions-item :label="$t('label.zoneid')" :span="2">{{ resource.zonename || '—' }} / {{ resource.project || resource.account || '—' }}</a-descriptions-item>
  </a-descriptions>
</template>
<script>
import Status from '@/components/widgets/Status'
export default { name: 'KubernetesDialogContext', components: { Status }, props: { resource: { type: Object, required: true } } }
</script>
