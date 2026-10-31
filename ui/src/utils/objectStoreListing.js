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

export const OBJECT_STORE_LIST_TIMEOUT_MS = 30000

// Own every terminal path of the SDK stream, including silent close and cancellation.
export function readObjectStoreListing (createStream, timeout = OBJECT_STORE_LIST_TIMEOUT_MS) {
  let stream, complete
  let settled = false
  const records = []
  const promise = new Promise(resolve => { complete = resolve })
  const ignoreLateError = () => {}
  const finish = (status, error) => {
    if (settled) return
    settled = true
    clearTimeout(timer)
    if (stream?.removeListener) {
      stream.removeListener('data', onData)
      stream.removeListener('end', onEnd)
      stream.removeListener('error', onError)
      stream.removeListener('close', onClose)
      if (status !== 'success') {
        // A cancelled SDK request can still forward a late error to its destroyed stream.
        stream.on('error', ignoreLateError)
        try { if (stream.destroy) stream.destroy() } catch (_) { /* The UI must still finish if disposal fails. */ }
      }
    }
    complete({ status, error, records: status === 'success' ? records : [] })
  }
  const onData = record => { records.push(record) }
  const onEnd = () => finish('success')
  const onError = error => finish('error', error)
  const onClose = () => finish('aborted')
  const timer = setTimeout(() => finish('timeout'), timeout)
  try {
    stream = createStream()
    stream.on('data', onData)
    stream.on('end', onEnd)
    stream.on('error', onError)
    stream.on('close', onClose)
  } catch (error) {
    finish('error', error)
  }
  return { promise, cancel: () => finish('cancelled') }
}
