// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export function partialScaleRecoverySize (record) {
  if (record?.clustertype !== 'CloudManaged' || record.state !== 'Alert' ||
      !Array.isArray(record.virtualmachines) || !record.virtualmachines.length ||
      record.virtualmachines.some(vm => vm.state !== 'Running')) return null
  const controls = Number(record.controlnodes ?? record.masternodes ?? 1)
  const etcd = Number(record.etcdnodes ?? 0)
  const workers = record.virtualmachines.length - controls - etcd
  return Number.isInteger(controls) && controls > 0 && Number.isInteger(etcd) && etcd >= 0 &&
    Number.isInteger(workers) && workers > 0 && workers <= Number(record.size) &&
    (workers < Number(record.size) || record.scalenetworkcleanuppending === true) ? workers : null
}
