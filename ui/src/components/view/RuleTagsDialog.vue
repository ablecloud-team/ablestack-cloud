<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

<template>
  <MoldDialog size="md" focus-guard :title="$t('label.edit.tags')" @cancel="$emit('cancel')">
    <ResourceTags :resource="target" :resource-type="resourceType" :parent-ip-id="resource.id" :can-edit="rule.canEdit" />
    <template #footer>
      <a-button type="primary" @click="$emit('cancel')">{{ $t('label.done') }}</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import MoldDialog from './MoldDialog.vue'
import ResourceTags from './ResourceTags.vue'

export default {
  name: 'RuleTagsDialog',
  components: { MoldDialog, ResourceTags },
  props: {
    resource: { type: Object, required: true },
    rule: { type: Object, required: true },
    resourceType: { type: String, required: true }
  },
  emits: ['cancel'],
  computed: {
    target () {
      const { account, domainid, projectid } = this.resource
      return { id: this.rule.id, account, domainid, projectid }
    }
  }
}
</script>
