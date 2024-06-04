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

import { shallowRef, defineAsyncComponent } from 'vue'
import store from '@/store'

export default {
  name: 'disasterrecoverycluster',
  title: 'label.disaster.recovery.clusters',
  resourceType: 'DisasterRecoveryCluster',
  icon: 'SubnodeOutlined',
  permission: ['getDisasterRecoveryClusterList'],
  columns: ['name', 'drclustertype', 'drclusterurl', 'drclusterstatus', 'mirroringagentstatus'],
  details: ['name', 'id', 'description', 'drclustertype', 'drclusterurl', 'drclusterstatus', 'mirroringagentstatus'],
  tabs: [
    {
      name: 'details',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
    },
    {
      name: 'instance',
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/DisasterRecoveryClusterVmTab.vue')))
    },
    {
      name: 'settings',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailSettings.vue')))
    },
    {
      name: 'events',
      resourceType: 'DisasterRecoveryCluster',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
      show: () => { return 'listEvents' in store.getters.apis }
    },
    {
      name: 'comments',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/AnnotationsTab.vue')))
    }
  ],
  actions: [
    {
      api: 'addImageStore',
      icon: 'plus-outlined',
      docHelp: '',
      label: 'label.add.disaster.recovery.cluster',
      listView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/DisasterRecoveryClusterAdd.vue')))
    },
    {
      api: 'updateDisasterRecoveryCluster',
      icon: 'edit-outlined',
      label: 'label.edit.disaster.recovery.cluster',
      dataView: true,
      args: ['name', 'description', 'drclusterurl']
    },
    {
      api: 'updateDisasterRecoveryCluster',
      icon: 'play-circle-outlined',
      label: 'label.action.enable.disaster.recovery.cluster',
      message: 'message.enabled.dr.cluster.confirm',
      dataView: true,
      popup: true,
      show: (record) => { return record.drclusterstatus === 'Disabled' },
      args: ['drclusterstatus'],
      mapping: {
        drclusterstatus: {
          value: (record) => 'Enabled'
        }
      }
    },
    {
      api: 'updateDisasterRecoveryCluster',
      icon: 'pause-circle-outlined',
      label: 'label.action.disable.disaster.recovery.cluster',
      message: 'message.disabled.dr.cluster.confirm',
      dataView: true,
      popup: true,
      show: (record) => { return record.drclusterstatus === 'Enabled' },
      args: ['drclusterstatus'],
      mapping: {
        drclusterstatus: {
          value: (record) => 'Disabled'
        }
      }
    },
    {
      api: 'updateDisasterRecoveryCluster',
      icon: 'UpSquareOutlined',
      label: 'label.action.promote.disaster.recovery.cluster',
      message: 'message.promote.dr.cluster.confirm',
      dataView: true,
      popup: true,
      show: (record) => { return record.drclustertype === 'primary' }
    },
    {
      api: 'updateDisasterRecoveryCluster',
      icon: 'DownSquareOutlined',
      label: 'label.action.demote.disaster.recovery.cluster',
      message: 'message.demote.dr.cluster.confirm',
      dataView: true,
      popup: true,
      show: (record) => { return record.drclustertype === 'primary' }
    },
    {
      api: 'deleteDisasterRecoveryCluster',
      icon: 'delete-outlined',
      label: 'label.action.delete.disaster.recovery.cluster',
      message: 'message.delete.dr.cluster.confirm',
      dataView: true
    }
  ]
}
