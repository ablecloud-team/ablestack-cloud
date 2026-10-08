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

// Prototype-only adapter. No HTTP client or credentials; unsupported calls fail closed.
const multiple = new URLSearchParams(window.location.search).get('sample') === 'pages'
const events = Array.from({length: multiple ? 42 : 3}, (_, i) => ({
  id: 'demo-event-'+i, level: 'INFO', type: ['KUBERNETES.CLUSTER.CREATE','KUBERNETES.CLUSTER.SCALE','KUBERNETES.CLUSTER.UPDATE'][i%3],
  state: 'Completed', description: ['클러스터 생성 완료','워커 노드 확장 완료','클러스터 설정 변경 완료'][i%3],
  username: 'demo-admin', account: 'demo-team', domain: 'ROOT', created: '2026-10-08T'+String(12-i%3).padStart(2,'0')+':00:00+0900'
}))
let notes = Array.from({length: multiple ? 21 : 2}, (_, i) => ({
  id: 'demo-note-'+i, userid: 'demo-user', username: 'demo-admin', adminsonly: i%2 === 1,
  annotation: i%2 ? '유지보수 일정을 확인했습니다.' : '클러스터 운영 메모입니다. 워커 확장 후 상태를 확인하세요.',
  created: '2026-10-08T12:00:00+0900'
}))
const response = (key, rows, params) => ({[key+'response']: {count: rows.length, [key === 'listevents' ? 'event' : 'annotation']: rows.slice(((params.page || 1)-1)*(params.pagesize || 10), (params.page || 1)*(params.pagesize || 10))}})
export function getAPI(command, params = {}) {
  if (command === 'listEvents') return Promise.resolve(response('listevents', events, params))
  if (command === 'listAnnotations') return Promise.resolve(response('listannotations', notes, params))
  return Promise.reject(new Error('목업에서 지원하지 않는 API: '+command))
}
export function postAPI(command, params = {}) {
  if (command === 'addAnnotation') notes.unshift({...params,id:'demo-note-new-'+notes.length,userid:'demo-user',username:'demo-admin',created:'2026-10-08T12:00:00+0900'})
  else if (command === 'removeAnnotation') notes = notes.filter(n => n.id !== params.id)
  else if (command === 'updateAnnotationVisibility') notes = notes.map(n => n.id === params.id ? {...n,adminsonly:params.adminsonly} : n)
  else return Promise.reject(new Error('목업에서 지원하지 않는 API: '+command))
  return Promise.resolve({})
}
