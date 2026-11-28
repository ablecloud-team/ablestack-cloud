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
  <div class="kubernetes-summary-item"><strong>{{ $t('label.autoscale.members.range') }}</strong><div>{{ $t('label.autoscale.range', { min: form.minmembers || '—', max: form.maxmembers || '—' }) }}</div></div>
  <div class="kubernetes-summary-item"><strong>{{ $t('label.interval') }}</strong><div>{{ form.interval || '—' }} {{ $t('label.seconds') }}</div></div>
  <div class="kubernetes-summary-item"><strong>{{ $t('label.loadbalancerrule') }}</strong><div>{{ loadbalancer.name || '—' }}</div></div>
  <div class="kubernetes-summary-item"><strong>{{ $t('label.autoscale.port.mapping') }}</strong><div>{{ loadbalancer.publicip || '—' }}:{{ loadbalancer.publicport ?? '—' }} → {{ loadbalancer.privateport ?? '—' }}</div></div>
</template>
<script>
export default {
  name: 'AutoScaleCreationSummary',
  props: { form: { type: Object, required: true }, loadbalancer: { type: Object, default: () => ({}) } }
}
</script>
