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

const labels = {
  true: 'label.enabled',
  false: 'label.disabled',
  enabled: 'label.enabled',
  disabled: 'label.disabled',
  up: 'label.host.state.up',
  down: 'label.host.state.down',
  disconnected: 'label.host.state.disconnected',
  alert: 'label.host.state.alert',
  maintenance: 'label.host.state.maintenance',
  prepareformaintenance: 'label.host.state.preparing',
  errorinmaintenance: 'label.host.state.maintenance.error',
  errorinprepareformaintenance: 'label.host.state.maintenance.error',
  degraded: 'label.host.state.degraded',
  available: 'label.host.state.available',
  suspect: 'label.host.state.suspect',
  checking: 'label.host.state.checking',
  recovering: 'label.host.state.recovering',
  recovered: 'label.host.state.recovered',
  fenced: 'label.host.state.fenced',
  on: 'label.host.power.on',
  off: 'label.host.power.off'
}

export function hostStatusLabel (value) {
  const key = String(value ?? '').toLowerCase()
  return Object.prototype.hasOwnProperty.call(labels, key) ? labels[key] : 'label.unknown'
}

export function hostStatusColor (value) {
  const key = String(value ?? '').toLowerCase()
  if (['true', 'enabled', 'up', 'on', 'available', 'recovered'].includes(key)) return 'success'
  if (['false', 'disabled', 'off'].includes(key)) return 'default'
  if (['down', 'disconnected', 'fenced', 'errorinmaintenance', 'errorinprepareformaintenance'].includes(key)) return 'error'
  if (['checking', 'recovering', 'prepareformaintenance'].includes(key)) return 'processing'
  if (['maintenance', 'alert', 'degraded', 'suspect'].includes(key)) return 'warning'
  return 'default'
}
