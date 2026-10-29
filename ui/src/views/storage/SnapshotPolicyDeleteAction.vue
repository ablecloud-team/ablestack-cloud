<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for copyright ownership.
Licensed under the Apache License, Version 2.0 (the "License");
http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <component :is="currentAction.invokedAsGroupAction ? 'SnapshotPolicyBulkDeleteDialog' : 'SnapshotPolicyDeleteDialog'" :resource="resource" :current-action="currentAction" @close-action="$emit('close-action', $event)" @refresh-data="$emit('refresh-data', $event)" />
</template>
<script>
import SnapshotPolicyDeleteDialog from './SnapshotPolicyDeleteDialog'
import SnapshotPolicyBulkDeleteDialog from './SnapshotPolicyBulkDeleteDialog'
export default {
  components: { SnapshotPolicyDeleteDialog, SnapshotPolicyBulkDeleteDialog },
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data']
}
</script>
