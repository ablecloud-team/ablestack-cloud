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

import { createApp } from 'vue'
import { createStore } from 'vuex'
import { createRouter, createMemoryHistory } from 'vue-router'
import Directives from '@/utils/directives'
import RenderIcon from '@/utils/renderIcon'
import korean from '../../../../ui/public/locales/ko_KR.json'
import Antd from 'ant-design-vue'
import * as AntIcons from '@ant-design/icons-vue'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import 'ant-design-vue/dist/antd.css'
import '@/style/vars.less'
import '@/style/index.less'
import '@/style/components/view/DetailTab.scss'
import App from './App.vue'
import './theme-guidance.css'
const store = createStore({ state: { app: { device: window.innerWidth < 766 ? 'mobile' : window.innerWidth < 1280 ? 'tablet' : 'desktop' } }, getters: {
  userInfo: () => ({id:'demo-user',roletype:'Admin'}), project: () => ({}), defaultListViewPageSize: () => 20,
  apis: () => ({listAnnotations:{},addAnnotation:{},removeAnnotation:{},updateAnnotationVisibility:{},listEvents:{},startKubernetesCluster:{},stopKubernetesCluster:{},scaleKubernetesCluster:{},upgradeKubernetesCluster:{},updateKubernetesClusterAffinityGroups:{},addNodesToKubernetesCluster:{},deleteKubernetesCluster:{},addVirtualMachinesToKubernetesCluster:{},updateKubernetesSupportedVersion:{},deleteKubernetesSupportedVersion:{}})
} })
const router = createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',name:'kubernetes',component:{render:()=>null},meta:{resourceType:'KubernetesCluster',name:'kubernetes'}}]})
router.push('/kubernetes/demo-cluster')
const fixtureStorage = new Map([['LOCALE','ko_KR']])
window.addEventListener('resize', () => { store.state.app.device = window.innerWidth < 766 ? 'mobile' : window.innerWidth < 1280 ? 'tablet' : 'desktop' })
const dark = new URLSearchParams(window.location.search).get('theme') === 'dark'
document.documentElement.classList.toggle('dark-mode', dark)
document.body.classList.toggle('dark-mode', dark)
if (new URLSearchParams(window.location.search).get('capture') === '1') document.documentElement.setAttribute('data-mock-capture', 'true')
const app = createApp(App)
app.use(store).use(Antd).use(router).use(Directives)
app.provide('parentFetchData',()=>Promise.resolve())
const icons = AntIcons
Object.entries(icons).forEach(([name, component]) => app.component(name, component))
app.component('FontAwesomeIcon',FontAwesomeIcon)
// QuickView is inactive; menus reuse the actual common icon renderer.
app.component('QuickView',{render:()=>null})
app.component('RenderIcon',RenderIcon)
app.config.globalProperties.$t = key => korean[key] || key
app.config.globalProperties.$localStorage = {get:key=>fixtureStorage.get(key),set:(key,value)=>fixtureStorage.set(key,value)}
app.config.globalProperties.$config = {theme:{'@primary-color':'#1890ff','@disabled-color':'#999'}}
app.config.globalProperties.$showIcon = () => false
app.config.globalProperties.$toLocaleDate = value => value ? new Date(value).toLocaleString('ko-KR') : ''
app.config.globalProperties.$notifyError = error => console.error(error)
app.config.globalProperties.$i18n = {locale:'ko_KR'}
router.isReady().then(()=>app.mount('#app'))
