<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <a-form layout="vertical" class="dialog-form shared-network-rule-form">
    <template v-for="section in sections" :key="section.title"><div v-if="section.title" class="network-form-group-label">{{section.title}}</div>
    <a-row :gutter="20"><a-col v-for="field in section.fields" :key="field.name||field.label" :xs="24" :md="field.wide?24:12">
      <a-form-item :label="field.label" :required="field.required" :validate-status="field.error?'error':''" :help="field.error||field.help">
        <a-select v-if="field.type==='select'" v-model:value="field.value" :disabled="field.disabled" :options="field.options||[{value:field.value,label:field.value}]" />
        <a-input-number v-else-if="field.type==='number'" v-model:value="field.value" :min="field.min??0" :max="field.max" :disabled="field.disabled" style="width:100%" />
        <a-switch v-else-if="field.type==='switch'" v-model:checked="field.value" :disabled="field.disabled" checked-children="사용" un-checked-children="사용 안 함" />
        <a-input v-else v-model:value="field.value" :disabled="field.disabled" />
      </a-form-item>
    </a-col></a-row></template>
  </a-form>
</template>
<script>
import { computed } from 'vue'
export default {
  name: 'NetworkRuleForm',
  props: { fields: { type: Array, default: ()=>[] } },
  setup (props) {
    const sections = computed(()=>{
      const visible = props.fields.filter(f=>!f.when||f.when.values.includes(props.fields.find(x=>x.name===f.when.field)?.value))
      const groups = []
      for (const field of visible) {
        const title = field.group || ''
        let section = groups.find(g=>g.title===title)
        if (!section) { section={title,fields:[]}; groups.push(section) }
        section.fields.push(field)
      }
      return groups
    })
    return {sections}
  }
}
</script>
