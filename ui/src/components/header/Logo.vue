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
  <div v-if="collapsed && $config.minilogo">
    <img
      :style="{
        width: $config.theme['@mini-logo-width'],
        height: $config.theme['@mini-logo-height'],
        marginTop: $config.theme['@mini-logo-magin-top'],
        marginBottom: $config.theme['@mini-logo-magin-bottom']
      }"
      :src="$config.minilogo" />
  </div>
  <div v-else-if="$config.logo">
    <img
      :style="{
        width: $config.theme['@logo-width'],
        height: $config.theme['@logo-height'],
        marginLeft: '8px',
        marginRight: '8px',
        marginTop: $config.theme['@logo-magin-top'],
        marginBottom: $config.theme['@logo-magin-bottom']
      }"
      :src="$config.logo" />
  </div>
</template>

<script>

export default {
  name: 'Logo',
  components: {
  },
  props: {
    title: {
      type: String,
      default: 'CloudStack',
      required: false
    },
    showTitle: {
      type: Boolean,
      default: true,
      required: false
    },
    collapsed: {
      type: Boolean,
      default: false,
      required: false
    }
  }
}
</script>
