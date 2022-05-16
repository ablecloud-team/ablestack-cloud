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
      title: 'title.automation.template',
      icon: 'block-outlined',
      docHelp: '',
      permission: ['listAutomationControllerVersion'],
      columns: ['name', 'state', 'version', 'zonename', 'controllerversionname'],
      details: ['name', 'description', 'version', 'controlleruploadtype', 'created'],
      actions: [
        {
          api: 'addDesktopControllerVersion',
          icon: 'plus-outlined',
          label: 'label.automation.controller.template.version.create',
          docHelp: '',
          listView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/desktop/AddDesktopControllerVersion.vue')))
        },
        {
          api: 'updateDesktopControllerVersion',
          icon: 'edit-outlined',
          label: 'label.desktop.controller.version.manage',
          dataView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/desktop/UpdateDesktopControllerVersion.vue')))
        },
        {
          api: 'deleteDesktopControllerVersion',
          icon: 'delete-outlined',
          label: 'label.desktop.controller.version.delete',
          message: 'message.desktop.controller.version.delete',
          dataView: true
        }
      ]
    },
    {
      name: 'automationcontroller',
      title: 'title.automation.controller',
      icon: 'block-outlined',
      docHelp: '',
      permission: ['listAutomationController'],
      columns: ['name', 'state', 'account', 'zonename'],
      details: ['description', 'name', 'serviceip', 'state', 'ipaddress', 'automationtemplatename', 'ostypename', 'serviceofferingname', 'isdynamicallyscalable', 'userurl'],
      tabs: [{
        component: shallowRef(defineAsyncComponent(() => import('@/views/automation/AutomationControllerTab.vue')))
      }],
      actions: [
        {
          api: 'addAutomationController',
          icon: 'plus-outlined',
          label: 'label.automation.controller.add',
          docHelp: '',
          listView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/automation/AddAutomationController.vue')))
        },
        {
          api: 'startAutomationController',
          icon: 'caret-right-outlined',
          label: 'label.automation.controller.start',
          message: 'message.automation.controller.start',
          docHelp: '',
          dataView: true,
          show: (record) => { return ['Stopped'].includes(record.state) },
          groupAction: true,
          popup: true,
          groupMap: (selection) => { return selection.map(x => { return { id: x } }) }
        },
        {
          api: 'stopAutomationController',
          icon: 'poweroff-outlined',
          label: 'label.automation.controller.stop',
          message: 'message.automation.controller.stop',
          docHelp: '',
          dataView: true,
          show: (record) => { return !['Stopped', 'Destroyed', 'Destroying'].includes(record.state) },
          groupAction: true,
          popup: true,
          groupMap: (selection) => { return selection.map(x => { return { id: x } }) }
        },
        {
          api: 'deleteAutomationController',
          icon: 'delete-outlined',
          label: 'label.automation.controller.delete',
          message: 'message.automation.controller.delete',
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
