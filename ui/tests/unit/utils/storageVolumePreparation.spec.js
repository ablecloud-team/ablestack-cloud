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

import { preparationObservation, preparationResumeAllowed, preparationTime, preparationCapacity, preparationDiagnostics } from '@/utils/storageVolumePreparation'
const wire = value => ({ getstorageservicevolumepreparationresponse: { storageserviceruntime: { id: 'instance', success: true, resultjson: JSON.stringify(value) } } })
const minimal = { success: true, status: 'NOT_STARTED', operationId: 'volume-data' }
describe('Preparation wire scope and safe presentation', () => {
  it('accepts an actual NOT_STARTED native response without fabricating receipt or filesystem', () => {
    const value = preparationObservation(wire(minimal), 'instance', 'data')
    expect(value).toEqual(minimal); expect(preparationResumeAllowed(value)).toBe(false)
  })
  it.each([
    null, {}, { getstorageservicevolumepreparationresponse: null },
    { getstorageservicevolumepreparationresponse: { id: 'foreign', success: true, resultjson: JSON.stringify(minimal) } },
    { getstorageservicevolumepreparationresponse: { id: 'instance', success: 'true', resultjson: JSON.stringify(minimal) } },
    wire({ ...minimal, operationId: 'volume-foreign' }),
    wire({ ...minimal, operation: { volumeUuid: 'foreign', operationId: 'volume-data' } }),
    wire({ ...minimal, currentIdentity: { volumeUuid: 'foreign' } }),
    wire({ ...minimal, observedOperation: [] }),
    wire({ ...minimal, observedOperation: { volumeUuid: 'foreign' } }),
    wire({ ...minimal, success: false }),
    wire({ ...minimal, status: 'UNKNOWN_NEW_SCHEMA' })
  ])('fails closed for malformed or foreign observation %p', value => {
    expect(() => preparationObservation(value, 'instance', 'data')).toThrow()
  })
  it.each([null, undefined, false, true, '', '1792100000', -1, Infinity, NaN, 8640000000001])('never treats an unknown or malformed timestamp %p as observed time', value => {
    expect(preparationTime(value)).toBe('—')
  })
  it('formats valid epoch and measured capacity without string coercion', () => {
    expect(preparationTime(1792100000)).toBe(new Date(1792100000000).toISOString())
    expect(preparationCapacity(10995116277760)).toBe('10.00 TiB')
    for (const value of [0, -1, true, '10995116277760', Infinity]) expect(preparationCapacity(value)).toBe('—')
  })
  it('retains bounded plain-text diagnostics for the collapsed detail without interpreting markup', () => {
    const value = preparationDiagnostics({ diagnostic: '<script>Traceback</script>' + 'x'.repeat(9000) })
    expect(value.startsWith('<script>Traceback</script>')).toBe(true)
    expect(value.length).toBe(8192)
  })
})
