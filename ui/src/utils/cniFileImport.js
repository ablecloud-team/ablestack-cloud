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

import { canonicalCni } from './cniRegistration'

export const cniHelpMarker = 'local:cni-configuration'
export function documentationUrl (base, section, locale = 'en') {
  return section === cniHelpMarker ? 'help/cni-configuration.' + (locale.toLowerCase().startsWith('ko') ? 'ko' : 'en') + '.html' : base + '/' + section
}
const fail = key => { throw new Error('message.cni.file.' + key) }
export async function readCniFile (file, mode, maxLength, read = file => file.arrayBuffer()) {
  if (!file || !['text', 'base64'].includes(mode)) fail('read')
  if (/\.(zip|gz|tgz|tar)$/i.test(file.name || '')) fail('archive')
  if (!file.size) fail('empty')
  const limit = mode === 'text' ? Math.floor(maxLength / 4) * 3 : Math.min(maxLength * 4, 4194304)
  if (file.size > limit) fail('size')
  let buffer
  try { buffer = await read(file) } catch (_) { fail('read') }
  const bytes = new Uint8Array(buffer)
  if (bytes.length !== file.size) fail('read')
  let binary = ''
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192))
  let text
  try { text = decodeURIComponent(escape(binary)) } catch (_) { fail('encoding') }
  const encoded = canonicalCni(text, mode === 'base64', maxLength)
  return { name: file.name, encoded, bytes: atob(encoded).length, mode }
}
