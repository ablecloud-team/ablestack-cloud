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
  <a-spin :spinning="loading">
    <a-form
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      class="form"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit"
    >
      <a-alert style="margin-bottom: 5px" type="warning" show-icon>
        <template #message>
          <span v-html="$t('message.add.disaster.recovery.cluster')" />
        </template>
      </a-alert>
      <a-form-item name="name" ref="name" :label="$t('label.name')">
        <a-input
          :placeholder="apiParams.name.description"
          v-model:value="form.name"
        />
      </a-form-item>

      <a-form-item name="displaytext" ref="displaytext" :label="$t('label.displaytext')">
        <a-input
          v-model:value="form.displaytext"
          :placeholder="apiParams.description.description"
        />
      </a-form-item>

      <a-card size="small" :title="$t('label.add.disaster.recovery.cluster.info')" style="margin-top: 15px">
        <a-form-item name="url" ref="url" :label="'Mold URL'">
          <a-input
            :placeholder="apiParams.drclusterurl.description"
            v-model:value="form.url"
          />
        </a-form-item>
        <a-form-item name="apikey" ref="apikey" :label="$t('label.apikey')">
          <a-input
            :placeholder="apiParams.drclusterapikey.description"
            v-model:value="form.apikey"
          />
        </a-form-item>
        <a-form-item name="secretkey" ref="secretkey" :label="$t('label.secret.key')">
          <a-input
            :placeholder="apiParams.drclustersecretkey.description"
            v-model:value="form.secretkey"
          />
        </a-form-item>
        <a-form-item name="glueip" ref="glueip" :label="'Glue URL'">
        <a-input
          :placeholder="apiParams.drclusterglueipaddress.description"
          v-model:value="form.glueip"
        />
      </a-form-item>
        <a-form-item name="file" ref="file" :label="$t('label.add.disaster.recovery.cluster.info.glue.pri.key')">
          <a-textarea
            :placeholder="apiParams.drclusterprivatekey.description"
            v-model:value="form.file"
          ></a-textarea>
        </a-form-item>
      </a-card>

      <a-divider v-if="showCode" />

      <div v-if="showCode">
        <template v-if="!spinTemplate">
          <a-spin :tip="loading">
            <a-alert :message="$t('message.disaster.recovery.cluster.connection.test.title')" :description="alertDescription" :type="info">
            </a-alert>
          </a-spin>
        </template>
        <template v-else>
          <a-alert :message="$t('message.disaster.recovery.cluster.connection.test.title')" :description="alertDescription" :type="info">
          </a-alert>
        </template>
      </div>

      <div :span="24" class="action-button">
        <a-button @click="closeModals">{{ $t('label.cancel') }}</a-button>
        <a-button @click="testConnCode" :disabled="buttonDisabled" v-if="!testConnResult" type="primary">{{ $t('label.disaster.recovery.cluster.start.connection.test.description') }}</a-button>
        <a-button @click="handleSubmit" :disabled="!buttonDisabled" v-if="testConnResult" ref="submit" type="primary">{{ $t('label.ok') }}</a-button>
      </div>

    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { api } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
import eventBus from '@/config/eventBus'
import { Spin, Alert } from 'ant-design-vue'

export default {
  name: 'DisasterRecoveryClusterAdd',
  components: {
    TooltipLabel,
    ResourceIcon,
    Spin,
    Alert
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  setup () {
    const spinning = ref(false)
    const changeSpinning = () => {
      spinning.value = !spinning.value
    }
    return {
      spinning,
      changeSpinning
    }
  },
  data () {
    return {
      loading: false,
      spinTemplate: false,
      showCode: false,
      params: [],
      buttonDisabled: false,
      alertDescription: this.$t('message.disaster.recovery.cluster.connection.test.description'),
      testConnResult: false
    }
  },
  computed: {
    testDescText () {
      return this.showCode ? 'Disappear Code' : 'Show Code'
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createDisasterRecoveryCluster')
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        name: [{ required: true, message: this.$t('label.required') }],
        url: [{ required: true, message: this.$t('label.required') }],
        apikey: [{ required: true, message: this.$t('label.required') }],
        secretkey: [{ required: true, message: this.$t('label.required') }],
        glueip: [{ required: true, message: this.$t('message.error.required.input') }],
        file: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    // 재난복구클러스터 연결 테스트
    testConnCode () {
      this.showCode = !this.showCode
      this.buttonDisabled = true
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        const params = {
          drclusterurl: values.url,
          drclusterapikey: values.apikey,
          drclustersecretkey: values.secretkey,
          drclusterglueipaddress: values.glueip
        }
        api('connectivityTestsDisasterRecovery', params).then(json => {
          this.testConnResult = json.connectivitytestsdisasterrecoveryresponse
          if (this.testConnResult === false) {
            return
          } else {
            this.showCode = false
            this.spinTemplate = false
          }
          this.$emit('refresh-data')
        }).catch(error => {
          this.showCode = !this.showCode
          this.buttonDisabled = false
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: (error.response && error.response.headers && error.response.headers['x-description']) || error.message
          })
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
        this.showCode = !this.showCode
        this.buttonDisabled = false
      })
    },
    handleKeyPress (event) {
      // Check if the button is disabled and if a keyboard key is pressed
      if (this.buttonDisabled && event.key !== 'Tab') {
        this.buttonDisabled = false
        this.alertDescription = this.testDescText === 'Disappear Code' ? this.$t('message.waiting.dr.simulation.test') : this.$t('message.disaster.recovery.cluster.connection.test.description')
        this.spinTemplate = true
      }
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value
        .validate()
        .then(() => {
          const values = toRaw(this.form)
          this.loading = true
          const params = {
            name: values.name,
            description: values.displaytext,
            drclusterurl: values.url,
            drclusterapikey: values.apikey,
            drclustersecretkey: values.secretkey,
            drclusterprivatekey: values.file,
            drclusterglueipaddress: values.glueip,
            drclustertype: 'secondary'
          }
          api('createDisasterRecoveryCluster', params).then(json => {
            const jobId = json.createdisasterrecoveryclusterresponse.jobid
            this.$pollJob({
              jobId,
              title: this.$t('label.add.disaster.recovery.cluster'),
              description: values.name,
              successMethod: () => {
                this.$notification.success({
                  message: this.$t('message.success.add.disaster.recovery.cluster'),
                  duration: 0
                })
                eventBus.emit('dr-refresh-data')
              },
              loadingMessage: `${this.$t('label.add.disaster.recovery.cluster')} ${values.name} ${this.$t('label.in.progress')}`,
              catchMessage: this.$t('error.fetching.async.job.result'),
              catchMethod: () => {
                eventBus.emit('dr-refresh-data')
              }
            })
            eventBus.emit('dr-refresh-data')
            this.closeModals()
          }).finally(() => {
            this.loading = false
          })
        }).catch(error => {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        })
    },
    closeModals () {
      this.$emit('close-action')
    }
  },
  mounted () {
    document.body.addEventListener('keydown', this.handleKeyPress)
  },
  beforeUnmount () {
    document.body.removeEventListener('keydown', this.handleKeyPress)
  }
}
</script>

<style scoped lang="scss">
.form {

  width: 80vw;

  @media (min-width: 700px) {
    width: 550px;
  }
}

.required {
  color: #ff0000;

  &-label {
    display: none;

    &--visible {
      display: block;
    }

  }
}

.ant-alert-warning {
  border: 1px solid #ffe58f;
  background-color: #fffbe6;
}

.test-con-content {
  padding: 50px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
}
</style>
