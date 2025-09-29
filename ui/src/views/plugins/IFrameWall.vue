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
  <div>
    <iframe :src="uriInfo" width="100%" frameBorder="0" style="height: 90vh">
    </iframe>
  </div>
</template>

<script>
import { getAPI } from '@/api'
export default {
  name: 'IFrameWall',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      uriInfo: ''
    }
  },
  created () {
    this.urlAction(this.resource.hypervisortype)
  },
  methods: {
    urlAction (hypervisortype) {
      const theme = this.$localStorage.get('DARK_MODE') ? '&theme=dark' : '&theme=light'

      getAPI('listConfigurations', { keyword: 'monitoring.wall.portal' }).then(json => {
        var items = json.listconfigurationsresponse.configuration
        var wallPortalProtocol = items.filter(x => x.name === 'monitoring.wall.portal.protocol')[0]?.value
        const wallPortalPort = items.filter(x => x.name === 'monitoring.wall.portal.port')[0]?.value
        var wallPortalDomain = items.filter(x => x.name === 'monitoring.wall.portal.domain')[0]?.value
        if (wallPortalDomain === null || wallPortalDomain === '') {
          wallPortalDomain = this.$store.getters.features.host
        }
        var uri = wallPortalProtocol + '://' + wallPortalDomain + ':' + wallPortalPort
        if (typeof hypervisortype !== 'undefined' && hypervisortype !== null && hypervisortype !== '') {
          const clusterUriPath = items.filter(x => x.name === 'monitoring.wall.portal.cluster.uri')[0]?.value
          this.uriInfo = uri + clusterUriPath + theme
        } else {
          const hostUriPath = items.filter(x => x.name === 'monitoring.wall.portal.host.uri')[0]?.value
          this.uriInfo = uri + hostUriPath + '&var-host=' + this.resource.ipaddress + theme
        }
        this.uriCreateOk = true
      })
    }
  }
}
</script>
