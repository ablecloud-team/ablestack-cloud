// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
// The ASF licenses this file under the Apache License, Version 2.0.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by law or agreed in writing, software is distributed
// on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
// Initial SharedFS DATA preparation only; generic protocol jobs keep their deadlines.
export const needsInitialPreparation = setup => !setup?.useexistingvolume && (setup?.services || []).some(x => ['NFS', 'SMB'].includes(x))
export const isPreparedBacking = fs => fs?.preparation?.status === 'PREPARED' || fs?.preparation?.phase === 'PREPARED'
// The API serializer wraps ordinary single responses in their objectName.
export function preparationFromResponse (json) {
  const state = json?.getsharedfilesystempreparationresponse?.sharedfilesystempreparation?.preparation
  if (!state || typeof state !== 'object' || !['RUNNING', 'PREPARED', 'OBSERVATION_PENDING', 'RECOVERY_REQUIRED'].includes(state.status)) {
    throw new Error('Invalid initial preparation observation response')
  }
  return state
}
export const observedPrepared = state => state?.observationAvailable === true && state?.status === 'PREPARED'
export const freshPreparation = data => ({ ...data, observationAvailable: data?.observationAvailable === true, canResume: data?.observationAvailable === true && data?.canResume === true })
export const unavailablePreparation = previous => ({ ...previous, observationAvailable: false, canResume: false, status: 'OBSERVATION_PENDING' })
export const canResumePreparation = state => state?.observationAvailable === true && state?.canResume === true && state?.status !== 'RUNNING' && state?.status !== 'RECOVERY_REQUIRED'

export async function observeInitialCreation ({ get, jobid, requestid, entityid, owner, changed, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)), maxAttempts = 1440 }) {
  for (let i = 0; i < maxAttempts; i++) {
    const listed = await get('listSharedFileSystems', { ...(entityid ? { id: entityid } : { requestid }), listall: true, ...owner })
    const fs = (listed.listsharedfilesystemsresponse?.sharedfs || [])[0]
    let preparation
    if (fs) {
      entityid = fs.id
      if (fs.preparation) {
        const reply = await get('getSharedFileSystemPreparation', { id: fs.id, requestid })
        preparation = preparationFromResponse(reply)
      }
      changed(fs, preparation)
      if (fs.state === 'Ready' && (!fs.preparation || observedPrepared(preparation))) return { sharedfs: fs, preparationObservation: preparation }
      if (preparation && ['RECOVERY_REQUIRED', 'OBSERVATION_PENDING'].includes(preparation.status)) return { sharedfs: fs, preparationObservation: preparation }
    }
    const job = (await get('queryAsyncJobResult', { jobid })).queryasyncjobresultresponse
    // A canceled job after management restart does not mean that the guest format failed.
    if (Number(job?.jobstatus) !== 0) {
      if (fs?.preparation) {
        if (preparation?.status === 'RUNNING') { await sleep(5000); continue }
        return { sharedfs: fs, preparationObservation: preparation }
      }
      if (Number(job?.jobstatus) === 1) return job.jobresult
      throw new Error(job?.jobresult?.errortext || 'Initial creation job did not complete')
    }
    await sleep(5000)
  }
  throw new Error('Initial preparation observation paused; reopen the preparation status using the same request')
}
