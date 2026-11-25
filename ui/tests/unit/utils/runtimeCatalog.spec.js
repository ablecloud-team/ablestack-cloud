// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"). You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

import { runtimeCatalogFilters, runtimeCatalogListQuery, runtimeCatalogReadParams } from '@/utils/runtimeCatalog'

test('builds catalogue filters from canonical option values and a trimmed keyword', () => {
  expect(runtimeCatalogFilters({ state: 'DRAFT', validationstatus: 'UNVERIFIED', keyword: '  audit-key  ' }))
    .toEqual({ state: 'DRAFT', validationstatus: 'UNVERIFIED', keyword: 'audit-key' })
  expect(runtimeCatalogFilters({ state: 'draft', validationstatus: ['VERIFIED'], keyword: '' })).toEqual({})
})

test('loads catalogue details by ID and constrains list queries to the catalogue', () => {
  expect(runtimeCatalogReadParams({ id: 'bundle', page: 2, pagesize: 20, state: 'REVOKED' }))
    .toEqual({ purpose: 'catalog', id: 'bundle' })
  expect(runtimeCatalogReadParams({ purpose: 'apply', sharedfilesystemid: 'other', page: 2, pagesize: 20, state: 'DRAFT' }))
    .toEqual({ purpose: 'catalog', page: 2, pagesize: 20, state: 'DRAFT' })
})

test('preserves catalogue list filters and paging through details without carrying the tab', () => {
  expect(runtimeCatalogListQuery({ keyword: 'key', version: 'v2', history: 'deleted', page: '2', pagesize: '50', tab: 'runtime.events' }))
    .toEqual({ keyword: 'key', version: 'v2', history: 'deleted', page: '2', pagesize: '50' })
})

test('combines an exact version with keyword and maps deleted history to only the deleted state', () => {
  expect(runtimeCatalogReadParams({ history: 'deleted', version: ' v1.2 ', keyword: 'key', state: 'DRAFT', page: 1, pagesize: 20 }))
    .toEqual({ purpose: 'catalog', state: 'DELETED', version: 'v1.2', keyword: 'key', page: 1, pagesize: 20 })
  expect(runtimeCatalogFilters({ version: 'v1.2', state: 'DELETED' })).toEqual({ version: 'v1.2' })
})
