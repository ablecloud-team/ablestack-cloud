// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
export function snapshotDownloadResult (result) {
  const data = result?.extractsnapshotresponse?.snapshot || result?.snapshot
  if (!data || !['QCOW2', 'RAW', 'VHD', 'VHDX', 'OVA', 'VMDK', 'ISO'].includes(data.format) || !(data.size > 0)) return null
  if (['QCOW2', 'RAW'].includes(data.format) && !(data.physicalsize > 0)) return null
  try {
    const url = new URL(data.url)
    const filename = decodeURIComponent(url.pathname.split('/').pop())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash ||
        !filename.match(/^[a-zA-Z0-9-]+\.[a-z0-9]+$/) || filename !== data.filename || !filename.endsWith('.' + data.format.toLowerCase())) return null
    return { ...data, url: url.href }
  } catch (_) { return null }
}
