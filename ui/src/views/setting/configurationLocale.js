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

let catalogRequest

export function normalizeConfigurationSource (value) {
  return typeof value === 'string' ? value.replace(/\r\n?/g, '\n').trim() : ''
}

export function configurationText (record, field, locale, catalog) {
  const original = record[field]
  const source = normalizeConfigurationSource(original)
  const fallback = source ? original : (field === 'displaytext' ? record.name : '')
  if (locale !== 'ko_KR' || !source || !catalog || !Object.prototype.hasOwnProperty.call(catalog, record.name)) {
    return fallback
  }

  const translations = catalog[record.name]?.[field]
  if (!Array.isArray(translations)) return fallback
  const entry = translations.find(translation => normalizeConfigurationSource(translation.enSource) === source)
  return normalizeConfigurationSource(entry?.ko) || fallback
}

export function loadKoreanConfigurationCatalog () {
  if (!catalogRequest) {
    catalogRequest = import(/* webpackChunkName: "configuration-ko" */ '@/locales/global-settings/ko_KR.json')
      .then(module => module.default)
      .catch(() => {
        catalogRequest = undefined
        return null
      })
  }
  return catalogRequest
}
