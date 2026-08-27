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

import { appendApiData } from '@/api'

describe('API form data serialization', () => {
  it('omits absent values instead of serializing them as strings', () => {
    const params = appendApiData(new URLSearchParams(), {
      miniops: undefined,
      maxiops: null,
      size: 0,
      enabled: false,
      description: ''
    })

    expect(params.has('miniops')).toBe(false)
    expect(params.has('maxiops')).toBe(false)
    expect(params.get('size')).toBe('0')
    expect(params.get('enabled')).toBe('false')
    expect(params.get('description')).toBe('')
  })
})
