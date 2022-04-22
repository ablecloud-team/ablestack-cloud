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

export default {
  name: 'automation',
  title: 'label.automation.service',
  icon: 'cloud-server-outlined',
  children: [
    {
      name: 'automationtemplate',
      title: 'title.automation.controller',
      icon: 'block-outlined',
      docHelp: '',
      permission: ['listAutomationControllerVersion'],
      columns: ['name', 'state', 'version', 'zonename', 'controlleruploadtype'],
      details: ['name', 'description', 'version', 'controlleruploadtype', 'dctemplate', 'workstemplate', 'created'],
      tabs: [{
        component: shallowRef(defineAsyncComponent(() => import('@/views/automation/AutomationTab.vue')))
      }],
      actions: [
        {
          api: 'createDesktopCluster',
          icon: 'plus-outlined',
          label: 'label.desktop.cluster.deploy',
          docHelp: '',
          listView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/automation/DeployCluster.vue')))
        },
        {
          api: 'startDesktopCluster',
          icon: 'caret-right-outlined',
          label: 'label.desktop.cluster.start',
          message: 'message.desktop.cluster.start',
          docHelp: '',
          dataView: true,
          show: (record) => { return ['Stopped'].includes(record.state) },
          groupAction: true,
          popup: true,
          groupMap: (selection) => { return selection.map(x => { return { id: x } }) }
        },
        {
          api: 'stopDesktopCluster',
          icon: 'poweroff-outlined',
          label: 'label.desktop.cluster.stop',
          message: 'message.desktop.cluster.stop',
          docHelp: '',
          dataView: true,
          show: (record) => { return !['Stopped', 'Destroyed', 'Destroying'].includes(record.state) },
          groupAction: true,
          popup: true,
          groupMap: (selection) => { return selection.map(x => { return { id: x } }) }
        },
        {
          api: 'deleteDesktopCluster',
          icon: 'delete-outlined',
          label: 'label.desktop.cluster.delete',
          message: 'message.desktop.cluster.delete',
          dataView: true,
          docHelp: '',
          show: (record) => { return !['Destroyed', 'Destroying'].includes(record.state) },
          groupAction: true,
          popup: true,
          groupMap: (selection) => { return selection.map(x => { return { id: x } }) }
        }
      ]
    }
  ]
}
