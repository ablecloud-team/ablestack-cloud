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

import { vueProps } from '@/vue-app'
import Cookies from 'js-cookie'
import { i18n } from './locales'
import router from './router'
import store from './store'

import NProgress from 'nprogress' // progress bar
import 'nprogress/nprogress.css' // progress bar style
import message from 'ant-design-vue/es/message'
import notification from 'ant-design-vue/es/notification'
import { setDocumentTitle } from '@/utils/domUtil'
import { ACCESS_TOKEN, APIS, SERVER_MANAGER, CURRENT_PROJECT } from '@/store/mutation-types'

NProgress.configure({ showSpinner: false }) // NProgress Configuration

const allowList = ['login', 'VerifyOauth', 'forgotPassword', 'resetPassword'] // no redirect allowlist

router.beforeEach((to, from, next) => {
  // start progress bar
  NProgress.start()

  if (to.meta && typeof to.meta.title !== 'undefined') {
    const title = i18n.global.t(to.meta.title) + ' - ' + vueProps.$config.appTitle
    setDocumentTitle(title)
  }

  if (vueProps.$config.multipleServer) {
    const servers = vueProps.$config.servers
    const serverStorage = vueProps.$localStorage.get(SERVER_MANAGER)
    let apiFullPath = ''
    if (serverStorage) {
      apiFullPath = (serverStorage.apiHost || '') + serverStorage.apiBase
    }
    const serverFilter = servers.filter(ser => (ser.apiHost || '') + ser.apiBase === apiFullPath)
    const server = serverFilter[0] || servers[0]
    vueProps.axios.defaults.baseURL = (server.apiHost || '') + server.apiBase
    store.dispatch('SetServer', server)
  }

  const validLogin = vueProps.$localStorage.get(ACCESS_TOKEN) || Cookies.get('userid') || Cookies.get('userid', { path: '/client' })
  if (validLogin) {
    var currentURL = new URL(window.location.href)
    var urlParams = new URLSearchParams(currentURL.search)
    var code = urlParams.get('code')
    if (code != null) {
      urlParams.delete('code')
    }
    currentURL.search = ''
    window.history.replaceState(null, null, currentURL.toString())
    if (to.path === '/user/login') {
      next({ path: '/dashboard' })
      NProgress.done()
    } else if (to.path === '/firstLogin') {
      const firstLogin = JSON.parse(Cookies.get('firstlogin') || Cookies.get('firstlogin', { path: '/client' }) || false)
      if (store.getters.firstLogin === true || firstLogin === true) {
        next()
      } else {
        next({ path: '/dashboard' })
        NProgress.done()
      }
    } else if (to.path === '/verify2FA' || to.path === '/setup2FA') {
      const isSAML = JSON.parse(Cookies.get('isSAML') || Cookies.get('isSAML', { path: '/client' }) || false)
      const twoFaEnabled = JSON.parse(Cookies.get('twoFaEnabled') || Cookies.get('twoFaEnabled', { path: '/client' }) || false)
      const twoFaProvider = Cookies.get('twoFaProvider') || Cookies.get('twoFaProvider', { path: '/client' }) || store.getters.twoFaProvider
      if ((store.getters.twoFaEnabled && !store.getters.loginFlag) || (isSAML === true && twoFaEnabled === true)) {
        console.log('Do Two-factor authentication')
        store.commit('SET_2FA_PROVIDER', twoFaProvider)
        next()
      } else {
        next({ path: '/dashboard' })
        NProgress.done()
      }
    } else {
      const isSAML = JSON.parse(Cookies.get('isSAML') || Cookies.get('isSAML', { path: '/client' }) || false)
      const twoFaEnabled = JSON.parse(Cookies.get('twoFaEnabled') || Cookies.get('twoFaEnabled', { path: '/client' }) || false)
      const twoFaProvider = Cookies.get('twoFaProvider') || Cookies.get('twoFaProvider', { path: '/client' })
      if (isSAML === true && !store.getters.loginFlag && to.path !== '/dashboard') {
        if (twoFaEnabled === true && twoFaProvider !== '' && twoFaProvider !== undefined) {
          next({ path: '/verify2FA' })
          return
        }
        if (twoFaEnabled === true && (twoFaProvider === '' || twoFaProvider === undefined)) {
          next({ path: '/setup2FA' })
          return
        }
        store.commit('SET_LOGIN_FLAG', true)
      }
      if (Cookies.get('firstlogin') === 'true' && to.path !== '/firstlogin') {
        store.dispatch('Logout').then(() => {
          next({ path: '/user/login', query: { redirect: to.fullPath } })
        })
      }
      if (Object.keys(store.getters.apis).length === 0) {
        const cachedApis = vueProps.$localStorage.get(APIS, {})
        if (Object.keys(cachedApis).length > 0) {
          message.loading(`${i18n.global.t('label.loading')}...`, 1.5)
        }
        store
          .dispatch('GetInfo')
          .then(apis => {
            store.dispatch('GenerateRoutes', { apis }).then(() => {
              store.getters.addRouters.map(route => {
                router.addRoute(route)
              })
              const redirect = decodeURIComponent(from.query.redirect || to.path)
              if (to.path === redirect) {
                next({ ...to, replace: true })
              } else {
                next({ path: redirect })
              }
              var project = vueProps.$localStorage.get(CURRENT_PROJECT)
              if (project == null) {
                project = {}
                store.commit('SET_PROJECT', project)
              }
              store.dispatch('ToggleTheme', project.id === undefined ? 'light' : 'dark')
            })
          })
          .catch(() => {
            let countNotify = store.getters.countNotify
            countNotify++
            store.commit('SET_COUNT_NOTIFY', countNotify)
            if (to.path === '/user/login') {
              notification.error({
                top: '65px',
                message: 'Error',
                description: i18n.global.t('message.error.discovering.feature'),
                duration: 0,
                onClose: () => {
                  let countNotify = store.getters.countNotify
                  countNotify > 0 ? countNotify-- : countNotify = 0
                  store.commit('SET_COUNT_NOTIFY', countNotify)
                }
              })
            }
            store.dispatch('Logout').then(() => {
              next({ path: '/user/login', query: { redirect: to.fullPath } })
            })
          })
      } else {
        next()
      }
    }
  } else {
    if (window.location.href.includes('verifyOauth') && to.name === undefined) {
      currentURL = new URL(window.location.href)
      urlParams = new URLSearchParams(currentURL.search)
      code = urlParams.get('code')
      urlParams.delete('verifyOauth')
      urlParams.delete('state')
      currentURL.search = '?code=' + code
      window.history.replaceState(null, null, currentURL.toString())
      next({ path: '/verifyOauth', query: { redirect: to.fullPath } })
    } else if (allowList.includes(to.name)) {
      next()
      // Failed samlSso login
      const hash = window.location.hash
      const queryString = hash.split('?')[1]
      const params = new URLSearchParams(queryString)
      const hasSsoLogin = params.has('ssoLogin')
      const ssoLogin = params.get('ssoLogin')
      if (hasSsoLogin && ssoLogin === 'false') {
        notification.error({
          top: '65px',
          message: 'Error',
          description: i18n.global.t('message.ssologin.failed.security'),
          duration: 3
        })
      }
    } else {
      next({ path: '/user/login', query: { redirect: to.fullPath } })
      NProgress.done()
    }
  }
})

router.afterEach(() => {
  NProgress.done() // finish progress bar
})
