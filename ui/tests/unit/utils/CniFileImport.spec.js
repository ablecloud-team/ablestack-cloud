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

import { readCniFile, documentationUrl } from '@/utils/cniFileImport'
const file = (data, name = 'cni.yaml') => ({ name, size: data.length, arrayBuffer: async () => Uint8Array.from(data).buffer })
const bytes = value => [...Buffer.from(value)]
test.each(['#cloud-config\n한글 😀\n', '\ufeff#cloud-config\r\n한글\r\n\r\n', 'eA=='])('text file preserves exact bytes %j', async text => {
  const draft = await readCniFile(file(bytes(text)), 'text', 32768)
  expect(Buffer.from(draft.encoded, 'base64').equals(Buffer.from(text))).toBe(true)
  expect(draft.bytes).toBe(Buffer.byteLength(text))
})
test.each(['eA==\r\n', 'eA', '_wAB', 'eA%3D%3D'])('explicit Base64 normalizes %j', async text => {
  const draft = await readCniFile(file(bytes(text)), 'base64', 32768)
  expect(draft.encoded).toBe(text === '_wAB' ? '/wAB' : 'eA==')
})
test.each([[], [255, 0], [255, 254, 65, 0], [239, 187, 191], bytes('  \r\n')].map(value => [value]))('invalid text file %j', async value => {
  await expect(readCniFile(file(value), 'text', 32768)).rejects.toThrow()
})
test.each(['!', 'A', 'eA=', '\ufeffeA=='])('invalid Base64 preserves failure %j', async text => {
  await expect(readCniFile(file(bytes(text)), 'base64', 32768)).rejects.toThrow('message.cni.validation.')
})
test('limit is enforced before read and after Base64 normalization', async () => {
  const read = jest.fn(); await expect(readCniFile({ name: 'big', size: 24577 }, 'text', 32768, read)).rejects.toThrow('message.cni.file.size'); expect(read).not.toHaveBeenCalled()
  const exact = await readCniFile(file(bytes('x'.repeat(24576))), 'text', 32768); expect(exact.encoded.length).toBe(32768)
  await expect(readCniFile(file(bytes('eHh4eHh4')), 'base64', 4)).rejects.toThrow('message.cni.validation.size')
})
test('read rejection, short read, archive and unsupported mode are rejected', async () => {
  await expect(readCniFile(file([1]), 'text', 100, () => Promise.reject(new Error('IO')))).rejects.toThrow('message.cni.file.read')
  await expect(readCniFile(file([1]), 'text', 100, async () => new ArrayBuffer(0))).rejects.toThrow('message.cni.file.read')
  await expect(readCniFile(file([1], 'file.zip'), 'text', 100)).rejects.toThrow('message.cni.file.archive')
  await expect(readCniFile(file([1]), 'guess', 100)).rejects.toThrow('message.cni.file.read')
})
test('CNI help is local and language-specific; other help destinations remain identical', () => {
  expect(documentationUrl('https://docs.example', 'local:cni-configuration', 'ko_KR')).toBe('help/cni-configuration.ko.html')
  expect(documentationUrl('', 'local:cni-configuration', 'fr')).toBe('help/cni-configuration.en.html')
  expect(documentationUrl('https://docs.example', 'adminguide/vm.html#ssh', 'ko_KR')).toBe('https://docs.example/adminguide/vm.html#ssh')
})
