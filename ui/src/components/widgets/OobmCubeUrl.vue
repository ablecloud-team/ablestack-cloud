// Licensed to the Apache Software Foundation (ASF) under one // or more contributor license agreements. See the NOTICE
file // distributed with this work for additional information // regarding copyright ownership. The ASF licenses this
file // to you under the Apache License, Version 2.0 (the // "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at // // http://www.apache.org/licenses/LICENSE-2.0 // //
Unless required by applicable law or agreed to in writing, // software distributed under the License is distributed on
an // "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY // KIND, either express or implied. See the License for the
// specific language governing permissions and limitations // under the License.

<template>
  <a :href="uriInfo" target="_blank">
    <a-button
      v-if="scope === 'oobm'"
      style="margin-left: 5px"
      shape="circle"
      type=""
      :size="size"
      :disabled="resource.details?.manageconsoleport == undefined"
    >
      <LaptopOutlined />
    </a-button>
    <a-button v-if="scope === 'cube'" style="margin-left: 5px" shape="circle" type="" :size="size">
      <BankOutlined />
    </a-button>
  </a>
</template>
<script>
export default {
  name: 'OobmUrl',
  props: {
    resource: {
      type: Object,
      required: true
    },
    scope: {
      type: String,
      default: 'oobm'
    },
    size: {
      type: String,
      default: 'small'
    }
  },
  data () {
    return {
      uriCreateOk: false,
      uriInfo: ''
    }
  },
  created () {
    this.urlAction()
  },
  methods: {
    urlAction () {
      if (this.scope === 'oobm') {
        this.uriInfo = this.resource.details?.manageconsoleprotocol + '://' + this.resource.outofbandmanagement?.address + ':' + this.resource.details?.manageconsoleport
      } else if (this.scope === 'cube') {
        this.uriInfo = 'https://' + this.resource.ipaddress + ':9090'
      }
    }
  }
}
</script>
