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

<template>
  <div class='user-layout'>
    <div class="user-layout-container">
      <div class="user-layout-header">
        <img
          v-if="$config.banner"
          :style="{
            width: $config.theme['@banner-width'],
            height: $config.theme['@banner-height']
          }"
          :src="$config.banner"
          class="user-layout-logo"
          alt="logo">
      </div>
      <a-form
        id="formLogin"
        class="user-layout-login"
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        v-ctrl-enter="handleSubmit"
      >
        <a-tabs
          class="tab-center"
          size="large"
          :tabBarStyle="{ textAlign: 'center', borderBottom: 'unset' }"
          :animated="false"
        >
          <a-tab-pane>
            <template #tab>
              <span>
                <safety-outlined />
                {{ $t('label.action.change.password') }}
              </span>
            </template>
            <a-form-item ref="password" name="password">
              <a-input-password
                size="large"
                type="password"
                autocomplete="false"
                :placeholder="$t('label.password')"
                v-model:value="form.password"
              >
                <template #prefix>
                  <lock-outlined />
                </template>
              </a-input-password>
            </a-form-item>
            <a-form-item ref="confirmpassword" name="confirmpassword">
              <a-input-password
                size="large"
                type="password"
                autocomplete="false"
                :placeholder="$t('label.confirmpassword.description')"
                v-model:value="form.confirmpassword"
              >
                <template #prefix>
                  <lock-outlined />
                </template>
              </a-input-password>
            </a-form-item>
          </a-tab-pane>
        </a-tabs>
        <a-form-item>
            <a-button
              :loading="loading"
              size="large"
              ref="submit"
              type="primary"
              :disabled="buttonstate"
              class="login-button"
              @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { ref, reactive, toRaw } from 'vue'
import Cookies from 'js-cookie'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'FirstLogin',
  components: {
    TooltipLabel
  },
  data () {
    return {
      buttonstate: false,
      firstLoginresponse: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateUser')
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        password: [{ required: true, message: this.$t('message.error.new.password') }],
        confirmpassword: [
          { required: true, message: this.$t('message.error.confirm.password') },
          { validator: this.validateTwoPassword }
        ]
      })
      getAPI('listUsers', { username: Cookies.get('username'), listall: true }).then(response => {
        const result = response.listusersresponse.user[0]
        this.$store.commit('SET_INFO', result)
      }).catch(error => {
        console.log(error)
      })
    },
    async validateTwoPassword (rule, value) {
      if (!value || value.length === 0) {
        return Promise.resolve()
      } else if (rule.field === 'confirmpassword') {
        const form = this.form
        const messageConfirm = this.$t('message.validate.equalto')
        const passwordVal = form.password
        if (passwordVal && passwordVal !== value) {
          return Promise.reject(messageConfirm)
        } else {
          return Promise.resolve()
        }
      } else {
        return Promise.resolve()
      }
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.buttonstate) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        if (values.password !== null && values.confirmpassword !== null) {
          this.buttonstate = true
        }
        const params = {
          id: this.$store.getters.userInfo.id,
          password: values.password
        }
        postAPI('updateUser', params).then(json => {
          this.firstLoginresponse = true
          if (this.firstLoginresponse) {
            this.$notification.destroy()
            this.$store.commit('SET_COUNT_NOTIFY', 0)
            this.$store.commit('SET_LOGIN_FLAG', true)
            this.$store.commit('SET_FIRST_LOGIN', false)
            Cookies.set('firstlogin', 'false')
            this.$message.success({
              content: `${this.$t('message.success.change.password')}`
            })
            this.$router.push({ path: '/dashboard' }).catch(() => {})
            this.$emit('refresh-data')
          }
        }).catch(error => {
          this.buttonstate = false
          this.$store.dispatch('Logout').then(() => {
            this.$router.replace({ path: '/user/login' })
          })
          this.$notifyError(error)
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    }
  }
}

</script>
<style lang="less" scoped>
.user-layout {
  height: 100%;

  &-container {
    padding: 3rem 0;
    width: 100%;

    @media (min-height:600px) {
      padding: 0;
      position: relative;
      top: 50%;
      transform: translateY(-50%);
      margin-top: -50px;
    }
  }

  &-logo {
    border-style: none;
    margin: 0 auto 2rem;
    display: block;

    .mobile & {
      max-width: 300px;
      margin-bottom: 1rem;
    }
  }

  &-footer {
    display: flex;
    flex-direction: column;
    position: absolute;
    bottom: 20px;
    text-align: center;
    width: 100%;

    @media (max-height: 600px) {
      position: relative;
      margin-top: 50px;
    }

    label {
      width: 368px;
      font-weight: 500;
      margin: 0 auto;
    }
  }

  .user-layout-login {
    min-width: 260px;
    width: 368px;
    margin: 0 auto;

  .mobile & {
    max-width: 368px;
    width: 98%;
  }

  label {
    font-size: 14px;
  }

  button.login-button {
    margin-top: 8px;
    padding: 0 15px;
    font-size: 16px;
    height: 40px;
    width: 100%;
  }
}
}
</style>
