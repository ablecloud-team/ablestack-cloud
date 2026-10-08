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

const col=(title,key,width)=>({title,key,dataIndex:key,width})
const actions=col('작업','actions',110)
export const networkModels={
  firewall:{kind:'firewall',create:'firewall-create',edit:'firewall-replace',remove:'firewall-delete',addLabel:'방화벽 규칙 추가',help:'소스 CIDR과 TCP·UDP 포트 또는 ICMP 유형·코드를 설정합니다. 관리 포트 규칙은 보호됩니다.',columns:[col('공인 IP','ip',140),col('소스 CIDR','cidr',165),col('프로토콜','protocol',100),col('시작 / 종료 포트','ports',140),col('상태','state',85),col('소유권','owner',180),actions],rows:[{id:1,name:'Kubernetes API',ip:'203.0.113.10',cidr:'198.51.100.0/24',protocol:'TCP',ports:'6443 – 6443',state:'Active',owner:'Mold 관리',protected:true},{id:2,name:'app-web',ip:'203.0.113.12',cidr:'198.51.100.0/24',protocol:'TCP',ports:'8080 – 8080',state:'Active',owner:'사용자 규칙',protected:false}]},
  portforwarding:{kind:'portforwarding',create:'pf-create',edit:'pf-edit',remove:'pf-delete',addLabel:'포트 포워딩 추가',help:'공인 포트 범위와 게스트 포트 범위, 대상 VM·NIC를 함께 설정합니다. API·SSH 관리 규칙은 보호됩니다.',columns:[col('공인 IP','ip',130),col('공인 포트','publicPorts',115),col('게스트 포트','privatePorts',115),col('프로토콜','protocol',85),col('대상 VM / NIC','target',185),col('소유권','owner',170),actions],rows:[{id:1,name:'Kubernetes API',ip:'203.0.113.10',publicPorts:'6443 – 6443',privatePorts:'6443 – 6443',protocol:'TCP',target:'control-01 / 192.0.2.10',owner:'Mold 관리',protected:true},{id:2,name:'Kubernetes SSH',ip:'203.0.113.10',publicPorts:'2201 – 2201',privatePorts:'22 – 22',protocol:'TCP',target:'control-01 / 192.0.2.10',owner:'Mold 관리',protected:true},{id:3,name:'app-web',ip:'203.0.113.12',publicPorts:'8080 – 8080',privatePorts:'80 – 80',protocol:'TCP',target:'worker-01 / 192.0.2.11',owner:'사용자 규칙',protected:false}]},
  loadbalancers:{kind:'loadbalancers',create:'lb-create',edit:'lb-edit',remove:'lb-delete',addLabel:'로드밸런서 추가',help:'사용자 규칙의 포트·알고리즘·대상 VM·세션·SSL을 설정합니다. Provider Service와 API 규칙은 소유권을 표시하고 보호합니다.',columns:[col('이름','name',150),col('공인 IP','ip',130),col('공인 / 게스트 포트','ports',140),col('알고리즘 / 프로토콜','algorithm',160),col('대상 VM','target',155),col('소유권','owner',185),actions],rows:[{id:1,name:'Kubernetes API',ip:'203.0.113.10',ports:'6443 / 6443',algorithm:'roundrobin / TCP',target:'control-01',owner:'Mold 관리',protected:true},{id:2,name:'app/web',ip:'203.0.113.11',ports:'80 / 30080',algorithm:'roundrobin / TCP',target:'worker-01, worker-02',owner:'Provider · Service UID',protected:true},{id:3,name:'app-web',ip:'203.0.113.12',ports:'443 / 8443',algorithm:'roundrobin / SSL',target:'worker-01, worker-02',owner:'사용자 규칙',protected:false}]}
}
