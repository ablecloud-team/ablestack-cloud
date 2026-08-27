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
  <a-config-provider :locale="locale">
    <div id="app">
      <router-view/>
    </div>
  </a-config-provider>
</template>

<script>
import enUS from 'ant-design-vue/lib/locale-provider/en_US'
import koKRSource from 'ant-design-vue/lib/locale-provider/ko_KR'
import { AppDeviceEnquire } from '@/utils/mixin'

const koKR = {
  ...koKRSource,
  Table: {
    ...koKRSource.Table,
    sortTitle: '정렬',
    triggerDesc: '내림차순으로 정렬',
    triggerAsc: '오름차순으로 정렬',
    cancelSort: '정렬 취소'
  }
}

export default {
  mixins: [AppDeviceEnquire],
  data () {
    return {
      configs: {}
    }
  },
  computed: {
    locale () {
      return this.$i18n.locale === 'ko_KR' ? koKR : enUS
    }
  },
  created () {
    window.less.modifyVars(this.$config.theme)
    console.log('config and theme applied')
  }
}
</script>
<style>
#app {
  height: 100%;
}
</style>
