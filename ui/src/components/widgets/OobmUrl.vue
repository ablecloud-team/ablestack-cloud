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
  <a
    v-if="['host'].includes($route.meta.name) && 'listHostsMetrics' in $store.getters.apis"
    :href="resource.details?.manageconsoleprotocol+'://'+resource.outofbandmanagement?.address+':'+resource.details?.manageconsoleport"
    target="_blank">
    <a-button style="margin-left: 5px" shape="circle" type="" :size="size" :disabled="resource.details?.manageconsoleport == undefined" >
      <LaptopOutlined />
    </a-button>
  </a>
</template>
<script>
export default {
  name: 'OobmUrl',
  props: {
    resource: {
      type: Object,
      required: true,
      default: () => ({})
    },
    size: {
      type: String,
      default: 'small'
    }
  }
}
</script>
