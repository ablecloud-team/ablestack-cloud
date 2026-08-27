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

import fs from 'fs'
import path from 'path'

const uiRoot = path.resolve(__dirname, '../../../..')
const sourceFiles = [
  'src/views/storage/SharedFSTab.vue',
  'src/views/storage/CreateSharedFS.vue'
]
const localeFiles = {
  en: 'public/locales/en.json',
  ko_KR: 'public/locales/ko_KR.json'
}
const storagePrefixes = ['label.storage.service.', 'message.storage.service.']

const readUiFile = relativePath => fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
const isStorageKey = key => storagePrefixes.some(prefix => key.startsWith(prefix))

const literalStorageKeys = () => {
  const keyPattern = /\$t\(\s*['"]([^'"]+)['"]/g
  const keys = new Set()

  sourceFiles.forEach(sourceFile => {
    const source = readUiFile(sourceFile)
    let match
    while ((match = keyPattern.exec(source)) !== null) {
      if (isStorageKey(match[1])) {
        keys.add(match[1])
      }
    }
  })

  return [...keys].sort()
}

const localeMessages = Object.fromEntries(
  Object.entries(localeFiles).map(([locale, localeFile]) => [
    locale,
    JSON.parse(readUiFile(localeFile))
  ])
)

describe('SharedFS runtime locale contract', () => {
  it.each(Object.keys(localeFiles))('defines every literal Storage Service key in %s', locale => {
    const messages = localeMessages[locale]
    const missing = literalStorageKeys().filter(key => {
      const value = messages[key]
      return typeof value !== 'string' || value.trim() === '' || value === key
    })

    expect(missing).toEqual([])
  })

  it('keeps English and Korean Storage Service key sets aligned', () => {
    const storageKeys = locale => Object.keys(localeMessages[locale])
      .filter(isStorageKey)
      .sort()

    expect(storageKeys('ko_KR')).toEqual(storageKeys('en'))
  })
})
