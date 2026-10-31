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

import * as Minio from 'minio'
import { registerBucketSession, forgetBucketSession } from '@/utils/bucketCredentialMemory'

export class BucketS3Session {
  constructor (loadCredentials) {
    this.loadCredentials = loadCredentials
    this.disposed = false
    this.client = null
    this.pending = null
    registerBucketSession(this)
  }

  async getClient () {
    if (this.disposed) throw new Error('message.bucket.credentials.context.changed')
    if (this.client) return this.client
    if (!this.pending) {
      this.pending = this.loadCredentials().then(json => {
        const data = json?.getbucketcredentialsresponse?.bucketcredential || json?.getbucketcredentialsresponse
        if (this.disposed) throw new Error('message.bucket.credentials.context.changed')
        if (!data?.accesskey || !data?.usersecretkey || !data?.url) throw new Error('message.bucket.credentials.fetch.failed')
        const url = new URL(data.url)
        this.client = new Minio.Client({
          endPoint: url.hostname,
          port: url.port ? Number(url.port) : url.protocol === 'https:' ? 443 : 80,
          useSSL: url.protocol === 'https:',
          accessKey: data.accesskey,
          secretKey: data.usersecretkey
        })
        return this.client
      }).catch(() => {
        throw new Error(this.disposed ? 'message.bucket.credentials.context.changed' : 'message.bucket.credentials.fetch.failed')
      }).finally(() => { this.pending = null })
    }
    return this.pending
  }

  dispose () {
    this.disposed = true
    // In-flight callbacks retain only their operation; subsequent actions cannot reuse a key.
    if (this.client) {
      this.client.accessKey = ''
      this.client.secretKey = ''
      this.client.sessionToken = ''
    }
    this.client = null
    this.pending = null
    this.loadCredentials = null
    forgetBucketSession(this)
  }
}
