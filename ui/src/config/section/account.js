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
import { accountTransitionAllowed } from '@/utils/accountState'

export default {
  name: 'account',
  title: 'label.accounts',
  icon: 'team-outlined',
  docHelp: 'adminguide/accounts.html',
  permission: ['listAccounts'],
  searchFilters: () => {
    const filters = ['id', 'name', 'accounttype', 'domainid', 'isrecursive', 'iscleanuprequired']
    if (store.getters.userInfo.roletype === 'Admin') {
      filters.push('apikeyaccess')
    }
    return filters
  },
  columns: ['name', 'state', 'rolename', 'roletype', 'domainpath'],
  details: ['name', 'id', 'rolename', 'roletype', 'domainpath', 'networkdomain', 'apikeyaccess', 'iptotal', 'vmtotal', 'volumetotal', 'receivedbytes', 'sentbytes', 'created'],
  related: [{
    name: 'accountuser',
    title: 'label.users',
    param: 'account'
  }, {
    name: 'vm',
    title: 'label.vms',
    param: 'account'
  }, {
    name: 'volume',
    title: 'label.volumes',
    param: 'account'
  }, {
    name: 'guestnetwork',
    title: 'label.networks',
    param: 'account'
  }, {
    name: 'ssh',
    title: 'label.sshkeypairs',
    param: 'account'
  }, {
    name: 'userdata',
    title: 'label.user.data',
    param: 'account'
  }, {
    name: 'template',
    title: 'label.templates',
    param: 'account'
  }, {
    name: 'iso',
    title: 'label.isos',
    param: 'account'
  }],
  filters: () => {
    const filters = ['enabled', 'disabled', 'locked']
    return filters
  },
  tabs: [
    {
      name: 'details',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
    },
    {
      name: 'limits.configure',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/ResourceLimitTab.vue')))
    },
    {
      name: 'certificates',
      component: shallowRef(defineAsyncComponent(() => import('@/views/iam/SSLCertificateTab.vue')))
    },
    {
      name: 'settings',
      component: shallowRef(defineAsyncComponent(() => import('@/views/setting/ScopedSettingsTab.vue'))),
      show: () => { return 'listConfigurations' in store.getters.apis }
    },
    {
      name: 'events',
      resourceType: 'Account',
      component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
      show: () => { return 'listEvents' in store.getters.apis }
    }
  ],
  actions: [
    {
      api: 'createAccount',
      icon: 'plus-outlined',
      label: 'label.add.account',
      listView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/iam/AddAccount.vue')))
    },
    {
      api: 'ldapCreateAccount',
      icon: 'user-add-outlined',
      label: 'label.add.ldap.account',
      docHelp: 'adminguide/accounts.html#using-an-ldap-server-for-user-authentication',
      listView: true,
      popup: true,
      show: (record, store) => {
        return store.isLdapEnabled
      },
      component: shallowRef(defineAsyncComponent(() => import('@/views/iam/AddLdapAccount.vue')))
    },
    {
      api: 'updateAccount',
      icon: 'edit-outlined',
      label: 'label.action.edit.account',
      dataView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/iam/EditAccount.vue')))
    },
    {
      api: 'updateResourceCount',
      icon: 'sync-outlined',
      label: 'label.action.update.resource.count',
      dataView: true,
      popup: true,
      show: (record, store) => record.state === 'enabled' && ['Admin', 'DomainAdmin'].includes(store.userInfo.roletype),
      component: shallowRef(defineAsyncComponent(() => import('@/views/iam/AccountResourceCount.vue')))
    },
    ...[
      { api: 'enableAccount', icon: 'play-circle-outlined', accountState: 'enabled', verb: 'enable' },
      { api: 'disableAccount', icon: 'pause-circle-outlined', accountState: 'disabled', verb: 'disable' },
      { api: 'disableAccount', icon: 'LockOutlined', accountState: 'locked', verb: 'lock' }
    ].map(action => ({
      ...action,
      label: 'label.action.' + action.verb + '.account',
      toolbarLabel: 'label.action.' + action.verb + '.account',
      message: 'message.' + action.verb + '.account',
      dataView: true,
      groupAction: true,
      popup: true,
      show: (record, store) => accountTransitionAllowed(action, record, store),
      groupShow: (records, store) => records.some(record => accountTransitionAllowed(action, record, store))
    })),
    {
      api: 'uploadSslCert',
      icon: 'SafetyCertificateOutlined',
      label: 'label.add.certificate',
      dataView: true,
      args: ['name', 'certificate', 'privatekey', 'certchain', 'password', 'account', 'domainid'],
      post: true,
      show: (record) => { return record.state === 'enabled' },
      mapping: {
        account: {
          value: (record) => { return record.name }
        },
        domainid: {
          value: (record) => { return record.domainid }
        }
      }
    },
    {
      api: 'deleteAccount',
      icon: 'delete-outlined',
      label: 'label.action.delete.account',
      message: 'message.delete.account',
      dataView: true,
      disabled: (record, store) => {
        return store.userInfo.accountid === record?.id
      },
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/iam/DeleteAccountWrapper.vue')))
    }
  ]
}
