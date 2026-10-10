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

export function volumeUploadError (error, translate) {
  const data = error?.response?.data || {}
  const response = data.postuploadvolumeresponse || data.uploadvolumeresponse || data.errorresponse || {}
  const detail = response.errortext || error?.message || String(error || '')
  const quota = /ResourceAllocationException|resource limit|maximum number of volumes|exceeds? (?:the )?(?:limit|quota)/i.test(detail)
  return { detail, message: translate(quota ? 'message.creation.source.upload.quota' : 'message.upload.volume.failed') }
}

// An async upload response only confirms acceptance. Registration may still fail.
export async function resolveVolumeUpload (response, trackJob) {
  if (!response?.jobid) return response
  const job = await trackJob(response.jobid)
  if (job.jobstatus === 1) return job.jobresult?.volume
  if (job.jobstatus === 2) {
    throw { response: { data: { uploadvolumeresponse: job.jobresult } } } // eslint-disable-line no-throw-literal
  }
  throw new Error('Upload registration result is unknown; check the volume list before submitting again.')
}
