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
  name: 'domain',
  title: 'label.domains',
  icon: 'BlockOutlined',
  docHelp: 'adminguide/accounts.html#domains',
  permission: ['listDomains', 'listDomainChildren'],
  resourceType: 'Domain',
  columns: ['name', 'state', 'path', 'parentdomainname', 'level'],
  details: ['name', 'id', 'path', 'parentdomainname', 'level', 'networkdomain', 'created'],
  component: shallowRef(() => import('@/views/iam/DomainView.vue')),
  related: [{
    name: 'account',
    title: 'label.accounts',
    param: 'domainid'
  }, {
    name: 'vm',
    title: 'label.vms',
    param: 'domainid'
  }, {
    name: 'volume',
    title: 'label.volumes',
    param: 'domainid'
  }, {
    name: 'guestnetwork',
    title: 'label.networks',
    param: 'domainid'
  }, {
    name: 'template',
    title: 'label.templates',
    param: 'domainid'
  },
  {
    name: 'iso',
    title: 'label.isos',
    param: 'domainid'
  }],
  tabs: [
    {
      name: 'domain',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/InfoCard.vue'))),
      show: (record, route) => { return route.path === '/domain' }
    },
    {
      name: 'details',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
    },
    {
      name: 'limits.configure',
      show: (record, route, user) => ['Admin', 'DomainAdmin'].includes(user.roletype),
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/ResourceLimitTab.vue')))
    },
    {
      name: 'settings',
      component: shallowRef(defineAsyncComponent(() => import('@/views/setting/ScopedSettingsTab.vue'))),
      show: () => { return 'listConfigurations' in store.getters.apis }
    },
    {
      name: 'events',
      resourceType: 'Domain',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
      show: () => { return 'listEvents' in store.getters.apis }
    },
    {
      name: 'comments',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/AnnotationsTab.vue')))
    }
  ],
  treeView: true,
  actions: [
    {
      api: 'createDomain',
      icon: 'plus-outlined',
      label: 'label.add.domain',
      listView: true,
      dataView: false,
      args: ['parentdomainid', 'name', 'networkdomain', 'domainid'],
      mapping: {
        parentdomainid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'updateDomain',
      icon: 'edit-outlined',
      label: 'label.action.edit.domain',
      listView: true,
      dataView: true,
      args: (record) => {
        var fields = ['networkdomain']
        if (record.level > 0) {
          fields.unshift('name')
        }
        return fields
      },
      show: (record, store) => {
        return ['Admin'].includes(store.userInfo.roletype) ||
          ['DomainAdmin'].includes(store.userInfo.roletype) && record.id !== store.userInfo.domainid
      }
    },
    {
      api: 'moveDomain',
      icon: 'BranchesOutlined',
      label: 'label.domain.move',
      listView: true,
      dataView: true,
      show: (record, store) => record.level > 0 && store.userInfo.roletype === 'Admin',
      args: ['domainid', 'parentdomainid'],
      mapping: {
        domainid: { value: record => record.id }
      }
    },
    {
      api: 'updateResourceCount',
      icon: 'sync-outlined',
      label: 'label.action.update.resource.count',
      message: 'message.update.resource.count.domain',
      listView: true,
      dataView: true,
      args: ['domainid'],
      mapping: {
        domainid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'linkDomainToLdap',
      icon: 'LinkOutlined',
      label: 'label.link.domain.to.ldap',
      docHelp: 'adminguide/accounts.html#using-an-ldap-server-for-user-authentication',
      listView: true,
      dataView: true,
      show: record => record.domainLdap?.configured === true && record.domainLdap.linked === false,
      args: ['type', 'domainid', 'ldapdomain', 'accounttype', 'admin'],
      mapping: {
        type: {
          options: ['GROUP', 'OU'],
          labels: ['label.domain.ldap.group', 'label.domain.ldap.ou']
        },
        accounttype: {
          options: ['0', '2'],
          labels: ['label.user', 'label.domain.ldap.domainadmin']
        },
        domainid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'unlinkDomainFromLdap',
      icon: 'ArrowsAltOutlined',
      label: 'label.unlink.domain.from.ldap',
      show: record => record.domainLdap?.linked === true,
      docHelp: 'adminguide/accounts.html#using-an-ldap-server-for-user-authentication',
      listView: true,
      dataView: true,
      args: ['domainid'],
      mapping: {
        domainid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'deleteDomain',
      icon: 'delete-outlined',
      label: 'label.action.delete.domain',
      listView: true,
      dataView: true,
      show: (record, store) => {
        return record.level > 0 && (['Admin'].includes(store.userInfo.roletype) ||
          ['DomainAdmin'].includes(store.userInfo.roletype) && record.id !== store.userInfo.domainid)
      },
      args: ['cleanup']
    }
  ]
}
