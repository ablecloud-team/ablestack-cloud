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

# 인스턴스/볼륨 가져오기 통합 설계와 검증 계획

통합 이슈 [#2012](https://github.com/ablecloud-team/ablestack-cloud/issues/2012). 기준은 Europa `65c1225710f`와 [KRBD 이관 PR #2011](https://github.com/ablecloud-team/ablestack-cloud/pull/2011)이다. 이 문서는 구현과 인수 테스트에 사용할 계약을 제안한다.

## 적용 범위와 작업 순서

| 순서 | 변경 대상 | 구현 내용 |
| --- | --- | --- |
| 1 | KVM Agent 및 서버 | 파일과 Ceph 후보를 조사하고 API·등록·디스크 연결의 형식과 저장소 식별을 맞춘다. |
| 2 | Mold 라우트와 UI | 도구 메뉴를 **인스턴스/볼륨 가져오기**로 통합하고 원본별 마법사, 데이터 볼륨 목록, 인스턴스 관리 해제, 이관 작업을 배치한다. |
| 3 | 이관 소비자와 검증 | V2K·N2K의 같은 API 소비와 실제 ROOT/DATA 실행을 확인한다. 31번 파일형과 32번 RBD 시험을 증거로 기록한다. |

현재 `ui/src/config/section/tools.js`의 `manageinstances`와 `managevolumes`가 분리되어 있다. `ManageInstances.vue`, `ImportUnmanagedInstance.vue`와 `ManageVolumes.vue`가 각 화면을 구성한다. `VolumeImportUnmanageManagerImpl`은 조회와 등록을 KVM의 `LibvirtGetVolumesOnStorageCommandWrapper`를 통해 수행한다. Agent는 QCOW2/RAW를 조사하고 RBD 풀에서는 RBD 경로를 구성한다. 응답은 `storagepooltype`, `format`, `size`, `virtualsize`를 이미 보낸다. 실제 3종 이미지의 등록·부팅·관리 해제는 아래 시험에서 확인한다.

## UI

- **인스턴스 가져오기** 탭은 VMware(V2K)·Nutanix(N2K) 이관과 Mold 비관리 VM·원격 KVM·로컬 QCOW/RAW·공유 QCOW/RAW 등록으로 진입한다. 원본, 대상, 원본 VM 또는 디스크 정보, 인스턴스 설정, 검토·실행 순으로 진행한다. 검토에는 실제 검사 형식, 디스크 용량·대상 풀, NIC 매핑을 표시한다. 확장자로 형식 후보를 안내하되 서버 관측을 최종 결과로 사용한다.
- VMware 비관리 VM 등록 진입점과 선택 옵션을 UI에서 제거한다. 기존 URL, 책갈피 및 직접 진입으로 해당 폼이 열리지 않는지 확인한다. VMware 이관과 기존 비관리 VM API 호출을 별도로 회귀 검증한다.
- **데이터 볼륨 가져오기** 탭은 스토리지 범위 → Zone → 범위별 Pod/클러스터/호스트 → 풀 순으로 구성한다. 같은 영역에서 비관리·관리 목록을 전환한다. 상위 값이 바뀌면 하위 선택과 검색·행 선택을 초기화하고, 이전 요청이 늦게 완료되어도 새 목록에 섞이지 않게 한다.
- 비관리 목록에는 이름, 원본 유형, 할당/가상 크기 및 조회 가능한 상태를 표시한다. RBD의 할당량을 확인할 수 없으면 미제공으로 표시한다. Ceph 논리 크기를 물리 사용량으로 표시하지 않는다. 행 하나를 선택해 이름·디스크 오퍼링·소유자(현재 계정/계정/프로젝트)를 지정한다. API job 완료 후 같은 풀의 양쪽 목록을 갱신하며 중복 제출을 막는다.
- 관리 목록은 이름·상태·소유자·연결 VM을 표시한다. 사용 가능한 준비/미연결 KVM 볼륨만 관리 해제할 수 있다. 작업 확인 시 원본과 풀을 보여준다. 목록은 Mold 테이블 스타일, 한 줄 말줄임과 전체 값 툴팁, 검색 및 빈 상태와 고정 하단을 사용한다.
- **인스턴스 관리 해제** 탭에서는 VM 선택과 영향 범위를 확인한다. **이관 작업**은 진행률, 작업 이력, 상태별 전환·재개·재시도·취소·기록/자격 증명 관리를 제공한다. 브라우저 새로고침 후에도 서버에서 작업 상태를 조회한다.
- `manageinstances`와 `managevolumes` 기존 URL은 각각 통합 화면의 대응 탭으로 연결한다. 독립 권한의 합집합으로 메뉴 진입 가능 여부를 정하고 각 탭·액션에서 기존 권한을 재확인한다. 제한된 역할에는 사용 가능한 탭만 표시한다.

## 데이터 식별·등록 계약

| 원본 | UI 표시 | API `format` 및 런타임 | 조회·등록 기준 |
| --- | --- | --- | --- |
| 파일 QCOW2 | QCOW2 | QCOW2 | 등록 파일 풀의 경로와 실제 헤더, 읽기 가능, 지원되는 독립 이미지 |
| 파일 RAW | RAW | RAW | 등록 파일 풀의 경로·크기와 RAW 조사 결과, 정상 읽기 |
| Ceph 블록 이미지 | RBD | RAW | 등록된 RBD 풀의 image ID·크기, 이미지 접근 상태와 pool/namespace 일치 |

기존 `listVolumesForImport`의 `storagepooltype + format`으로 UI 원본 유형을 구한다. RBD는 저장소 유형을 기준으로 표기하고, DB와 QEMU에서 쓰는 디스크 형식은 RAW로 유지한다. 파일명 확장자는 후보 안내에 쓴다. 기존 응답 필드의 의미, 단위와 V2K·N2K 소비 계약을 유지하며, 필요한 추가 관측 값은 호환성 시험을 거친다.

1. 후보 조회와 등록 모두 서버가 풀과 적격 호스트를 확인한다. 파일은 해당 풀에서 정규화한 경로를 사용한다. RBD는 등록 풀과 현재 Ceph 이미지 식별자를 확인한다. 정규화 경로·Ceph ID·크기 등 확인한 식별 정보가 등록 시 달라지면 새 조회를 요구한다. 풀 범위 밖 참조 또는 직접 지정한 Ceph 인증 정보는 입력으로 받지 않는다.
2. 등록 직전에 관리·템플릿·스냅샷 참조, 원본 잠금/사용 상태, backing chain·암호화·RBD 의존 상태, 오퍼링·소유권·할당량을 확인한다. 원본 조사 오류와 실제 빈 목록을 상태와 메시지로 구분한다. 크기 계측이 불가하면 등록 가능 여부와 값을 구분한다.
3. 기존 `importVolume(storageid, path, ...)`로 같은 풀의 DATA 볼륨을 채택한다. 풀 ID와 정규화된 원본에 대한 등록 경쟁을 직렬화하고, 등록과 용량·이벤트 반영의 경계에서 실패 후 재시도 결과를 확인한다. 현재 Agent/KVM 경로를 재사용한다. 별도 복제/변환은 기존 명시적 이관·할당 API 경로의 사례로 구분한다.
4. `unmanageVolume`은 분리되고 준비 상태인 KVM DATA 볼륨에 적용한다. 관리 해제 후 파일 또는 Ceph image ID와 데이터가 유지되고, 작업 상태에서 같은 원본을 다시 조회하여 등록할 수 있어야 한다. 등록 실패·취소·재개 시 작업 전용 자원만 정리한다.
5. qemu tools는 기존 V2K·N2K의 `listVolumesForImport`–`importVolume` 인계와 KRBD 실행을 담당한다. 독립 볼륨 등록은 KVM Agent가 처리한다. 이 계약이 기존 응답을 그대로 쓰면 API 명령이나 SDK 변경 사항은 없다. 계약 검증에서 소비 코드 수정이 확인되면 해당 저장소를 이슈에 연결한다.

## 인수 테스트

| ID | 환경과 입력 | 수행 및 통과 기준 |
| --- | --- | --- |
| UI-01 | 31·32, 라이트/다크, 일반/좁은 폭 | 새 메뉴·탭·브레드크럼과 양쪽 이전 URL, 탭별 권한, 키보드/검색·빈 목록·툴팁·페이지네이션을 확인한다. 탭·풀 전환 1회에 조회가 중복 실행되거나 이전 풀 응답으로 덮이지 않는다. |
| UI-02 | 31·32, 관리자와 권한 제한 계정 | 소유자 기본값과 명시적 계정/프로젝트, 오퍼링, 필수 입력과 job 결과를 확인한다. 금지된 탭/액션/API 요청의 권한 동작과 메시지가 일치한다. |
| DV-01 | 31, GFS2 및 로컬 풀 × QCOW2/RAW | 사전 guest 데이터 해시와 용량을 확보하고 비관리 조회 → 등록 → DATA 연결 → 읽기/쓰기·재연결 → 관리 해제 → 재등록을 시험한다. API/스토리지/게스트의 포맷·경로·크기·해시가 일치하고 원본이 유지된다. |
| DV-02 | 32, 등록된 Ceph RBD 풀 | 원본 image ID·논리 크기를 확보한 뒤 DV-01 흐름을 수행한다. UI RBD, API RAW, 실제 XML과 KRBD 매핑의 pool/image를 대조한다. 다른 적격 호스트에서 재기동해 image ID와 테스트 바이트를 확인하고 분리·정리한다. |
| DV-03 | 31·32, 과부하·오류 및 충돌 | 크기/오퍼링·쿼터·권한 위반, 사용 중/연결, 동일 이름 원본 교체, 템플릿·스냅샷, 손상/미지원 파일, 풀 비정상/Agent 실패를 재현한다. 실패 사유, 기존 원본·용량·사용량 보존, 중복 요청/재시도 후 1개 등록을 확인한다. |
| VM-01 | 31, 로컬·공유 × QCOW2/RAW | 네 조합에서 검사 정보, 단계 전환, 루트 볼륨·NIC, 부팅과 게스트 데이터, 취소·오류 시 원본 보존을 확인한다. |
| VM-02 | 등록된 KVM 인프라, 31·32 가능한 조합 | Mold 비관리 VM과 원격 KVM의 목록·권한·디스크/NIC 매핑·가져오기·부팅, VM 관리 해제를 검증한다. VMware 비관리 등록 UI 경로는 제거되고 기존 API는 정상 응답한다. |
| MIG-01 | 31 파일형·32 RBD, 실제 V2K·N2K 원본 | 소스별 한 단계 이관, 2단계 전환, ROOT/DATA와 NIC·펌웨어를 확인한다. Linux와 Windows의 기동 및 생성된 볼륨을 대조한다. 32에서는 KRBD 이미지 ID·호스트 재기동을 확인한다. |
| MIG-02 | 기존 작업과 단계 실패, 31·32 | 작업 조회·재개·재시도·취소·정리, 브라우저 재접속 후 job 상태, 인계 전후 오류에서 중복 VM/볼륨·원본 변경이 없음을 검증한다. |

단위 테스트는 `ImportVolumeCmdTest`, `VolumeImportUnmanageManagerImplTest`, `LibvirtGetVolumesOnStorageCommandWrapperTest`에 확인된 새 분기 및 호환성을 추가한다. UI는 목록 요청 순서, 권한, 라우트와 마법사 동작을 브라우저에서 확인한다. 데이터 경로의 성공은 job 완료, 원본 식별, 게스트 데이터가 모두 일치할 때 기록한다. 실패 및 사용 불가 환경은 사유를 기록한다.

Cloud 구현 시 WSL ext4에서 변경 Maven 모듈 및 UI만 빌드한다. qemu 변경이 필요한 경우 대응 GitHub Actions 패키지를 사용한다. 양쪽 커밋·패키지 해시와 배포된 UI/API/Agent/호스트 버전을 기록한 후 31·32를 검증한다. 필요 시 13번에서 기존 인스턴스 흐름을 확인한다. 격리된 테스트 VM·볼륨·이미지로 증거를 모으고 ID·잡·경로에 대조해 그 자원만 정리한다.
