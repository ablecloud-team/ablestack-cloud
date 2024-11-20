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
  <a-drawer v-if="accessibleEventActions && accessibleEventActions.length > 0">
    <template #overlay>
      <a-menu>
        <a-menu-item style="width: 100%; padding: 12px" v-for="menuItem in accessibleEventActions" :key="menuItem.api">
          <router-link :to="menuItem.route">
            <a-row>
              <a-col style="margin-right: 12px">
                <a-avatar :style="{ backgroundColor: $config.theme['@primary-color'] }">
                  <template #icon>
                    <render-icon v-if="(typeof menuItem.icon === 'string')" :icon="menuItem.icon" />
                    <font-awesome-icon v-else :icon="menuItem.icon" />
                  </template>
                </a-avatar>
              </a-col>
              <a-col>
                <h3 style="margin-bottom: 0px;">
                  {{ menuItem.title }}
                </h3>
                <small>{{ menuItem.subtitle }}</small>
              </a-col>
            </a-row>
          </router-link>
        </a-menu-item>
      </a-menu>
    </template>
    <a-button type="primary">
      {{ $t('label.event') }}
      <DownOutlined />
    </a-button>
  </a-drawer>
</template>

<script>
export default {
  name: 'CreateEvents',
  beforeCreate () {
    const eventItems = [
      {
        api: 'createEvent',
        title: this.$t('label.event'),
        subtitle: this.$t('label.create.event'),
        icon: 'calendar-outlined',
        route: { path: '/event', query: { action: 'createEvent' } }
      },
      {
        api: 'scheduleEvent',
        title: this.$t('label.schedule'),
        subtitle: this.$t('label.schedule.event'),
        icon: 'clock-circle-outlined',
        route: { path: '/event', query: { action: 'scheduleEvent' } }
      }
    ]
    this.accessibleEventActions = eventItems.filter(m => m.api in this.$store.getters.apis)
  }
}
</script>
