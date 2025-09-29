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
  <a :href="uriInfo" target="_blank">
    <a-button style="margin-left: 5px" shape="circle" type="" :size="size" v-if="uriCreateOk">
      <AreaChartOutlined />
    </a-button>
  </a>
</template>
<script>
import { getAPI } from '@/api'
export default {
  name: 'WallLinkUrl',
  props: {
    resource: {
      type: Object,
      required: true
    },
    scope: {
      type: String,
      default: 'vm'
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
      getAPI('listConfigurations', { keyword: 'monitoring.wall.portal' }).then(json => {
        var items = json.listconfigurationsresponse.configuration
        var wallPortalProtocol = items.filter(x => x.name === 'monitoring.wall.portal.protocol')[0]?.value
        const wallPortalPort = items.filter(x => x.name === 'monitoring.wall.portal.port')[0]?.value
        var wallPortalDomain = items.filter(x => x.name === 'monitoring.wall.portal.domain')[0]?.value
        if (wallPortalDomain === null || wallPortalDomain === '') {
          wallPortalDomain = this.$store.getters.features.host
        }
        var uri = ''
        uri += wallPortalProtocol + '://' + wallPortalDomain + ':' + wallPortalPort
        if (this.scope === 'vm') {
          const vmUriPath = items.filter(x => x.name === 'monitoring.wall.portal.vm.uri')[0]?.value
          this.uriInfo = uri + vmUriPath + '&var-vm_uuid=' + this.resource.id
          this.uriCreateOk = true
        } else if (this.scope === 'host') {
          const hostUriPath = items.filter(x => x.name === 'monitoring.wall.portal.host.uri')[0]?.value
          this.uriInfo = uri + hostUriPath + '&var-host=' + this.resource.ipaddress
          this.uriCreateOk = true
        } else if (this.scope === 'cluster') {
          const clusterUriPath = items.filter(x => x.name === 'monitoring.wall.portal.cluster.uri')[0]?.value
          this.uriInfo = uri + clusterUriPath
          this.uriCreateOk = true
        }
      })
    }
  }
}
</script>
