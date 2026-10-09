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

// Read deadlines bound UI loading; late responses never mutate the committed snapshot.
export function storageReadDeadline (promise, milliseconds = 15000) {
  let timer
  return Promise.race([
    promise,
    new Promise((resolve, reject) => {
      timer = setTimeout(() => {
        const error = new Error('Storage service read deadline exceeded')
        error.code = 'STORAGE_READ_TIMEOUT'
        reject(error)
      }, milliseconds)
    })
  ]).finally(() => clearTimeout(timer))
}

// Expose only fixed public causes; provider messages and request context stay private.
export function storageReadFailureKind (error) {
  try {
    const code = error && typeof error === 'object' ? error.code : null
    if (['STORAGE_READ_TIMEOUT', 'ECONNABORTED', 'ETIMEDOUT'].includes(code)) return 'TIMEOUT'
    if (code === 'ERR_NETWORK' || code === 'ECONNRESET' ||
      (error && typeof error === 'object' && error.request && !error.response && code !== 'ERR_CANCELED')) return 'TRANSPORT'
  } catch (_) {
    return 'READ_FAILURE'
  }
  return 'READ_FAILURE'
}

export async function readStorageSections (readers) {
  const names = Object.keys(readers)
  const results = await Promise.all(names.map(async name => {
    try { return { name, value: await storageReadDeadline(Promise.resolve().then(readers[name])) } } catch (error) { return { name, failed: true, failureKind: storageReadFailureKind(error) } }
  }))
  return {
    values: Object.fromEntries(results.filter(result => !result.failed).map(result => [result.name, result.value])),
    errors: results.filter(result => result.failed).map(result => result.name),
    errorKinds: Object.fromEntries(results.filter(result => result.failed).map(result => [result.name, result.failureKind]))
  }
}
