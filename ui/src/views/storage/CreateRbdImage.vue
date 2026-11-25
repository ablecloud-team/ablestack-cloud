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
      class="form"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit"
     >
      <a-form-item ref="name" name="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name?.description"/>
        </template>
        <a-input
          id="primary-rbd-image-name"
          v-model:value="form.name"
          :placeholder="$t('message.rbd.name')" />
      </a-form-item>

        <a-form-item ref="size" name="size">
          <template #label>
            <tooltip-label :title="$t('label.sizegb')" :tooltip="apiParams.size?.description"/>
          </template>
          <a-input
            v-model:value="form.size"
            :placeholder="$t('message.rbd.size')"/>
      </a-form-item>

      <mold-dialog-actions :busy="loading">
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :loading="loading" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </mold-dialog-actions>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { mixinForm } from '@/utils/mixin'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'CreateRbdImage',
  mixins: [mixinForm],
  components: {
    ResourceIcon,
    TooltipLabel,
    MoldDialogActions
  },
  props: {
    resource: {
      type: Object,
      default: () => {}
    }
  },
  data () {
    return {
      snapshotZoneIds: [],
      zones: [],
      offerings: [],
      customDiskOffering: false,
      loading: false,
      isCustomizedDiskIOps: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createRbdImage')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.name') }],
        size: [{ required: true, message: this.$t('message.error.size') }]
      })
    },
    fetchData () {},
    async handleSubmit () {
      if (this.loading || !this.$store.getters.apis.createRbdImage) return
      this.loading = true
      try {
        await this.formRef.value.validate()
        const values = this.handleRemoveFields(toRaw(this.form))
        await postAPI('createRbdImage', { ...values, id: this.resource.id, zoneid: this.resource.zoneid }, { preserveOnFailure: true })
        this.closeModal()
      } catch (error) {
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },
    closeModal () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped>
.form { min-width: 0; width: 100%; }
</style>
