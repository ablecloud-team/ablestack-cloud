<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<template>
  <div class="autogen-action-dropdown__trigger" :style="triggerStyle">
    <a-dropdown
      v-model:visible="open"
      :trigger="['click']"
      placement="bottomRight"
      overlayClassName="autogen-action-dropdown">
      <template #overlay>
        <div class="autogen-action-dropdown__content">
          <dr-action-menu-content
            :actions="actions"
            :resource="resource"
            @exec-action="execAction" />
        </div>
      </template>
      <a-button type="primary" shape="round" class="autogen-action-dropdown__button">
        <template #icon><DownOutlined /></template>
        {{ $t('label.actions') }}
      </a-button>
    </a-dropdown>
  </div>
</template>

<script>
import DrActionMenuContent from '@/components/dr/DrActionMenuContent.vue'

export default {
  name: 'DrResourceActionMenu',
  components: {
    DrActionMenuContent
  },
  props: {
    actions: {
      type: Array,
      default: () => []
    },
    resource: {
      type: Object,
      default: () => ({})
    },
    triggerStyle: {
      type: Object,
      default: () => ({})
    }
  },
  emits: ['exec-action'],
  data () {
    return {
      open: false
    }
  },
  methods: {
    execAction (action) {
      this.open = false
      this.$emit('exec-action', action, action.resource || this.resource)
    }
  }
}
</script>
