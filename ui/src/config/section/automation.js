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
  icon: 'AppstoreAddOutlined',
  children: [
    {
      name: 'automationcontroller',
      title: 'title.automation.controller',
      icon: 'RocketOutlined',
      docHelp: '',
      permission: ['listAutomationController'],
      columns: ['name', 'state', 'networkname', 'account', 'hostname', 'zonename'],
      details: ['description', 'name', 'hostname', 'automationcontrollerip', 'automationcontrollerpublicip', 'automationtemplatename', 'automationcontrollerversion', 'osdisplayname', 'isdynamicallyscalable'],
      tabs: [{
        component: shallowRef(defineAsyncComponent(() => import('@/views/automation/AutomationControllerTab.vue')))
      }],
      actions: [
        {
          api: 'addAutomationController',
          icon: 'plus-outlined',
          label: 'label.automation.controller.deploy',
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
    },
    {
      name: 'automationtemplate',
      title: 'title.automation.controller.template',
      icon: 'shop-outlined',
      docHelp: '',
      permission: ['listAutomationControllerVersion'],
      columns: ['name', 'state', 'templatestate', 'version', 'zonename'],
      details: ['name', 'description', 'version', 'templatestate', 'templatename', 'created'],
      actions: [
        {
          api: 'addAutomationControllerVersion',
          icon: 'plus-outlined',
          label: 'label.automation.controller.template.version.create',
          docHelp: '',
          listView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/automation/AddAutomationControllerVersion.vue')))
        },
        {
          api: 'updateAutomationControllerVersion',
          icon: 'edit-outlined',
          label: 'label.automation.controller.version.manage',
          dataView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/automation/UpdateAutomationControllerVersion.vue')))
        },
        {
          api: 'deleteAutomationControllerVersion',
          icon: 'delete-outlined',
          label: 'label.automation.controller.version.delete',
          message: 'message.automation.controller.version.delete',
          dataView: true
        }
      ]
    },
    {
      name: 'deployedresource',
      title: 'title.automation.deployed.resource',
      icon: 'GroupOutlined',
      docHelp: '',
      permission: ['listAutomationDeployedResource'],
      columns: ['name', 'description', 'state', 'account'],
      details: ['name', 'description', 'accessinfo', 'state', 'created'],
      tabs: [{
        component: shallowRef(defineAsyncComponent(() => import('@/views/automation/DeployedResourceTab.vue')))
      }],
      actions: [
      ]
    }
  ]
}
