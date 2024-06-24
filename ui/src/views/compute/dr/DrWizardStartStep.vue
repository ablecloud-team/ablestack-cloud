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
  <div>
    <a-card
      class="ant-form-text card-waiting-launch"
      :ref="formRef"
      :model="form"
      :rules="rules"
      style="text-align: justify; margin: 10px 0; padding: 24px;"
      v-html="$t(description.waiting)"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit">
    </a-card>
    <div class="form-action">
      <a-button ref="submit" type="primary" @click="handleSubmit" class="button-next">
        <experiment-outlined /> {{ $t('label.launch.dr.simulation.test') }}
      </a-button>
    </div>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
export default {
  props: {
    prefillContent: {
      type: Object,
      default: function () {
        return {}
      }
    }
  },
  data: () => ({
    formItemLayout: {
      labelCol: { span: 6 },
      wrapperCol: { span: 14 }
    },
    zoneDescription: {
      Core: 'message.desc.core.zone',
      Edge: 'message.desc.edge.zone'
    },
    formModel: {},
    description: {
      waiting: 'message.launch.dr.simulation.test',
      launching: 'message.please.wait.while.zone.is.being.created'
    },
    isLaunchTest: false
  }),
  created () {
    this.initForm()
  },
  watch: {
    formModel: {
      deep: true,
      handler (changedFields) {
        const fieldsChanged = toRaw(changedFields)
        this.$emit('fieldsChanged', fieldsChanged)
      }
    }
  },
  computed: {
    zoneSuperType () {
      return this.prefillContent.zoneSuperType ? this.prefillContent.zoneSuperType : 'Core'
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        zoneSuperType: this.zoneSuperType
      })
      this.rules = reactive({
        zoneSuperType: [{ required: true, message: this.$t('message.error.zone.type') }]
      })
      this.formModel = toRaw(this.form)
    },
    handleSubmit () {
      this.isLaunchTest = true
      this.$emit('nextPressed')
    }
  }
}
</script>
<style scoped lang="less">
  .form-content {
    border: 1px dashed #e9e9e9;
    border-radius: 6px;
    min-height: 200px;
    text-align: center;
    vertical-align: center;
    padding: 8px;
    padding-top: 16px;
    margin-top: 8px;
  }
  .card-item {
    margin-top: 10px;
    .card-form-item {
      float: left;
      font-weight: bold;
        font-size: 15px;
    }
    .checkbox-advance {
      margin-top: 10px;
    }
    .zone-support {
      text-align: justify;
    }
  }
  .card-waiting-launch {
    text-align: center;
    margin: 10px 0;
    width: 100%;
    padding: 20px;
    font-size: 20px;
  }
  .card-launch-description {
    text-align: justify;
    margin: 10px 0;
    width: 100%;
    padding: 0;
  }
  .card-launch-content {
    text-align: justify;
    margin: 10px 0;
    width: 100%;
    font-size: 15px;
    max-height: 45vh;
    overflow-y: auto;
  }
  :deep(.step-error) {
    color: #f5222d;
    margin-top: 20px;
    :deep(.ant-card-body) {
      padding: 15px;
      text-align: justify;
    }
  }
</style>
