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
  <div :class="['']">
    <div class="line">
    </div>
  </div>
</template>

<script>
import semver from 'semver'
import { getParsedVersion } from '@/utils/util'

export default {
  name: 'LayoutFooter',
  data () {
    return {
      buildVersion: this.$config.buildVersion,
      isSidebarVisible: false
    }
  },
  created () {
    // 젠킨스를 이용한 빌드가 아닐 경우 버전 및 빌드 날짜 표시하기 위함.
    // if (this.buildVersion === '') {
    //   const version = 'Bronto-v2.0.1'
    //   const m = new Date()
    //   const date = m.getFullYear() + ('0' + (m.getMonth() + 1)).slice(-2) + ('0' + m.getDate()).slice(-2)
    //   this.buildVersion = version + '-' + date + '-dev'
    // }
  },
  methods: {
    toggleSidebar () {
      this.isSidebarVisible = !this.isSidebarVisible
    },
    showVersionUpdate () {
      if (this.$store.getters?.features?.cloudstackversion && this.$store.getters?.latestVersion?.version) {
        const currentVersion = getParsedVersion(this.$store.getters?.features?.cloudstackversion)
        const latestVersion = getParsedVersion(this.$store.getters?.latestVersion?.version)
        return semver.valid(currentVersion) && semver.valid(latestVersion) && semver.gt(latestVersion, currentVersion)
      }
      return false
    }
  }
}
</script>

<style lang="less" scoped>
  .footer {
    padding: 0 16px;
    margin: 48px 0 24px;
    text-align: center;
    transition: all 0.3s ease;

    &.expanded {
      margin-bottom: 324px;
    }

    .line {
      margin-bottom: 8px;
    }
    .copyright {
      font-size: 14px;
    }
  }
</style>
