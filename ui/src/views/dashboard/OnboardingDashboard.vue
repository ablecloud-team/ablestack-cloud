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
  <div class="onboarding">
    <div class="head">
      <h2>{{ $t('label.installwizard.title') }}</h2>
      <h3>{{ $t('label.installwizard.subtitle') }}</h3>
    </div>
    <div class="body">
      <div class="intro">
        <div class="title">{{ $t('label.what.is.cloudstack') }}</div>
        <div class="subtitle">{{ $t('label.introduction.to.cloudstack') }}</div>
        <p>{{ $t('message.installwizard.copy.whatiscloudstack') }}</p>
        <img class="center" src="assets/bg-what-is-ablestack.png">
        <a-button @click="() => { this.step = 1 }" type="primary">
          {{ $t('label.continue.install') }}
          <double-right-outlined />
        </a-button>
      </div>
    </div>
    <a-modal
      :title="$t('label.installwizard.addzoneintro.title')"
      :visible="step === 1"
      :closable="true"
      :maskClosable="false"
      :footer="null"
      @cancel="closeAction"
      centered
      width="auto">
      <zone-wizard
        @close-action="closeAction"
        @refresh-data="parentFetchData" />
    </a-modal>
  </div>
</template>

<script>
import ZoneWizard from '@/views/infra/zone/ZoneWizard.vue'

export default {
  name: 'OnboardingDashboard',
  components: {
    ZoneWizard
  },
  inject: ['parentFetchData'],
  data () {
    return {
      step: 0,
      cloudstackminorversion: this.$store.getters.features.cloudstackversion.split('-')[0]
    }
  },
  methods: {
    closeAction () {
      this.step = 0
    }
  }
}
</script>

<style scoped lang="scss">

.onboarding {
  font-family: sans-serif;
  padding: 20px 10px 50px 10px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  @media (min-width: 760px) {
    padding: 20px 10% 50px 10%;
  }
}

.head {
  text-align: center;
}

h2 {
  font-size: 28px;
}

h3 {
  font-size: 20px;
}

.body {
  padding: 56px 20px 20px 20px;
}

.title {
  margin: auto auto 30px;
  font-size: 22px;
}

.subtitle {
  font-size: 12px;
  font-weight: bold;
}

p {
  font-family: sans-serif;
  text-align: justify;
  font-size: 15px;
  line-height: 23px;
  white-space: pre-line;
}

pre {
  font-family: sans-serif;
  text-align: justify;
  font-size: 15px;
  white-space: pre-wrap;
  margin: 0px 0px;
}

.center {
  display: block;
  margin-left: auto;
  margin-right: auto;
  margin-bottom: 10px;
  text-align: center;
}

button {
  float: right;
}
</style>
