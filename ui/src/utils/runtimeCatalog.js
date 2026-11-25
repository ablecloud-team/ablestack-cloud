// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"). You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

export const runtimeCatalogApi = 'listStorageServiceRuntimeBundles'

export function runtimeCatalogTranslationKey (i18n, group, value) {
  if (!group || value == null || value === '') return undefined
  const key = 'label.runtime.catalog.' + group + '.' + value
  // The locale files store complete dotted keys at their top level.
  return Object.prototype.hasOwnProperty.call(i18n.getLocaleMessage(i18n.locale), key) ? key : undefined
}

export const runtimeCatalogChoices = {
  state: ['AVAILABLE', 'DISABLED', 'DRAFT', 'PUBLISHED', 'SUSPENDED', 'RETIRED', 'REVOKING', 'REVOKED'],
  validationstatus: ['UNVERIFIED', 'RUNNING', 'VERIFIED', 'FAILED', 'UNKNOWN']
}

export function runtimeCatalogFilters (input) {
  const filters = {}
  if (typeof input.keyword === 'string' && input.keyword.trim()) filters.keyword = input.keyword.trim()
  if (typeof input.version === 'string' && input.version.trim()) filters.version = input.version.trim()
  for (const [field, choices] of Object.entries(runtimeCatalogChoices)) {
    if (choices.includes(input[field])) filters[field] = input[field]
  }
  if (input.history === 'deleted') {
    filters.history = 'deleted'
    delete filters.state
  }
  return filters
}

export function runtimeCatalogReadParams ({ id, page, pagesize, ...params }) {
  if (id) return { purpose: 'catalog', id }
  const { history, ...filters } = runtimeCatalogFilters(params)
  if (history === 'deleted') filters.state = 'DELETED'
  return { purpose: 'catalog', page, pagesize, ...filters }
}

export function runtimeCatalogListQuery (query) {
  const result = runtimeCatalogFilters(query)
  for (const field of ['page', 'pagesize']) {
    if (typeof query[field] === 'string' && /^[1-9]\d*$/.test(query[field])) result[field] = query[field]
  }
  return result
}
