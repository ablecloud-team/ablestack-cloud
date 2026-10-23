// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
export default {
  data: () => ({ version: 0, disposed: false, loading: false, saving: false, loaded: false, readError: '', submitError: '', unknownResult: false }),
  computed: {
    managementContext () { return JSON.stringify([this.resource?.id, this.$route.meta.name, this.$store.state?.user?.token, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.project?.id]) },
    managementDisabled () { return this.loading || this.saving || !this.loaded || this.unknownResult },
    managementAdmin () { return this.$store.getters.userInfo?.roletype === 'Admin' },
    managementCanEdit () { return ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo?.roletype) && !!this.$store.getters.apis?.[this.managementApi] },
    managementFields () { return Object.fromEntries((this.$store.getters.apis?.[this.managementApi]?.params || []).map(p => [p.name, p])) }
  },
  watch: { managementContext: { immediate: true, handler () { this.saving = false; this.load() } } },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    currentRequest (version, context) { return !this.disposed && version === this.version && context === this.managementContext },
    managementReason (error) { return Object.values(error?.response?.data || {}).find(v => v?.errortext)?.errortext || (error?.message?.startsWith('message.') ? this.$t(error.message) : error?.message) || this.$t('message.request.failed') },
    checkManagementPermission () { if (!this.managementCanEdit) throw new Error('message.storage.management.admin.only') },
    failedSubmission (error) { if (!error.response && !error.errorFields && this.postStarted) this.unknownResult = true; this.submitError = this.managementReason(error) }
  }
}
