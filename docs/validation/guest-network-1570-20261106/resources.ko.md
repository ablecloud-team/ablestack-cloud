<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 -->

# 확인용 시험 자원

각 클러스터에 격리 네트워크 4개와 연결 VM 4개를 유지한다. VPC·오퍼링·전용 소유자·도메인은 해당 시험망의 의존 자원이다.

## 31번 GFS2

| 망 | 네트워크 | 연결 VM |
| --- | --- | --- |
| NAT | [qa-gn1570-31-nat](http://10.10.31.10:8080/client/#/guestnetwork/caea7915-8bed-4265-94e5-22748cbee88e?tab=virtual.routers) | [qa-gn1570-31-nat-vm](http://10.10.31.10:8080/client/#/vm/0f2ccdef-031c-41d1-b993-fb6edfdb1d39?tab=nics) |
| no-SourceNat | [qa-gn1570-31-nosnat](http://10.10.31.10:8080/client/#/guestnetwork/eac5e03a-07c9-4d23-99ec-0f74d5c5349f?tab=guest.ip.range) | [qa-gn1570-31-nosnat-vm](http://10.10.31.10:8080/client/#/vm/dddeac1b-95e8-46a0-960f-b969f900f02f?tab=nics) |
| Static Routed | [qa-gn1570-31-static](http://10.10.31.10:8080/client/#/guestnetwork/0fa185ab-1fc1-4e89-bd23-eec3e97269bb?tab=routing.firewall) | [qa-gn1570-31-static-vm](http://10.10.31.10:8080/client/#/vm/4fc03925-e497-40f6-9b76-51ef31ab7d2d?tab=nics) |
| VPC tier | [qa-gn1570-31-vpc](http://10.10.31.10:8080/client/#/guestnetwork/b51cf142-4a0d-4131-b690-28f04704552c?tab=details) | [qa-gn1570-31-vpc-vm](http://10.10.31.10:8080/client/#/vm/19e6e147-217c-4f6a-b8f8-8a96ea83bcdb?tab=nics) |

## 32번 Glue RBD

| 망 | 네트워크 | 연결 VM |
| --- | --- | --- |
| NAT | [qa-gn1570-32-nat](http://10.10.32.10:8080/client/#/guestnetwork/9b477162-d921-4015-9f76-16475a067428?tab=virtual.routers) | [qa-gn1570-32-nat-vm](http://10.10.32.10:8080/client/#/vm/cc345380-a1c3-437f-ac24-5a2d4a313f6a?tab=nics) |
| no-SourceNat | [qa-gn1570-32-nosnat](http://10.10.32.10:8080/client/#/guestnetwork/a96e6e1e-315a-4cf3-8e2a-01421a5e302e?tab=guest.ip.range) | [qa-gn1570-32-nosnat-vm](http://10.10.32.10:8080/client/#/vm/5786130a-646d-4268-b58e-75ed8296f3f8?tab=nics) |
| Static Routed | [qa-gn1570-32-static](http://10.10.32.10:8080/client/#/guestnetwork/9c753cf2-e3e5-4b18-8a9b-42547bf22270?tab=routing.firewall) | [qa-gn1570-32-static-vm](http://10.10.32.10:8080/client/#/vm/93d31a8a-d118-4b83-b9cf-58a014ee1137?tab=nics) |
| VPC tier | [qa-gn1570-32-vpc](http://10.10.32.10:8080/client/#/guestnetwork/3f6c1cf3-c33f-41b4-bb5c-6b2149b7e1db?tab=details) | [qa-gn1570-32-vpc-vm](http://10.10.32.10:8080/client/#/vm/d264f90f-d521-4199-ae43-30f37d44e9ad?tab=nics) |
