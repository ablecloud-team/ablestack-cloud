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
import { secondaryStorageSearchFields } from '@/utils/secondaryStorageSearch'
import { selectorApi } from '@/utils/secondaryStorageSelectors'

export default {
  name: 'imagestore',
  title: 'label.secondary.storage',
  icon: 'picture-outlined',
  docHelp: 'adminguide/storage.html#secondary-storage',
  permission: ['listImageStores'],
  searchFilters: secondaryStorageSearchFields,
  columns: () => {
    var fields = ['name', 'url', 'disksizetotal', 'disksizeused', 'protocol', 'providername', 'scope', 'zonename']
    if (store.getters.apis.listImageStores.params.filter(x => x.name === 'readonly').length > 0) {
      fields.push({
        field: 'readonly',
        customTitle: 'access'
      })
    }
    return fields
  },
  details: () => {
    var fields = ['name', 'id', 'url', 'disksizetotal', 'disksizeused', 'protocol', 'providername', 'scope', 'zonename']
    if (store.getters.apis.listImageStores.params.filter(x => x.name === 'readonly').length > 0) {
      fields.push('readonly')
    }
    return fields
  },
  resourceType: 'SecondaryStorage',
  related: [{
    name: 'template',
    title: 'label.templates',
    param: 'imagestoreid'
  },
  {
    name: 'iso',
    title: 'label.isos',
    param: 'imagestoreid'
  },
  {
    name: 'snapshot',
    title: 'label.snapshots',
    param: 'imagestoreid'
  }],
  tabs: [{
    name: 'details',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
  }, {
    name: 'settings',
    component: shallowRef(defineAsyncComponent(() => import('@/views/setting/ScopedSettingsTab.vue')))
  }, {
    name: 'browser',
    resourceType: 'ImageStore',
    show: () => 'listImageStoreObjects' in store.getters.apis,
    component: shallowRef(defineAsyncComponent(() => import('@/views/infra/StorageBrowser.vue')))
  }, {
    name: 'events',
    resourceType: 'ImageStore',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
    show: () => { return 'listEvents' in store.getters.apis }
  }, {
    name: 'comments',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/AnnotationsTab.vue')))
  }],
  actions: [
    {
      api: 'addImageStore',
      icon: 'plus-outlined',
      docHelp: 'installguide/configuration.html#add-secondary-storage',
      label: 'label.add.secondary.storage',
      listView: true,
      popup: true,
      standardDialogSize: 'md',
      standardDialogInitialFocus: '#secondary-storage-name',
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/AddSecondaryStorage.vue')))
    },
    {
      api: 'listSecondaryStagingStores',
      icon: 'database-outlined',
      label: 'label.secondary.staging.stores',
      listView: true,
      popup: true,
      standardDialogSize: 'md',
      standardDialogInitialFocus: '#secondary-staging-refresh',
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/SecondaryStagingStores.vue')))
    },
    {
      api: selectorApi.list,
      icon: 'aim-outlined',
      label: 'label.secondary.storage.selector',
      listView: true
    },
    {
      api: 'migrateSecondaryStorageData',
      icon: 'drag-outlined',
      label: 'label.migrate.data.from.image.store',
      listView: true,
      popup: true,
      standardDialogSize: 'md',
      standardDialogInitialFocus: '#secondary-migration-source',
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/MigrateData.vue')))
    },
    {
      api: 'updateImageStore',
      icon: 'edit-outlined',
      label: 'label.edit',
      dataView: true,
      popup: true,
      standardDialogSize: 'md',
      standardDialogInitialFocus: '#secondary-storage-edit-name',
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/UpdateSecondaryStorage.vue')))
    },
    {
      api: 'deleteImageStore',
      icon: 'delete-outlined',
      label: 'label.action.delete.secondary.storage',
      message: 'message.action.delete.secondary.storage',
      danger: true,
      dataView: true,
      displayName: (record) => { return record.name || record.displayName || record.id }
    }
  ]
}
