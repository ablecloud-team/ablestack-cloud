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

import { resolveVolumeUpload, volumeUploadError } from '@/utils/volumeUploadError'

describe('URL upload registration result', () => {
  it('does not treat an accepted but failed job as upload success', async () => {
    const errortext = 'Maximum number of volumes for account has been exceeded'
    try {
      await resolveVolumeUpload({ jobid: 'job-1' }, async () => ({ jobstatus: 2, jobresult: { errortext } }))
      throw new Error('expected rejection')
    } catch (error) {
      expect(volumeUploadError(error, key => key)).toEqual({ detail: errortext, message: 'message.creation.source.upload.quota' })
    }
  })
  it('waits for successful registration and returns the registered volume', async () => {
    const track = jest.fn().mockResolvedValue({ jobstatus: 1, jobresult: { volume: { id: 'volume-1', state: 'Uploading' } } })
    expect(await resolveVolumeUpload({ jobid: 'job-1' }, track)).toEqual({ id: 'volume-1', state: 'Uploading' })
    expect(track).toHaveBeenCalledWith('job-1')
  })
  it('rejects an unknown job result instead of claiming upload success', async () => {
    await expect(resolveVolumeUpload({ jobid: 'job-1' }, async () => ({ jobstatus: null, trackingStatus: 'unknown' }))).rejects.toThrow('unknown')
  })
})
