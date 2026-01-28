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
import { api } from '@/api'
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
    this.urlAction()
  },
  methods: {
    urlAction () {
      const port = 7077
      const zoneId = this.resource.id

      api('listCapabilities').then(json => {
        const host = json.listcapabilitiesresponse.capability.host || []
        this.uriInfo = `http://${host}:${port}/index.html?zone_id=${zoneId}`
        this.uriCreateOk = true
      }).catch(err => {
        console.error('capabilities API 실패:', err)
        this.uriInfo = ''
      })
    }
  }
}
</script>
