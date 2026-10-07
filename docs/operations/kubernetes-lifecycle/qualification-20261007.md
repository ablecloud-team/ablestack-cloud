<!-- Licensed to the Apache Software Foundation (ASF) under one
     or more contributor license agreements. See the NOTICE file
     distributed with this work for additional information
     regarding copyright ownership. The ASF licenses this file
     to you under the Apache License, Version 2.0 (the
     "License"); you may not use this file except in compliance
     with the License. You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

     Unless required by applicable law or agreed to in writing,
     software distributed under the License is distributed on an
     "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
     KIND, either express or implied. See the License for the
     specific language governing permissions and limitations
     under the License. -->

# 31번 GFS2 생명주기 검증 및 인계 — 2026-10-07

이 결과는 Europa, amd64, 지정 CKS prepared template 및 `Primary` SharedMountPoint/GFS2 조합입니다. 실제 버전·artifact·native 데이터와 실패/복구를 구분하며, 모든 환경에 대한 지원 선언은 아닙니다. [시험 #1230](https://github.com/ablecloud-team/ablestack-cloud/issues/1230)의 단계 댓글과 [Epic #1227](https://github.com/ablecloud-team/ablestack-cloud/issues/1227)을 실행 증거의 공개 색인으로 사용합니다.

## 산출물과 검증 범위

| 구분 | 기준과 실제 확인 |
| --- | --- |
| Cloud consumer | 실환경 소비 코드 `27304f33ae36cbab433569f1ddd244895ad60b6d`; 변경 CKS 모듈481 tests/package PASS, Python78 PASS, server 모듈139 PASS 및 기존 enable3 분기 PASS |
| 실행 JAR | SHA256 `74d9335bc8f311f7799a66a198e30273f403ac2673352be189281bb3b30fe321`, 변경 class/script만 검증된 원본 JAR에 overlay, 나머지 entry 내용 보존 |
| UI/외부 CKS conf | 검증된 UI `e951365358`; focused21 tests/3 suites·lint/build PASS, 834 static hash, WEB-INF/config/index 보존, client200, 외부 CKS conf4 MATCH |
| ISO producer | `31ebce5255c61e78a999019362b38f3203cac7e7`; 전용 Origin [Actions 37579468037](https://github.com/dhslove/ablestack-kubernetes-iso/actions/runs/37579468037)에서 base/CSI 12 ISO의 build·독립검증·trial publish PASS |
| Provider / SDK | 공개 후보 Provider `95e66eaff4d17f0e072f019e9c82c196ec10980e`, SDK `25574a288d86e638c058721c919bb43e33c4485f`; Mold SHA256/실제 권한·Service 경로 검증 |
| AutoScaler | `2132862adc757e4547033e36e0502ca00f3d1762`; minor별 build와 실제 Pending→Cloud→native 확장/축소 검증 |
| CSI | `f24ff0a1d3dfef949fc97c7f97058abf9035cdd3`; 최신 CSI 배치 정책과 recipe를 반영한 fresh 1.36.5→1.37.1 DEV 및 controller fallback/동일 DATA 검증 |

처음 여섯 버전 clean 배포는 이전 `b46a5885` 산출물을 사용했습니다. 후속 component 고정 payload 및 CSI profile 개정은 새 revision으로 등록해 별도 disposable 클러스터에서 확인했습니다. 12개 최신 ISO의 build/파일 검증 완료를 12개 모두 최신 backend로 fresh boot 시험했다는 뜻으로 확대하지 않습니다. 최신 base payload가 같은 부분과 CSI 변경 부분을 분리하며, 기존 등록 항목을 덮어쓰지 않습니다.

## 버전별 신규 배포

| Kubernetes | 독립 신규 배포 | 실제 앱/데이터·접근 | AutoScaler/확장 | 판정 경계 |
| --- | --- | --- | --- | --- |
| 1.34.2 | r10, GFS2 control1/worker2 | DNS/HTTP, 앱65·DB/파일·독립 복원, 다운로드/Headlamp | minor1.34 native 확대/축소 | 이후 업그레이드한 장기 r10의 현재 버전과 최초 배포 버전을 구분 |
| 1.34.9 | r11, 같은 배치 | 앱/독립 복원, 한국어 Headlamp, 실제 키 A→B/원래 복원·폐기 키 거부 | native 확장/축소/PDB | original VM/Node UID 보존 장기 관측 |
| 1.34.12 | r12, 같은 배치; disposable CSI/프로젝트 별도 | 앱/독립 복원·AOF 실제 재시작·checksum | native AS; 프로젝트 역할/전체 quota 별도 | 대표 장기관측 종료 PASS(사용자 현재 시점 기준) |
| 1.35.9 | r13 및 disposable CSI/VPC/CNI | 앱/복원; project VPC basic LB100, offline native DNS/Pod HTTP | native AS1.35, 사용자 CNI2→3→2 | 대표 장기관측 종료 PASS(사용자 현재 시점 기준) |
| 1.36.5 | r14 및 최신 CSI fresh r32 | 앱/복원; CSI provision/resize/reattach/snapshot/Retain·Delete | native AS1.36; project role quotas | 대표 장기관측 종료 PASS(사용자 현재 시점 기준) |
| 1.37.1 | r15 및 CSI r25/r27/r32 | 앱/복원, CSI DATA·최종 물리 Delete | native AS1.37/실행 중 controller restart·UI 복구 | Mold AutoScaler 프로덕션 판정/실환경 PASS; 고정 개발 commit 출처 보존 |

장기 r10–r15는 총18개의 원래 VM/Node UID 및 Ready를 유지했습니다. 잔여 작업은 별도 클러스터를 사용하며 장기 대상의 삭제·키 회전·장애 주입으로 대신하지 않았습니다. 정상 구간과 계획 중단, 원래 실패와 수정 후 복구의 집계를 합치지 않습니다.

## 확대 시험과 수정 완료 범위

| 시나리오 | 실제 증거와 이슈 |
| --- | --- |
| patch chain/직접 patch·인접 minor | 1.34.2→1.34.9→1.34.12, 별도 direct1.34.2→1.34.12 및 1.34.12→1.35.9→1.36.5→1.37.1; 동일 artifact no-op/skip-minor·downgrade 거부, [#1266](https://github.com/ablecloud-team/ablestack-cloud/issues/1266), [#1297](https://github.com/ablecloud-team/ablestack-cloud/issues/1297) |
| 부분 실패/실제 management restart | FAILED 취소의 command/instance 일치 복구, target pin 유지/다른 target·scale 거부, 동일 ISO 재개 후 native Ready/데이터, [#1317](https://github.com/ablecloud-team/ablestack-cloud/issues/1317), [#1318](https://github.com/ablecloud-team/ablestack-cloud/issues/1318) |
| cordon/CSI controller | operator control cordon 유지 및 worker fallback, 새 CSI r32 clean36→37 DEV·same VM/Node UID·volume handle/checksum, [#1313](https://github.com/ablecloud-team/ablestack-cloud/issues/1313), [#1321](https://github.com/ablecloud-team/ablestack-cloud/issues/1321) |
| 인터넷 차단/CNI | 새 r40 기본 CNI 및 r41 사용자 Calico3.33 MTU1450의 인터넷 egress 차단 clean35→36/native Ready·MTU·DNS·cross-Pod HTTP PASS; r36 기존 실패 복구도 별도 기록. 정상 Delete 후 ROOT 각3의3호스트 파일/도메인 부재 확인, [#1323](https://github.com/ablecloud-team/ablestack-cloud/issues/1323), [#1326](https://github.com/ablecloud-team/ablestack-cloud/issues/1326), [#1327](https://github.com/ablecloud-team/ablestack-cloud/issues/1327) |
| HA/external-etcd/인증서 | HA r17 API·quorum/worker lease·데이터, external-etcd r18; 실제 etcd snapshot 복원 및 CA 갱신→Mold 구성 검증, [#1294](https://github.com/ablecloud-team/ablestack-cloud/issues/1294), [#1315](https://github.com/ablecloud-team/ablestack-cloud/issues/1315) |
| 외부 VM·ExternalManaged | cloud-init error 거부, prepared VM native join/Ready, PDB/drain 실패 보존·반복 제거; 기본 unregister VM/ROOT 보존 및 별도 r33 explicit cleanup/expunge 물리 소멸, [#1311](https://github.com/ablecloud-team/ablestack-cloud/issues/1311), [#1314](https://github.com/ablecloud-team/ablestack-cloud/issues/1314) |
| 프로젝트/권한/키 | 외부 IAM 의존 없는 로컬 machine account/Regular membership, SHA256200/SHA1401/foreign project·VM·volume·관리 사용자 거부; r35 최종 scoped key200→401/soft removed와 다른 VPC IP generation·manual ACL 보존, [#1296](https://github.com/ablecloud-team/ablestack-cloud/issues/1296), [#1287](https://github.com/ablecloud-team/ablestack-cloud/issues/1287) |
| quota/역할 offering/실제 AS | 전체 요구량 사전 거부·부분 설치 정상 Delete, stopped role change/start 합계, minor별 Pending/2→3→2/max3·CPU16→22→16/메모리32768→45056→32768, pending Scaling 중 controller restart 및 한국어 UI size-only recovery, [#1302](https://github.com/ablecloud-team/ablestack-cloud/issues/1302), [#1312](https://github.com/ablecloud-team/ablestack-cloud/issues/1312), [#1319](https://github.com/ablecloud-team/ablestack-cloud/issues/1319) |
| VPC ACL/재배포 | ACL 누락/default-deny preflight 거부, 삭제된 역사 ACL 조회/과거 PF·FW 재할당 세대 보호, r26 Delete→같은 tier r35 fresh35/public LB→36 복구→정상 Delete/manual ACL2 UUID·정책/다른 IP generation 유지, [#1299](https://github.com/ablecloud-team/ablestack-cloud/issues/1299), [#1322](https://github.com/ablecloud-team/ablestack-cloud/issues/1322) |
| 동적 CSI·데이터 정책 | GFS2 provisioning/resize/다른 worker 재연결·snapshot actual restore, Retain 회수·API/storage 실패 시 노드 보존; r27/r32 전체 정상 Delete 뒤 ROOT3+DATA Expunged/removed·3호스트 GFS2 파일/도메인 부재, [#1283](https://github.com/ablecloud-team/ablestack-cloud/issues/1283), [#1284](https://github.com/ablecloud-team/ablestack-cloud/issues/1284), [#1285](https://github.com/ablecloud-team/ablestack-cloud/issues/1285) |
| 실패 생성 정리 | r30/r31 pre-API FAILED Bootstrap, r34 API 초기화 후 legacy Starting, r38 legacy Alert/0 VM, 새 r39 네트워크 시작 예외 Error/0 VM 정상 Delete; 상태/receipt 강제 변경 없음, [#1324](https://github.com/ablecloud-team/ablestack-cloud/issues/1324), [#1325](https://github.com/ablecloud-team/ablestack-cloud/issues/1325) |

| 추가 핵심 작업 | 실제 증거와 이슈 |
| --- | --- |
| strict GFS2 사전 점검 | 생성 UI의 노드/VR offering·tag·strictness 표시, 기본 VR 사전 거부, r42 control/worker/router의 실제 domain XML/findmnt GFS2 및 scale 신규 worker 물리 검증, [#1233](https://github.com/ablecloud-team/ablestack-cloud/issues/1233) |
| 로컬 계정 및 잔여 SG 정리 | enable 누락·false 실제 생성/키200→401 삭제, true 전달 unit 보존, origin 보호와 역사 orphan default SG의 root-admin 정상 API 정리, [#1231](https://github.com/ablecloud-team/ablestack-cloud/issues/1231), [#1328](https://github.com/ablecloud-team/ablestack-cloud/issues/1328). 외부 IAM true 통합은 별도 범위 |
| Headlamp/view·Service LB UI | 실제 view15분 토큰의 Pod200/Secret·DELETE403, 자연 expiry401·재로그인·정상 SA/binding 제거 후401; 격리/VPC Service UID+LB/IP 유지 affinity 변경·CIDR 변경·scale backend membership·Service 정상 Delete/API LB 보존·실 API19페이지 완전 조회, [#1239](https://github.com/ablecloud-team/ablestack-cloud/issues/1239), [#1241](https://github.com/ablecloud-team/ablestack-cloud/issues/1241) |
| 현재 boot 및 유지보수 | r41 정상 stop/start의 새 boot·동일 VM/Node UID와 native proxy/Calico/Lease gate; r42 실제 proxy Pod 누락/NodeReady=true 음성 및 정확한 원래 affinity/cordon 복구. 역사 VM event/EndpointSlice 관측·VR 실제 apply 시간축, 계획 유지보수 LB5993/0·NodePort/데이터 유지, [#1263](https://github.com/ablecloud-team/ablestack-cloud/issues/1263), [#1264](https://github.com/ablecloud-team/ablestack-cloud/issues/1264) |
| 관리 add-on 배치 | worker operator cordon 상태의 CCM/Headlamp Pending을 별도 [#1329](https://github.com/ablecloud-team/ablestack-cloud/issues/1329)로 추적. 검증된 ISO 소비/신규 설치/upgrade 및 정상 Alert scanner의 UID/resourceVersion 보호 정규화, 다중 dry-run JSON parser 구현. 실패/Alert 사전 거부와 구분한 r42 동일 artifact 새 job83242ce0… status1, native2 v1.37.1 DEV Ready/동일 VM·Node UID, operator worker cordon 유지·current-boot 게이트 및 operator uncordon 전 양방향 Pod HTTP/DNS PASS. 같은 node/VR ROOT3 실제 GFS2 유지 |

일부 준비 fixture 보정과 최초 실패는 기록을 유지합니다. 특히 r35 최초 `CNI_APPLY exit1`의 정확한 원인은 확정되지 않았고 새 진단·제한 재시도와 동일 artifact 복구를 검증했습니다. r36 최초 KUBEADM health-check 실패는 target pause cache가 아직 없는 worker에 Job이 배치되는 순서 문제였으며 모든 원래 노드 cache 준비를 먼저 하도록 수정했습니다. localhost/외부 HTTP 관측 위치와 fixture ImagePull 오류를 제품의 네트워크 PASS로 바꾸지 않습니다.

## 인계 자원과 최종 단계

프로젝트 r35 및 CSI r27/r32의 정상 Delete와 물리 정리는 완료했습니다. 인터넷 차단 fresh r40/r41의 native 검증 및 정상 물리 Delete도 완료했습니다. 별도 r42의 current-boot 게이트·worker cordon 보존 동일 artifact 업그레이드와 정상 Delete를 완료했습니다. cluster job8bff36a4… 및 owned network jobc36c687e… status1, ROOT3 Expunged/removed·3호스트 파일/원래 node+VR 도메인 부재입니다. 일반 시험 계정은 실제 사용량을 확인해 원래17개 quota 값을 모두 복원했습니다(임시 변경6종: VM28/volume32/CPU112/memory229376MiB/primary1200GiB/IP20). 프로젝트 quota·임시 project template 공유·사용 종료 role offerings 및 소유 빈 isolated network는 별도 정상 API 정리 기록을 따릅니다.

최종 API에는 장기6개 클러스터만 있으며 최초18 VM UUID 및 각 역사 native18 Node UID·Ready가 일치합니다. Retain DATA6개(전부 Ready/GFS2)와 데이터 checksum·회수 기록, 별도 NFS helper8개, 공유 project/offline VPC 및 사용자 manual ACL/다른 할당 세대 IP는 보존하는 자원입니다. 미생성 실패 allocation의 path=NULL/Destroy receipt1개는 정상 deleteVolume로 API 제거·removed 기록을 확인했고 Expunging metadata 상태를 별도 기록했습니다. 실제 할당 볼륨의 물리 Expunged PASS와 구분합니다. 프로젝트 machine identity는 기존 프로젝트를 유지하는 동안 보존하고 삭제한 각 cluster scoped key만 폐기합니다.

PR 준비 기준은 최신 HEAD 전체 License Check PASS/Conflict 없음입니다. 구현·실환경 집중 검증과 PR 준비까지 이 작업 단계이며 Upstream 병합/공식 component·ISO Release/전체 Cloud 통합 build는 후속 Release 단계입니다. 2026-10-07 사용자 지시로 현재 누적 구간에서 관측·최종 판정을 완료했습니다. 실제20~22시간의 수치를 기록하고 literal24시간 완료로 표시하지 않습니다. 실제 발견 자료 중 공개 불가 자료는 공개 문서/PR에 포함하지 않습니다.

## 유지보수 시간과 증거 한계

동일 router192의 실제 UTC journal에서 계획 반복의 HAProxy 적용07:59:59/08:04:26을 확인했습니다. worker194 STOP 시작→완료08:01:32→08:01:36, START08:03:00→08:03:10, 새 Calico/proxy gate08:04:09.375, Provider API backend 복귀08:04:24.496입니다. 시작 요청 기준 CNI69.4초/API backend84.5초/VR86초를 관측했으며 VM Running만을 서비스 복구로 판정하지 않습니다. 두 웹 replica/PDB minAvailable1/남은 capacity 조건의600.015초·5993요청 오류0 결과와 원래 drain 없는226오류를 구분합니다. 단일 Redis replica의 HA/무중단이나 고정 RTO 보장은 검증하지 않았습니다.

역사 Lease/EndpointSlice의 정확한 전이 시각은 수집하지 못했습니다. 실제 준비/UID 관측과 현재 boot Lease gate를 구분하며, service unit의 kubelet/containerd 순서 정보만으로 guest shutdown 결함을 확정하지 않습니다. 준비 계약은 VM power, native Node/현재 boot CNI·proxy·Lease, 사용자 workload/실제 DNS·HTTP 데이터 경로로 나눕니다. 지원 Calico 프로파일을 벗어나는 사용자 CNI는 자동 게이트의 별도 지원 조건을 확인해야 합니다.

## 이슈 중복 방지

상위 #1230의 하위74건을 번호/제목/상태/갱신 시각으로 대조했습니다. 기존 #1313(cordon ownership), #1321(CSI affinity), #1247(최초 Provider 순서)과 다른 CCM·Headlamp placement 계약만 #1329로 만들고 #1230에 한 번 연결했습니다. #1231/#1233/#1239/#1241/#1263/#1264/#1317/#1328의 결과는 해당 이슈에 갱신하며 새로운 대체 이슈를 만들지 않습니다. 구현·실환경 검증·PR 준비·통합 대기의 상태를 구분하고 PR 병합 전에 하위 이슈를 일괄 종료하지 않습니다. #1264의 마지막 전체 버전 확대 회귀 조건은 대표 현재 코드 실행과 최신12개 ISO 파일검증을 완료한 범위와 구분해 체크하지 않았습니다. 사용자 기준의 전체 통합 빌드·공식 Release regression에서 확인합니다.

## RT12 최종 관측·종료 판정 — 2026-10-07 20:50 KST

사용자의 “현재 시점이면 충분하므로 지금 관측하고 판정” 지시에 따라 종료 기준을 변경했습니다. **누적 관측과 현재 실환경 재검증 PASS**입니다. 실제 관측20시간51분~22시간38분을 그대로 기록하며, literal24시간 완료로 표시하지 않습니다. 24시간 도달 대기는 더 이상 이번 시험의 잔여 조건이 아닙니다.

| Kubernetes | 실제 누적 관측 | 샘플 | 앱 HTTP 요청 | 오류 | 판정 |
| --- | --- | ---: | ---: | ---: | --- |
| v1.34.12 | 22시간 38분 1초 | 1,359 | 135,900 | 0 | PASS(사용자 종료 기준) |
| v1.35.9 | 21시간 31분 1초 | 1,292 | 129,200 | 0 | PASS(사용자 종료 기준) |
| v1.36.5 | 20시간 51분 1초 | 1,252 | 125,200 | 0 | PASS(사용자 종료 기준) |

총390,300회의 앱 API 읽기 HTTP는 오류0건, 매회65/65 검사·DB100 records·64files(4MiB) checksum 유지 및3Node Ready입니다. 샘플 간격 최대90초 미만을 확인했습니다. 60초 간격 표본이며 외부 LB의 연속24시간 무중단으로 확대하지 않습니다.

현재 각 버전에서 기존 데이터를 다시 쓰지 않고65/65·DB/파일 checksum을 확인했고, 앱 DNS3종·외부 Service LB100회/오류0·예상 fixture 응답, CCM/AutoScaler/Headlamp Ready·fresh Node Lease·원래 LB rule count를 확인했습니다. 이전 system checkpoint 이후 같은 Pod UID와 추가 restart0입니다. 최근 controller 로그는 private으로 보존했고 마지막10분/최대1000줄에서 fatal/auth/error severity marker0을 확인했습니다. CCM은 해당 구간 새 로그가 없으므로 로그0건을 새 API 호출 성공의 증거로 계산하지 않습니다. 실제 minor별 AutoScaler·키 회전·Provider 동작은 앞선 시험 증거를 재사용합니다.

최초 관측 보조 코드의 LB fixture namespace와 Lease 시간 허용차를 확인해 **현재 리소스와 기존 제품 freshness 계약(과거90초/미래30초)**에 맞춰 재검증했습니다. 최초 엄격 Lease assertion의 값은 보존되지 않아 clock skew 원인으로 단정하지 않습니다. 제품 패치나 신규 중복 이슈를 만들지 않았습니다.

원래 장기 클러스터6개·최초18 VM UUID 및 역사18 native Node UID가 그대로 Running/Ready입니다. 작업 소유권과 `/proc` command/cwd가 일치하는 관측 수집기3개만 종료하고, 클러스터/앱/VM/스토리지/키는 변경하지 않았습니다. frozen 샘플과 현재 결과를 보존했습니다.

**실배포·생명주기 시험의 장기관측 단계는 완료**입니다. 남은 단계는 사용자 지정 Upstream PR 통합·공식 component/ISO Release·전체 Cloud Release build/통합 검사입니다. 기존 #1230 / Epic #1227의 진행 상태를 갱신하며 새 RT12/패치 이슈를 만들지 않습니다.


Retain volume UUID는 `90fbba30-99bf-4405-89e1-66980ae8d13d`, `f8a4f70a-40b2-4dcf-8bde-7f57ffff6fdf`, `bf4e0635-c63a-4cb4-9b10-81f3bca0f7f7`, `d1814d1b-a467-45de-b43e-5ef9ee335950`, `c95e29a6-1fd0-49df-b9c0-ae6e7f745fce`, `837700f0-bc46-47ed-858f-2c320ca39a9a`입니다. 외부 helper·Retain 데이터의 회수는 기존 checksum/인계 기록을 사용하고 임의 정리를 하지 않습니다.


## 1.37.1 AutoScaler Mold 프로덕션 판정 — 2026-10-07

사용자가 기존 실환경 검증에 근거해1.37.1 AutoScaler를 Mold 내 프로덕션 레벨로 판정했습니다. [판정과 정확한 source/image](https://github.com/ablecloud-team/ablestack-cloud/issues/1228#issuecomment-6038989411)를 ISO 두 프로파일의 production/PASS qualification에 반영합니다. 외부 원본 commit의 development-candidate provenance를 보존하되, 이를 Mold 내 개발 전용 또는 AutoScaler만의 공식 게시 금지 판정으로 사용하지 않습니다. 기록에 남은 DEV는 당시 ISO/클러스터 식별 이름과 역사적 시험 구분입니다. 전체 lifecycle/CSI qualification·공식 source/SDK 승격·immutable tag 검사는 각각 유지합니다. 실제 바이너리/커스터마이징과 장기 클러스터는 변경하지 않았습니다.
