// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export function userDataContent (encoded) {
  if (encoded == null || encoded === '') return { encoded: '', bytes: new Uint8Array(), text: '', empty: true }
  if (typeof encoded !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw new Error('Invalid Base64')
  const binary = atob(encoded)
  if (btoa(binary) !== encoded) throw new Error('Non-canonical Base64')
  const bytes = Uint8Array.from(binary, character => character.charCodeAt(0))
  let text = null
  // Strict decoding preserves BOM, CRLF and trailing newlines; binary is never
  // silently replaced with U+FFFD or guessed to be plain text.
  try { text = decodeURIComponent(escape(binary)) } catch (error) { /* Binary content is downloadable as bytes and readable as Base64. */ }
  return { encoded, bytes, text, empty: false }
}

export function userDataLibraryParams (params) {
  const result = { ...params }
  const keyword = typeof result.keyword === 'string' ? result.keyword.trim() : ''
  if (keyword && /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(keyword)) {
    result.id = keyword
    delete result.keyword
  }
  // Sorting belongs to the current ListView page, not the listUserData API.
  for (const key of ['sortkey', 'sortorder', 'tab']) delete result[key]
  return result
}

// CNI shares UserData search semantics, with list-only UI state kept out of the API.
export function cniLibraryParams (params) {
  const result = userDataLibraryParams(params)
  const keys = ['id', 'name', 'keyword', 'domainid', 'account', 'projectid', 'isrecursive', 'listall', 'page', 'pagesize']
  return Object.fromEntries(keys.filter(key => result[key] !== undefined && result[key] !== '').map(key => [key, result[key]]))
}
