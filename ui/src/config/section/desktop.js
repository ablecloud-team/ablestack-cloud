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
  name: 'desktop',
  title: 'label.desktop.service',
  icon: 'cloud-server-outlined',
  children: [
    {
      name: 'desktopcluster',
      title: 'title.desktop.cluster',
      icon: 'block-outlined',
      docHelp: '',
      permission: ['listDesktopClusters'],
      columns: ['name', 'state', 'addomainname', 'account', 'zonename'],
      details: ['name', 'id', 'description', 'controllerversion', 'account', 'addomainname', 'zonename', 'associatednetworkname', 'adminurl', 'userurl', 'created'],
      tabs: [{
        component: shallowRef(defineAsyncComponent(() => import('@/views/desktop/DesktopTab.vue')))
      }],
      actions: [
        {
          api: 'createDesktopCluster',
          icon: 'plus-outlined',
          label: 'label.desktop.cluster.deploy',
          docHelp: '',
          listView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/desktop/DeployCluster.vue')))
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
    },
    {
      name: 'controllertemplate',
      title: 'title.desktop.controller',
      icon: 'shop-outlined',
      docHelp: '',
      permission: ['listDesktopControllerVersions'],
      columns: ['name', 'state', 'version', 'zonename', 'controlleruploadtype'],
      details: ['name', 'description', 'version', 'controlleruploadtype', 'dctemplate', 'workstemplate', 'created'],
      actions: [
        {
          api: 'addDesktopControllerVersion',
          icon: 'plus-outlined',
          label: 'label.desktop.controller.template.version.create',
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
      name: 'mastertemplate',
      title: 'title.desktop.master',
      icon: 'hdd-outlined',
      docHelp: '',
      permission: ['listDesktopMasterVersions'],
      columns: ['name', 'state', 'version', 'zonename', 'masteruploadtype', 'mastertemplatetype'],
      details: ['name', 'version', 'description', 'templateostype', 'templatestate', 'masteruploadtype', 'templatename', 'mastertemplatetype', 'created'],
      actions: [
        {
          api: 'addDesktopMasterVersion',
          icon: 'plus-outlined',
          label: 'label.desktop.master.template.version.create',
          docHelp: '',
          listView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/desktop/AddDesktopMasterVersion.vue')))
        },
        {
          api: 'updateDesktopMasterVersion',
          icon: 'edit-outlined',
          label: 'label.desktop.master.version.manage',
          dataView: true,
          popup: true,
          component: shallowRef(defineAsyncComponent(() => import('@/views/desktop/UpdateDesktopMasterVersion.vue')))
        },
        {
          api: 'deleteDesktopMasterVersion',
          icon: 'delete-outlined',
          label: 'label.desktop.master.version.delete',
          message: 'message.desktop.master.veresion.delete',
          dataView: true
        }
      ]
    }
  ]
}
