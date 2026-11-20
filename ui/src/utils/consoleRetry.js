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

const CONSOLE_FAILED = 'mold:console-connect-failed'
const CONSOLE_FINISHED = 'mold:console-connect-finished'
const LISTEN_TIMEOUT_MILLIS = 60000

function setConsoleAttempt (url, attempt) {
  const hash = new URLSearchParams(url.hash.slice(1))
  hash.set('moldConsoleAttempt', attempt)
  if (attempt === '1') {
    hash.set('moldConsoleOpener', window.location.origin)
  } else {
    hash.delete('moldConsoleOpener')
  }
  url.hash = hash.toString()
}

export function openConsoleWithRetry (url, createEndpoint, onError) {
  const target = new URL(url, window.location.href)
  if (!target.pathname.endsWith('/resource/noVNC/vnc.html') || !['https:', 'http:'].includes(target.protocol)) {
    window.open(url, '_blank')
    return
  }

  setConsoleAttempt(target, '1')
  const popup = window.open(target.href, '_blank')
  if (!popup) {
    return
  }

  const stopListening = () => {
    window.removeEventListener('message', onMessage)
    window.clearTimeout(listenTimeout)
  }
  const onMessage = async event => {
    if (event.source !== popup || event.origin !== target.origin ||
        ![CONSOLE_FAILED, CONSOLE_FINISHED].includes(event.data)) {
      return
    }

    stopListening()
    if (event.data !== CONSOLE_FAILED || popup.closed) {
      return
    }
    try {
      const json = await createEndpoint()
      const endpoint = json?.createconsoleendpointresponse?.consoleendpoint
      if (!endpoint?.success || !endpoint.url) {
        throw new Error(endpoint?.details || 'Unable to create console endpoint')
      }
      const next = new URL(endpoint.url, window.location.href)
      if (!['https:', 'http:'].includes(next.protocol)) {
        throw new Error('Invalid console endpoint URL')
      }
      setConsoleAttempt(next, '2')
      if (!popup.closed) {
        popup.location.replace(next.href)
      }
    } catch (error) {
      onError(error)
    }
  }

  const listenTimeout = window.setTimeout(stopListening, LISTEN_TIMEOUT_MILLIS)
  window.addEventListener('message', onMessage)
}
