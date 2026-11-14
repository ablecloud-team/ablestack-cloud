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
  <dl v-for="resource in resources.filter(row => row?.id)" :key="resource.id" class="vpc-action-context">
    <div><dt>{{ $t('label.name') }}</dt><dd>{{ resource.name || resource.ipaddress || resource.id }}</dd></div>
    <div><dt>{{ $t('label.id') }}</dt><dd>{{ resource.id }}</dd></div>
    <div v-if="resource.account || resource.project"><dt>{{ $t(resource.projectid ? 'label.project' : 'label.account') }}</dt><dd>{{ resource.project || resource.account }}<span v-if="resource.domain || resource.domainpath"> · {{ resource.domainpath || resource.domain }}</span></dd></div>
    <div v-if="resource.zonename || resource.zone"><dt>{{ $t('label.zone') }}</dt><dd>{{ resource.zonename || resource.zone }}</dd></div>
    <div v-if="resource.vpcid"><dt>{{ $t('label.vpc') }}</dt><dd><router-link :to="{ path: '/vpc/' + resource.vpcid, query: { tab: 'pgw' } }">{{ resource.vpcname || resource.vpcid }}</router-link></dd></div>
  </dl>
</template>
<script>
export default { name: 'VpcActionContext', props: { resources: { type: Array, default: () => [] } } }
</script>
<style scoped>
.vpc-action-context { margin: 0 0 16px; padding: 12px; border: 1px solid var(--ui-border); border-radius: 4px; background: var(--ui-bg-navigation); color: var(--ui-text-primary); }
.vpc-action-context div { display: grid; grid-template-columns: minmax(64px, auto) minmax(0, 1fr); gap: 12px; }
.vpc-action-context dt { font-weight: 600; }
.vpc-action-context dd { margin: 0; overflow-wrap: anywhere; }
</style>
