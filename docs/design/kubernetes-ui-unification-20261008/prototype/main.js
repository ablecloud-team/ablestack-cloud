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
import Antd from 'ant-design-vue'
import { HomeOutlined, CloudOutlined, CloudServerOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, FilterOutlined, SettingOutlined, QuestionCircleOutlined, DownOutlined, MenuFoldOutlined, TranslationOutlined, BellOutlined, DeleteOutlined, CopyOutlined, DownloadOutlined, GlobalOutlined, CheckCircleOutlined, HddOutlined } from '@ant-design/icons-vue'
import 'ant-design-vue/dist/antd.css'
import '@/style/vars.less'
import '@/style/index.less'
import '@/style/components/view/DetailTab.scss'
import App from './App.vue'
const store = createStore({ state: { app: { device: window.innerWidth < 766 ? 'mobile' : window.innerWidth < 1280 ? 'tablet' : 'desktop' } } })
window.addEventListener('resize', () => { store.state.app.device = window.innerWidth < 766 ? 'mobile' : window.innerWidth < 1280 ? 'tablet' : 'desktop' })
const dark = new URLSearchParams(window.location.search).get('theme') === 'dark'
document.documentElement.classList.toggle('dark-mode', dark)
document.body.classList.toggle('dark-mode', dark)
if (new URLSearchParams(window.location.search).get('capture') === '1') document.documentElement.setAttribute('data-mock-capture', 'true')
const app = createApp(App)
app.use(store).use(Antd)
const icons = { HomeOutlined, CloudOutlined, CloudServerOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, FilterOutlined, SettingOutlined, QuestionCircleOutlined, DownOutlined, MenuFoldOutlined, TranslationOutlined, BellOutlined, DeleteOutlined, CopyOutlined, DownloadOutlined, GlobalOutlined, CheckCircleOutlined, HddOutlined }
Object.entries(icons).forEach(([name, component]) => app.component(name, component))
app.config.globalProperties.$t = key => ({ 'label.close': '닫기' }[key] || key)
app.mount('#app')
