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
import { createApp, h } from 'vue'
import { createStore } from 'vuex'
import Antd from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import 'ant-design-vue/dist/antd.css'
import './mold-theme.less'
import '@/style/theme/tokens.less'
import '@/style/theme/base.less'
import '@/style/theme/components.less'
import '@/style/theme/feedback.less'
import '@/style/theme/scrollbar.less'
import App from './App.vue'
const currentDevice = () => innerWidth < 766 ? 'mobile' : innerWidth < 1280 ? 'tablet' : 'desktop'
const store = createStore({ state: { app: { device: currentDevice() } }, mutations: { device (state, value) { state.app.device = value } } })
window.addEventListener('resize', () => store.commit('device', currentDevice()))
const app = createApp(App)
app.use(Antd).use(store)
app.component('RenderIcon', { props: ['icon'], render () { return this.icon === 'reload-outlined' ? h(ReloadOutlined) : null } })
app.config.globalProperties.$t = key => ({ 'state.ready': '사용 가능', 'state.running': '실행 중', 'state.stopped': '정지됨', 'state.error': '오류', 'label.refresh': '업데이트' }[key] || key)
app.mount('#app')
