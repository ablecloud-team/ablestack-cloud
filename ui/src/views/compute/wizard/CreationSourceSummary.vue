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
  <div v-if="source" class="creation-source-summary">
    <strong>{{ $t('label.creation.source.selected') }}</strong>
    <p>{{ source.name }}<br><span class="source-meta">{{ source.id }}</span></p>
    <p>{{ $t('message.creation.source.' + source.sourcekind) }}</p>
    <p v-if="source.snapshotcreated">{{ $t('label.creation.source.restore.time') }}: {{ new Date(source.snapshotcreated).toLocaleString() }}</p>
    <p>ROOT · {{ (source.sizebytes / 1024 ** 3).toLocaleString() }} GiB · {{ source.sourcekind === 'volume' ? source.storage?.name || '—' : targetStorage.name || $t('label.vm.storage.auto') }}</p>
    <p v-if="source.sourcekind === 'volume' && source.storage?.clustername">{{ $t('label.cluster') }}: {{ source.storage.clustername }} · {{ source.storage.clusterid }}</p>
    <p>{{ source.bootprofile?.osname }} · {{ source.bootprofile?.boottype }} · {{ source.bootprofile?.bootmode }}<br>{{ $t('label.creation.source.root.bus') }}: {{ source.bootprofile?.rootbus }}</p>
    <p class="source-meta">{{ $t('message.creation.source.identity') }}</p>
    <p v-if="source.bootprofile?.provenance === 'legacy-template'" class="source-meta">{{ $t('message.creation.source.legacy') }}</p>
  </div>
</template>
<script>export default { props: { source: { type: Object, default: null }, targetStorage: { type: Object, default: () => ({}) } } }</script>
<style lang="less" scoped>
.creation-source-summary { color: var(--ui-text); margin: 16px 0; padding: 12px; border: 1px solid var(--ui-border); border-radius: 6px; background: var(--ui-bg-elevated); }
p { margin: 8px 0; overflow-wrap: anywhere; }
.source-meta { color: var(--ui-text-secondary); font-size: 12px; }
</style>
