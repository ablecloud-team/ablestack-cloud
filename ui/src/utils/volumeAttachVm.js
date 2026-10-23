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

// Display names may change or repeat. Always select and submit the VM UUID.
export function vmDisplayName (vm = {}) {
  return vm.displayname || vm.name || vm.id || ''
}

export function vmIdentity (vm = {}) {
  return [...new Set([vm.name, vm.instancename, vm.id].filter(Boolean))].join(' · ')
}

export function vmLabel (vm = {}) {
  return [vmDisplayName(vm), vmIdentity(vm)].filter(Boolean).join(' — ')
}

export function vmMatchesSearch (vm, input) {
  if (!vm) return false
  const search = String(input || '').trim().toLocaleLowerCase()
  return [vm.displayname, vm.name, vm.instancename, vm.id].some(value => String(value || '').toLocaleLowerCase().includes(search))
}
