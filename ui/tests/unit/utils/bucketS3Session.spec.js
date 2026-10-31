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

import { BucketS3Session } from '@/utils/bucketS3Session'
import { clearBucketS3Sessions } from '@/utils/bucketCredentialMemory'
import * as Minio from 'minio'
jest.mock('minio', () => ({ Client: jest.fn() }))
const payload = { getbucketcredentialsresponse: { url: 'http://store:3333/bucket', accesskey: 'PRIVATE_ACCESS', usersecretkey: 'PRIVATE_SECRET' } }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { resolve, reject, promise } }
afterEach(() => { clearBucketS3Sessions(); jest.clearAllMocks() })
beforeEach(() => { Minio.Client.mockImplementation(args => ({ ...args })) })
it('deduplicates concurrent authorized lookups without persisting keys', async () => {
  const wait = deferred()
  const load = jest.fn(() => wait.promise)
  const session = new BucketS3Session(load)
  const one = session.getClient(); const two = session.getClient()
  expect(load).toHaveBeenCalledTimes(1)
  wait.resolve(payload)
  expect(await one).toBe(await two)
  expect(Minio.Client).toHaveBeenCalledWith(expect.objectContaining({ accessKey: 'PRIVATE_ACCESS', secretKey: 'PRIVATE_SECRET', port: 3333 }))
  expect(JSON.stringify(localStorage)).not.toContain('PRIVATE_')
  expect(JSON.stringify(sessionStorage)).not.toContain('PRIVATE_')
})
it('discards a credential response that arrives after a context is disposed', async () => {
  const wait = deferred(); const session = new BucketS3Session(() => wait.promise)
  const pending = session.getClient(); session.dispose(); wait.resolve(payload)
  await expect(pending).rejects.toThrow('context.changed')
  expect(Minio.Client).not.toHaveBeenCalled()
})
it('drops SDK key values and prevents reuse on logout', async () => {
  const session = new BucketS3Session(() => Promise.resolve(payload)); const sdk = await session.getClient()
  clearBucketS3Sessions()
  expect(sdk.accessKey).toBe(''); expect(sdk.secretKey).toBe('')
  await expect(session.getClient()).rejects.toThrow('context.changed')
})
it('never retries failed credential lookup automatically and allows one explicit retry', async () => {
  const load = jest.fn().mockRejectedValueOnce(new Error('PRIVATE_SECRET')).mockResolvedValueOnce(payload)
  const session = new BucketS3Session(load)
  await expect(session.getClient()).rejects.toThrow('message.bucket.credentials.fetch.failed')
  expect(load).toHaveBeenCalledTimes(1)
  expect(await session.getClient()).toBeTruthy()
  expect(load).toHaveBeenCalledTimes(2)
})
