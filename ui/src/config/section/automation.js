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
    }
  ]
}
