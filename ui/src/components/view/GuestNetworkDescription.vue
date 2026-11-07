<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may
obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS. -->
<template>
  <div class="guest-network-description" :data-description-view="summary ? 'summary' : 'details'">
    <div v-if="summary" class="resource-detail-item__label">{{ $t('label.displaytext') }}</div>
    <div class="guest-network-description__text">{{ description }}</div>
  </div>
</template>

<script>
export default {
  name: 'GuestNetworkDescription',
  props: { value: { type: String, default: '' }, summary: { type: Boolean, default: false } },
  computed: {
    description () { return this.value?.trim() ? this.value : '—' }
  }
}
</script>

<style scoped>
.guest-network-description { min-width: 0; max-width: 100%; }
.resource-detail-item__label { margin-bottom: 5px; font-weight: 700; }
.guest-network-description__text { white-space: pre-wrap; overflow-wrap: anywhere; color: inherit; }
</style>
