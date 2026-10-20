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

# Epic #898 구현 준비 및 13번 클러스터 기준선

기준 일시: 2026-10-06, Asia/Seoul. 이 문서는 기능 구현 착수 전 준비 기록이다.
이슈 상태는 GitHub의 Epic 본문 및 각 이슈를 직접 조회한 결과이며, 배포 확인은
기존 서비스의 읽기 점검과 최신 모듈 반영 범위에 한정한다. 전체 Epic 기능 검증은 별도다.

## 소스 및 브랜치

- upstream: `ablecloud-team/ablestack-cloud`, base `ablestack-europa`.
- origin: `dhslove/ablestack-cloud`.
- 구현 기준 SHA: `73dbd8258f762e454f8b8fcb3b14436b5540117b`.
- upstream → WSL local base fast-forward → origin base fast-forward 완료.
- WSL local / origin / upstream 및 Windows local base SHA 일치, divergence `0 0`.
- Epic 브랜치: `epic/898-sharedfs`, origin에 생성.
- 실제 개발/빌드 경로: `/root/work/ablestack-cloud`.
- Windows 기존 작업 브랜치의 수정·미추적 파일은 보존한다. Windows base ref만 동기화했다.
- 후속 구현은 `sharedfs/issue-<번호>-<기능>`으로 최신 local base에서 분기하고
  upstream `ablestack-europa`에 이슈별 PR을 제출한다. Epic 브랜치는 준비 문서와
  통합 검증 기준을 유지하며, 기능 구현 커밋을 한꺼번에 병합하지 않는다.
- 각 후속 작업 전 fetch, local base 동기화 및 `0 0` 검증을 반복한다.

## 전체 이슈와 순서

[Epic #898](https://github.com/ablecloud-team/ablestack-cloud/issues/898)의
하위 대상은 27개다. 직접 조회 결과 #911만 CLOSED이고 나머지 26개는 OPEN이다.
#925는 #911 구현 PR이며 하위 기능 이슈에 중복 집계하지 않는다.
Epic 참조 검색으로 확인한 #926(CI 정비)과 #997(Europa Network 통합)은 관련 참조이며
이 Epic의 27개 하위 개발 대상에 포함하지 않는다. GitHub native sub-issues 응답은 비어
있어 Epic 본문의 목록과 의존 관계를 기준으로 대조했다.

| 순서 | 기능 이슈 | Epic 상태 | 선행 조건·완료 게이트 |
|---:|---|---|---|
| 1 | #911 운영 중 Storage Service 런타임 코드 인플레이스 업그레이드 | **DONE** | PR #925 병합, UI E2E·재부팅·4개 프로토콜 I/O·최신 전체 릴리즈 검증 완료 |
| 2 | #924 Storage Service 런타임 번들 카탈로그 및 수명주기 관리 | **NEXT** | #911 서명·전송·활성화 계약, 관리자 카탈로그·상태 전이·권한·UI 검증 |
| 3 | #892 설정 변경 실패 시 원자적 롤백과 상태 복구 | WAITING | #911의 runtime 전환과 desired-state rollback 책임 분리 |
| 4 | #897 장시간·고메모리 설정 변경 사전 점검과 장애 격리 | WAITING | #892 공통 revision·operation 상태 모델 |
| 5 | #974 대용량 백킹 볼륨 초기 포맷 timeout 및 재개 가능한 준비 작업 | WAITING | #892 rollback, #897 operation deadline·heartbeat·resume 기반 |
| 6 | #913 L2 고정 IP 기본 게이트웨이 재부팅 지속성 및 DHCP 충돌 방지 | WAITING | #911 배포 경로, network-ready와 reconcile 순서 검증 |
| 7 | #914 다수 NFS Export 부팅 Reconcile 지연과 고정 Timeout 제거 | WAITING | #897 progress/deadline, #913 부팅 네트워크 순서 |
| 8 | #918 제한 NFS ACL visibility probe 실패의 Ganesha 전체 중지 방지 | WAITING | #892 rollback, #914 readiness/probe 단계화 |
| 9 | #895 삭제 시 데이터 볼륨 보존 및 분리 정책 | WAITING | #892 rollback, #897 operation lock·progress |
| 10 | #909 구성 백업 번들 및 마지막 정상 구성 복원 | WAITING | #892, #897, #895 및 #911 runtime 버전 정보 |
| 11 | #920 운영 서비스의 SystemVM 템플릿 교체 및 설정 자동 복구 | WAITING | #911 책임 분리, #913·#914·#918 부팅 복구, #895·#909 데이터/LKG 기반 |
| 12 | #900 SMB 다중 엔드포인트 유실 방지 | WAITING | 공통 rollback·operation 기반, 다중 listener runtime 검증 |
| 13 | #896 컴퓨트 오퍼링 제약 안내와 선택 UX | WAITING | 공통 offering validator 및 capability API |
| 14 | #891 온라인 스케일업 가능한 오퍼링 사용 강제 | WAITING | #896 validator·사용자 안내, #897 preflight |
| 15 | #904 기존 볼륨 초기 생성 경로의 오퍼링 의존성 제거 | WAITING | #895 볼륨 수명주기, #896 offering 분기 |
| 16 | #905 모든 백킹 볼륨의 고유 합산 용량 표시 | WAITING | #895·#904의 고유 볼륨 참조 모델 |
| 17 | #894 NFS squash별 POSIX 권한 기본값 | WAITING | #892 권한 변경 rollback |
| 18 | #906 NFSv4 숫자 UID/GID 모드 | WAITING | #894 POSIX 정책과 service-level ID mapping 분리 |
| 19 | #903 NFS/SMB 교차 프로토콜 POSIX 속성 단일화 | WAITING | #894·#906의 권한 및 identity 정책 |
| 20 | #915 SMB 부모 UID/GID 상속 및 setgid 정책 | WAITING | #903 공통 path policy, #892 rollback |
| 21 | #916 공유 하위 디렉터리별 POSIX 소유권·ACL 정책 | WAITING | #903 공통 policy, #915 owner/setgid |
| 22 | #919 SMB 공유별 create/directory mask와 force mode | WAITING | #915·#916 권한 조합 및 reload 정책 |
| 23 | #910 동일 볼륨 하위 디렉터리 중첩 Export/Share | WAITING | #903·#916 공통 path·ownership 정책 |
| 24 | #908 SMB force user/group 고정 UID/GID | WAITING | #903·#906·#915의 인증 주체와 POSIX identity 분리 |
| 25 | #902 SMB AD 다중 호스트명·DNS 별칭 | WAITING | #900 다중 endpoint 수명주기 |
| 26 | #907 SMB 공유별 클라이언트 IP/CIDR 정책 | WAITING | #900 listener 모델, #903 공유 정책 모델 |
| 27 | #901 NFS 프로토콜·리스너 행 기반 표시 | WAITING | 최종 listener API 구조 확정 후 UI 반영 |

## 이슈별 구현·검증 범위

각 이슈의 상세 요구사항은 아래 링크의 원문을 기준으로 한다. OPEN 상태는
해당 기능의 미구현·미검증 전체를 의미하지 않으며, 현재 코드의 기반과 실제 완료
게이트를 후속 구현에서 다시 대조한다.

### [#911 [SharedFS][SystemVM] 운영 중 Storage Service 런타임 코드 인플레이스 업그레이드 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/911)

상태: CLOSED; 원문 갱신 시각: 2026-08-29T01:35:12Z.

**테스트 게이트**

- NFS/SMB/iSCSI/NVMe-oF가 동시에 활성화된 인스턴스에서 읽기·쓰기와 세션 유지 검증
- 프로토콜별 단독 인스턴스 검증
- 기존 템플릿 VM의 bootstrap 설치와 향후 템플릿 기본 updater 검증
- 잘못된 서명·해시·ABI·schema·경로·권한·용량 부족 거절 검증
- STAGING, ACTIVATING, RECONCILING 단계별 관리 서버·에이전트·System VM 장애 주입
- 업그레이드 중 동시 API 변경 차단 검증
- 성공 및 롤백 후 System VM 재부팅 복구 검증
- `serviceImpact=NONE`에서 기존 클라이언트 세션과 I/O 연속성 검증
- 이전 관리 서버·에이전트와 신규 번들의 호환성 거절 검증

**완료 조건**

- 허용 대상 런타임 코드가 System VM 중지·재생성·템플릿 교체 없이 갱신됩니다.
- 관리 서버, API와 UI에서 실제 적용 버전과 파일 해시를 확인할 수 있습니다.
- `serviceImpact=NONE` 업그레이드 중 기존 프로토콜 세션과 I/O가 유지됩니다.
- 실패 시 이전 런타임으로 자동 롤백되고 기존 desired state가 정상 복구됩니다.
- 업그레이드 성공·롤백 후 재부팅해도 선택된 런타임과 서비스 설정이 복구됩니다.
- 라이브 적용 범위를 벗어난 변경은 시작 전에 거절되고 템플릿 유지보수 경로로 안내됩니다.
- 릴리즈 런타임 번들과 신규 System VM 템플릿의 런타임 버전이 일치합니다.

### [#924 [SharedFS][Runtime] Storage Service 런타임 번들 카탈로그 및 수명주기 관리](https://github.com/ablecloud-team/ablestack-cloud/issues/924)

상태: OPEN; 원문 갱신 시각: 2026-08-29T00:00:25Z.

**테스트 게이트**

- Root Admin과 비관리자 메뉴/API 권한 분리
- 정상·변조·잘못된 키·잘못된 checksum 번들 등록 검증
- 동일 버전 멱등 등록과 동일 버전·다른 해시 거부
- 모든 상태 전이와 잘못된 전이 차단
- 사용 중 번들의 삭제 방지 및 사용 대상 조회
- ABI/schema/Manager/Agent/System VM 템플릿 호환성 필터링
- SharedFS 업그레이드 화면에 게시·호환 번들만 표시
- 폐기 번들의 신규 적용·롤백 차단
- 다크모드의 텍스트, 표 경계선, 상태 태그 및 대화상자 대비 검증
- Management Server 재시작 후 카탈로그와 적용 상태 정합성 유지

**완료 조건**

- Root Admin이 `도구 > Storage Service 런타임 번들`에서 번들을 등록·검증·게시·중지·폐기할 수 있습니다.
- 사용 중인 현재 버전과 향후 업데이트 버전, 적용 대상 및 호환성을 UI에서 확인할 수 있습니다.
- SharedFS 런타임 업그레이드 화면에는 승인되고 호환되는 번들만 표시됩니다.
- 사용 중 번들의 이력과 롤백 가능성이 임의 삭제로 손상되지 않습니다.
- API, DB, UI와 감사 이력의 번들 상태가 일치합니다.
- 라이트·다크 모드에서 목록·상세·대화상자를 UI로 검증합니다.

### [#892 [SharedFS] 설정 변경 실패 시 원자적 롤백과 상태 복구 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/892)

상태: OPEN; 원문 갱신 시각: 2026-08-17T22:12:49Z.

**구현 범위**

- API: 작업 revision, rollback 결과, 실패 단계 응답
- Backend/DB: operation 및 snapshot 영속화, 공통 트랜잭션 경계
- SystemVM: generation 기반 원자적 설정 교체와 이전 설정 보관
- UI: 적용 중, 롤백 중, 복구 완료, 수동 개입 필요 상태 구분
- Test: 네 프로토콜 create/update/delete 실패 주입 및 재시도 테스트

**완료 조건**

- 설정 적용 실패 후 DB와 런타임이 직전 Ready 설정과 일치합니다.
- 삭제 실패 시 삭제 대상 레코드가 유실되지 않습니다.
- 롤백 성공은 Error 상태로 남지 않습니다.
- 롤백 실패만 명시적인 복구 필요 상태와 진단 정보를 노출합니다.

### [#897 [SharedFS] 장시간·고메모리 설정 변경을 위한 사전 점검과 장애 격리](https://github.com/ablecloud-team/ablestack-cloud/issues/897)

상태: OPEN; 원문 갱신 시각: 2026-08-17T22:12:50Z.

**구현 범위**

- DB/API: operation, phase, progress, heartbeat, cancelability, diagnostic
- Backend: queue, lock, preflight, retry/reconcile
- SystemVM agent: operation journal, resource probe, idempotent resume
- UI: 백그라운드 진행률, 예상 영향, 취소 가능 여부, 실패 복구 안내
- Test: timeout, OOM 위험, QGA 단절, 재부팅, 중복 요청, 세션 drain

**완료 조건**

- 자원 부족이 예상되는 작업은 서비스 변경 전에 차단됩니다.
- 동시에 두 설정 변경이 같은 인스턴스에 적용되지 않습니다.
- 관리 서버 또는 SystemVM 재시작 후 작업 상태를 재조정할 수 있습니다.
- 실패 시 서비스가 직전 정상 설정을 유지하거나 자동 롤백됩니다.

### [#974 [SharedFS][SystemVM] 대용량 백킹 볼륨 초기 포맷 timeout 및 재개 가능한 준비 작업 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/974)

상태: OPEN; 원문 갱신 시각: 2026-09-10T02:47:47Z.

**테스트 게이트**

- 지연 주입한 10 TiB XFS 포맷이 20초를 초과해도 정상 완료
- ext4 대용량 볼륨 포맷과 mount/write 검증
- mkfs 완료 후 응답 직전 QGA timeout에서 상태 재조회 후 정상 재개
- 포맷 도중 SystemVM/관리 서버 재시작 후 operation reconcile
- 불완전한 파일시스템에서 자동 재포맷 금지 및 `RECOVERY_REQUIRED` 표시
- 기존 파일시스템, 잘못된 UUID와 다른 VM의 볼륨 포맷 차단
- 같은 operation 중복 요청의 멱등 처리
- timeout 후 share/volume DB와 실제 attach/mount 상태 일치
- 실패 시 신규·기존 볼륨 데이터 보존 정책 검증
- UI 백그라운드 진행률, 재시도, 진단 상세 및 라이트·다크 모드 검증
- 성공 후 NFS/SMB mount, read/write 및 SystemVM 재부팅 복구 검증

**완료 조건**

- 대용량 신규 백킹 볼륨이 고정 20초 제한 때문에 실패하지 않습니다.
- timeout이나 통신 단절 후 실제 포맷 결과를 재조회하고 안전하게 재개할 수 있습니다.
- 부분 포맷된 device를 자동으로 다시 포맷하지 않습니다.
- SharedFS, file share, volume과 SystemVM mount 상태가 부분 성공으로 방치되지 않습니다.
- 사용자는 UI에서 현재 단계, 경과 시간과 복구 방법을 확인할 수 있습니다.
- XFS와 ext4, thin 및 일반 블록 스토리지에서 회귀 테스트를 통과합니다.

### [#913 [SharedFS][SystemVM] L2 고정 IP 기본 게이트웨이 재부팅 지속성 보장 및 DHCP 충돌 방지](https://github.com/ablecloud-team/ablestack-cloud/issues/913)

상태: OPEN; 원문 갱신 시각: 2026-08-24T15:30:47Z.

**테스트 게이트**

1. STATIC + gateway 생성 직후 `default via <gateway>` 확인
2. STATIC + gateway 상태에서 System VM 재부팅 후 주소·기본 경로 복구 확인
3. 재부팅 후 Storage reconcile과 NFS/SMB/iSCSI/NVMe-oF 서비스 복구 확인
4. DHCP 임대 갱신 시간을 경과해도 정적 주소·기본 경로 유지 확인
5. STATIC + gateway 미입력 시 connected route만 존재하는지 확인
6. DHCP 모드 생성·재부팅 회귀 테스트
7. DHCP 유닛이 STATIC NIC를 다시 시작하지 못하는지 확인
8. NIC 이름이 `eth0`이 아닌 환경에서 MAC 기반 적용 확인
9. 잘못된 gateway 입력 거절 확인
10. 기존 운영 VM에 QGA 교정본 배포 후 서비스 중단 없이 경로 복구 확인
11. 신규 System VM 템플릿으로 생성한 VM과 기존 VM의 동작 일치 확인

**완료 조건**

- 저장 JSON에 gateway가 있으면 생성 직후와 재부팅 후 모두 정확한 default route가 존재합니다.
- STATIC NIC에서 DHCP 유닛과 잔존 DHCP 프로세스가 주소·경로를 변경하지 않습니다.
- Storage reconcile은 네트워크 준비 완료 후 시작하며 DHCP 모드도 정상 동작합니다.
- 기본 경로 불일치가 모니터링과 API/UI 상태에 명확히 표시됩니다.
- 기존 운영 VM은 템플릿 교체 없이 QGA 기반 교정본을 적용할 수 있습니다.
- 신규 System VM 템플릿에 수정된 네트워크·reconcile 구성이 포함됩니다.

### [#914 [SharedFS][SystemVM][NFS] 다수 Export 부팅 Reconcile의 초기 Probe 지연과 고정 Timeout 제거](https://github.com/ablecloud-team/ablestack-cloud/issues/914)

상태: OPEN; 원문 갱신 시각: 2026-08-24T15:29:52Z.

**테스트 게이트**

1. NFS export 1개, 10개, 100개 cold boot
2. NFSv4 only와 NFSv3+v4 dual mode
3. 단일·다중 endpoint/port group
4. 첫 mount probe 의도적 지연 후 retry 성공
5. 특정 export만 timeout 또는 permission 실패
6. `mount.nfs` 프로세스 hang과 강제 cleanup
7. NFS 실패 중 SMB가 한 번만 적용되는지 확인
8. systemd timeout보다 내부 deadline이 먼저 종료되는지 확인
9. 실제 listener는 ready지만 상세 probe 진행 중인 상태 표시
10. 재부팅 후 reconcile success, monitor `ok`, UI 상태 일치
11. reconcile 중 QGA/관리 서버 단절 후 guest 독립 완료
12. 기존 적은 수의 export 환경 회귀 테스트

**완료 조건**

- 다수 export 환경에서도 boot reconcile이 systemd timeout으로 중단되지 않습니다.
- cold-start readiness 지연은 구조화된 retry로 처리됩니다.
- 성공한 프로토콜을 실패한 프로토콜 때문에 반복 재시작하지 않습니다.
- 모든 probe 프로세스와 mount 디렉터리가 성공·실패·timeout 후 정리됩니다.
- 실제 서비스 readiness와 reconcile 진행 상태가 monitor/API/UI에 일치하게 표시됩니다.
- 10개 export 실증 환경에서 재부팅 후 NFS/SMB, monitor와 unit이 모두 정상 상태입니다.

### [#918 [SharedFS][NFS] 제한 ACL의 로컬 visibility probe 실패로 전체 Ganesha가 중지되는 문제](https://github.com/ablecloud-team/ablestack-cloud/issues/918)

상태: OPEN; 원문 갱신 시각: 2026-08-26T05:55:41Z.

**테스트 게이트**

- [ ] `Clients=192.168.3.11/32`, SystemVM 로컬 IP 미포함 상태에서 apply 성공
- [ ] ACL에 SystemVM IP가 자동 추가되지 않음
- [ ] Ganesha 설정 파싱, managed process, 2049 listener 정상
- [ ] 허용 클라이언트 `192.168.3.11`의 NFSv4 mount/read/write 성공
- [ ] 허용 클라이언트의 NFSv3 mount/read/write 성공(Dual Mode)
- [ ] 허용되지 않은 SystemVM 로컬 주소와 외부 주소의 mount 거부
- [ ] 로컬 mount 거부 후에도 Ganesha 프로세스와 다른 export 유지
- [ ] wildcard ACL에서는 기존 로컬 mount probe 정상 수행
- [ ] SystemVM 로컬 주소를 포함한 CIDR에서는 기존 mount probe 정상 수행
- [ ] 실제 config parse/process/listener 실패는 여전히 fail-closed 처리
- [ ] 단일 export probe 실패가 다른 정상 export를 중지하지 않음
- [ ] desired-state 재적용 및 SystemVM 재부팅 후 제한 ACL과 서비스 유지
- [ ] monitor/UI가 `ACL_RESTRICTED_NO_LOCAL_SOURCE`를 오류가 아닌 probe 생략으로 표시

**완료 조건**

- 특정 외부 클라이언트 IP/CIDR만 허용한 NFS export를 추가·수정할 수 있습니다.
- SystemVM 자신의 IP를 ACL에 추가하지 않아도 desired-state 적용이 성공합니다.
- 제한 ACL 때문에 다른 NFS export가 중단되지 않습니다.
- 실제 Ganesha 장애와 ACL로 인한 로컬 probe 생략이 runtime/API/UI에서 명확하게 구분됩니다.
- 재부팅과 reconcile 후에도 동일한 ACL과 서비스 상태가 복구됩니다.

### [#895 [SharedFS] 삭제 시 데이터 볼륨 보존 및 분리 정책 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/895)

상태: OPEN; 원문 갱신 시각: 2026-08-17T22:12:53Z.

**구현 범위**

- API: destroy/expunge 보존 정책, dry-run 영향 목록
- Backend/DB: 볼륨 관계 inventory와 삭제 정책 audit
- Provider/SystemVM: unmount, 서비스 해제, detach 순서 보장
- UI: 보존 기본 선택, 영향 볼륨 표, 파괴적 삭제 이중 확인
- Test: 기본 및 추가 볼륨 보존/삭제/부분 실패 시나리오

**완료 조건**

- 파일시스템 삭제 후 보존을 선택한 모든 데이터 볼륨이 삭제되지 않고 재사용 가능합니다.
- 볼륨 삭제 선택 시에만 명시된 볼륨이 삭제됩니다.
- 보존 실패가 데이터 삭제로 이어지지 않습니다.

### [#909 [SharedFS] 구성 백업 번들 내보내기·업로드 및 마지막 정상 구성 복원 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/909)

상태: OPEN; 원문 갱신 시각: 2026-08-19T08:09:58Z.

**테스트 게이트**

- NFS, SMB, iSCSI, NVMe-oF 단독 및 통합 서비스의 전체 구성 내보내기·업로드·복구
- Running, Stopped, Error, SystemVM 접근 불가 상태의 내보내기
- 백업 중 desired state 변경에 대한 revision 재시도/실패
- desired/runtime drift 및 stale cache 표시
- archive 해제, 파일 목록, 개별 및 전체 SHA-256 검증
- 비밀번호, CHAP/DH-HMAC-CHAP key, keytab, Samba private 정보가 포함되지 않는지 자동 검사
- 악성 archive 경로, symlink/hardlink, 압축 폭탄, 과도한 JSON depth/size 거부
- 손상 checksum, 미지원 schemaVersion, 상·하위 호환 버전 검증
- `RESTORE_EXISTING` dry-run, 리소스 mapping, secret 재입력, 성공 적용 및 runtime verify
- `CREATE_NEW`의 zone/network/offering/storage/volume mapping과 새 UUID 생성
- 최초 생성 및 각 프로토콜 설정 변경 성공 후 `ACTIVE_LKG` 승격, 실패·timeout·부분 적용 시 기존 지점 유지
- 현재 구성과 마지막 정상 구성의 dry-run diff, 수동 복원, runtime revision 검증과 복원 실패 시 emergency rollback
- 기존 서비스의 최초 성공 reconcile baseline 생성과 관리 서버/SystemVM 재부팅 후 복원 지점 유지
- 삭제·점유 변경 volume, endpoint 충돌, 잘못된 CIDR/port와 미해결 secret의 apply 차단
- apply 실패 주입 후 자동 rollback과 기존 서비스 연결 유지
- 복구 계획에 없는 기존 리소스와 데이터 volume 보존
- 권한 없는 생성·업로드·검증·계획·적용·다운로드·삭제 거부 및 토큰 만료
- 대규모 share/ACL/endpoint 구성의 크기 제한과 timeout
- 관리 서버 및 SystemVM 재시작 후 operation·artifact 보존/정리
- 데이터 볼륨의 파일 내용이 포함되지 않는지 검증
- 다크모드, i18n, wizard, 비동기 진행, 부분 갱신 UI 검증

**완료 조건**

- 관리자가 SharedFS 서비스 전체 구성 백업을 생성하고 외부로 다운로드할 수 있습니다.
- 외부 번들을 다시 업로드해 안전성·무결성·호환성 검증 결과를 확인할 수 있습니다.
- 기존 서비스 복구와 신규 서비스 복제를 위한 resource mapping 및 dry-run 계획을 확인할 수 있습니다.
- 명시적으로 승인한 계획만 기존 domain validator와 desired-state apply 경로를 통해 적용됩니다.
- 마지막으로 runtime 검증까지 성공한 구성이 자동 `ACTIVE_LKG` 복원 지점으로 보관되고 실패한 변경은 이를 덮어쓰지 않습니다.
- 운영자가 현재 구성과의 diff를 확인한 뒤 마지막 정상 구성으로 복원할 수 있습니다.
- 적용 실패 시 기존 설정 또는 emergency snapshot으로 롤백되고 runtime 검증 결과가 기록됩니다.
- 번들의 desired/runtime revision과 drift 상태가 실제 상태와 일치합니다.
- 중지·오류 상태에서도 가능한 범위의 백업을 만들고 누락 범위를 명확히 표시합니다.
- 비밀정보와 데이터 볼륨 내용이 번들에 포함되지 않으며 누락 비밀값은 적용 전에 재입력합니다.
- 다운로드·업로드한 번들의 checksum과 schemaVersion을 검증할 수 있습니다.

### [#920 [SharedFS][SystemVM] 운영 서비스의 SystemVM 템플릿 교체 업그레이드 및 설정 자동 복구 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/920)

상태: OPEN; 원문 갱신 시각: 2026-08-27T04:11:45Z.

**테스트 게이트**

- KVM 동일 VM ID에서 새 template ROOT로 교체하고 NIC/MAC/IP가 유지되는지 검증
- 기본/보조 endpoint와 firewall/route 복구
- 다중 NFS/SMB 백킹 볼륨 mount/fstab 및 read/write 복구
- iSCSI/NVMe-oF raw volume device mapping과 read/write 복구
- SMB local 사용자, AD domain join 상태, keytab/passdb 복구 및 로그인 검증
- iSCSI CHAP secret 복구와 인증 연결 검증
- 관리 서버/agent/SystemVM 재시작을 각 phase에 주입한 재개 테스트
- target ROOT 부팅 실패, QGA 미응답, identity 복원 실패, 프로토콜 probe 실패별 previous ROOT 자동 롤백
- 롤백 중 target ROOT와 사용자 DATA 볼륨의 비파괴성 검증
- 설정 변경과 템플릿 업그레이드 동시 요청 거부
- 이전 ROOT retention/finalize/수동 rollback 테스트
- UI 다크모드, i18n, 진행률, 단절 경고와 오류/롤백 결과 표시

### [#900 [SharedFS][SMB] 프로토콜 반복 활성화 시 기존 엔드포인트 유실 방지 및 다중 IP 서비스 보장](https://github.com/ablecloud-team/ablestack-cloud/issues/900)

상태: OPEN; 원문 갱신 시각: 2026-08-18T04:55:18Z.

**완료 조건**

- IP A를 활성화한 뒤 IP B를 활성화해도 DB에 A와 B endpoint가 모두 남습니다.
- desired payload, SystemVM NIC, `smb.conf`, 수신 소켓, API, UI에 A와 B가 모두 표시됩니다.
- A와 B 각각에서 동일 SMB 공유 연결 및 권한별 읽기/쓰기가 성공합니다.
- 같은 endpoint 재요청은 중복 행을 만들지 않습니다.
- B 적용 실패 시 A의 DB/runtime/UI 상태가 유지됩니다.
- B만 삭제하면 A 서비스는 중단되지 않고, 공유 중인 NIC alias는 잘못 제거되지 않습니다.
- 관리 서버와 SystemVM 재시작 후 모든 endpoint가 자동 복구됩니다.

### [#896 [SharedFS] 컴퓨트 오퍼링 제약 조건 안내와 선택 UX 개선](https://github.com/ablecloud-team/ablestack-cloud/issues/896)

상태: OPEN; 원문 갱신 시각: 2026-08-17T22:12:52Z.

**구현 범위**

- API: SharedFS offering constraints와 compatibility reason
- Backend: 공통 validator 한 곳에서 목록과 생성 검증 재사용
- UI: disabled option, 이유 tooltip, 요구조건 요약, 오퍼링 생성 안내
- Test: 조건별 적합/부적합 목록과 서버 검증 일치

**완료 조건**

- 사용자는 오퍼링이 보이지 않거나 선택되지 않는 이유를 생성 전에 확인할 수 있습니다.
- UI와 서버가 같은 validator 결과를 사용합니다.

### [#891 [SharedFS] 온라인 스케일업 가능한 컴퓨트 오퍼링 사용 강제](https://github.com/ablecloud-team/ablestack-cloud/issues/891)

상태: OPEN; 원문 갱신 시각: 2026-08-17T22:12:53Z.

**구현 범위**

- Provider/Backend: prerequisite 및 기존 인스턴스 compatibility audit
- API: effective dynamic scaling readiness와 실패 reason
- UI: 호환 오퍼링만 선택 가능, 기존 비호환 경고
- Test: global, template, offering, hypervisor 조건 조합 및 online scale-up

**완료 조건**

- 비동적 오퍼링으로 신규 SharedFS를 만들 수 없습니다.
- 허용된 오퍼링은 실행 중 CPU/메모리 scale-up 검증을 통과합니다.
- 실패한 scale-up이 서비스 설정이나 VM 상태를 손상하지 않습니다.

### [#904 [SharedFS] 초기 생성 시 기존 볼륨 경로의 디스크 오퍼링 의존성 제거](https://github.com/ablecloud-team/ablestack-cloud/issues/904)

상태: OPEN; 원문 갱신 시각: 2026-08-18T04:34:07Z.

**구현 범위**

- API: backing volume mode, existing volume ID, mode별 validation contract
- Backend/Provider: 생성 경로 분기, attach/mount transaction, rollback
- DB: SharedFS 기본 백킹 볼륨 관계 및 import 상태
- UI: mode별 필드, 기존 볼륨 메타데이터, preflight 결과
- Test: 신규/기존 볼륨, offering 없음, zone/account 불일치, 포맷된 XFS/EXT4, attach 실패, 재시도

**완료 조건**

- 기존 볼륨 모드에서 사용자가 선택할 수 없는 disk offering 오류가 발생하지 않습니다.
- 선택한 기존 볼륨이 실제 SystemVM에 연결·마운트되고 SharedFS 기본 백킹 볼륨으로 표시됩니다.
- 실패한 생성은 고아 SharedFS, 고아 VM, 잘못 연결된 볼륨을 남기지 않습니다.

### [#905 [SharedFS] 목록 용량을 모든 백킹 볼륨의 고유 합계로 표시](https://github.com/ablecloud-team/ablestack-cloud/issues/905)

상태: OPEN; 원문 갱신 시각: 2026-08-18T04:34:10Z.

**구현 범위**

- DB/Query: 인스턴스별 고유 백킹 볼륨 집계
- API/Response: 초기 용량과 합산 용량, 사용량, freshness 필드
- Backend: 프로토콜 간 중복 제거와 detach 상태 처리
- UI: 목록 용량 칼럼과 상세 tooltip/상태 표시
- Test: 기본 1개, 추가 여러 개, NFS/SMB 공동 볼륨, block 볼륨, detach, stale runtime cache, 대량 목록

**완료 조건**

- 목록의 총 용량이 현재 연결된 고유 백킹 볼륨 크기의 합과 일치합니다.
- 동일 볼륨의 교차 프로토콜 공유가 중복 합산되지 않습니다.
- 초기 디스크 크기와 서비스 전체 용량을 API와 UI에서 명확히 구분할 수 있습니다.

### [#894 [SharedFS][NFS] squash 정책별 POSIX 권한 기본값과 가이드 정립](https://github.com/ablecloud-team/ablestack-cloud/issues/894)

상태: OPEN; 원문 갱신 시각: 2026-08-17T22:12:56Z.

**구현 범위**

- API/Backend: policy preset, effective POSIX policy, preflight 결과
- SystemVM: 안전한 stat, permission 검증, 선택적 원자 적용
- UI: 권장 preset, 고급 설정, 정책별 설명 및 경고
- Test: root squash, no root squash, all squash, RO/RW 조합과 정책 전환

**완료 조건**

- no root squash 생성 및 전환 시 의도하지 않은 nobody 소유권이 기본값으로 남지 않습니다.
- 각 정책의 effective UID, GID, mode가 API와 UI에 동일하게 표시됩니다.
- 기존 데이터 권한의 파괴적 변경은 명시적 승인 없이는 수행되지 않습니다.

### [#906 [SharedFS][NFSv4] 숫자 UID/GID 모드로 ID 매핑 불일치와 nobody 표시 방지](https://github.com/ablecloud-team/ablestack-cloud/issues/906)

상태: OPEN; 원문 갱신 시각: 2026-08-27T11:44:06Z.

**구현 범위**

- API: NFS protocol command/response의 `idmappingmode`
- Backend: service-level validation, persistence, desired-state payload, rollback/reconcile
- SystemVM: Ganesha `NFSV4` 렌더링, config/runtime drift 수집, 재부팅 복구
- UI: 초기 설정, NFS 서비스 설정, 상태/경고/접속 안내
- Test: API contract, backend unit, SystemVM config rendering, Linux client mount/stat/read/write, reboot/reconcile

**테스트 게이트**

- 동일 UID/GID를 가진 두 Linux 클라이언트에서 `NUMERIC` 모드로 파일 생성 후 `stat -c %u:%g`가 서버와 양쪽 클라이언트에서 일치합니다.
- `NAME_DOMAIN` 모드의 기존 동작과 기존 인스턴스 호환성이 유지됩니다.
- `root_squash`, `no_root_squash`, `all_squash` 각각에서 numeric owner mode와 anon/owner UID/GID 정책이 독립적으로 정확히 적용됩니다.
- NFSv4 only와 NFSv3+v4 dual 모두에서 NFSv4 owner 표시가 선택 정책과 일치하며 NFSv3 동작은 회귀하지 않습니다.
- 일부 endpoint 적용 실패 시 모든 endpoint가 이전 ID mapping mode로 롤백됩니다.
- SystemVM 재부팅 후 동일 mode, endpoint, export가 복구되고 모니터링 상태가 `CONSISTENT`입니다.
- 클라이언트 설정이 맞지 않는 경우 무조건 성공으로 오판하지 않고 진단 가능한 안내를 제공합니다.

**완료 조건**

- NFSv4 숫자 UID/GID 모드를 선택한 환경에서 일치하는 UID/GID가 `nobody`로 표시되지 않습니다.
- 서비스의 desired/effective/runtime ID mapping mode가 API, SystemVM, 모니터링 캐시, UI에서 일치합니다.
- 기존 export/ACL POSIX 정책과 숫자 ID mapping 정책의 역할이 UI와 문서에서 명확히 분리됩니다.
- 모드 변경 실패와 재부팅이 기존 NFS 서비스 설정 유실로 이어지지 않습니다.

### [#903 [SharedFS][NFS/SMB] 교차 프로토콜 공유 디렉터리의 POSIX 속성 단일화](https://github.com/ablecloud-team/ablestack-cloud/issues/903)

상태: OPEN; 원문 갱신 시각: 2026-08-18T04:34:04Z.

**구현 범위**

- DB: 공통 POSIX path policy와 NFS/SMB 참조 관계
- API/Backend: canonical path lock, compatibility validation, impact preview, atomic apply
- SystemVM: stat/chown/chmod 단일 실행과 사후 검증
- UI: 공유 경로 표시, 상속 정책, 공통 편집 모달, 충돌 메시지
- Test: 동일 경로 NFS+SMB 생성, owner/mode 변경, recursive on/off, 부분 실패, 재부팅

**완료 조건**

- 동일 디렉터리의 owner/mode가 API, NFS, SMB, SystemVM에서 하나의 유효값으로 일치합니다.
- 한 프로토콜의 수정이 다른 프로토콜을 Error로 만들지 않습니다.
- 충돌하는 변경은 파일 권한을 바꾸기 전에 명확한 영향 정보와 함께 차단됩니다.

### [#915 [SharedFS][SMB] 부모 UID/GID 상속을 위한 inherit owner 및 setgid 정책 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/915)

상태: OPEN; 원문 갱신 시각: 2026-08-24T21:52:27Z.

**구현 범위**

- API: SMB create/update command 및 response 필드
- Backend: config persistence, validation, desired-state payload, preview/rollback
- SystemVM: Samba `inherit owner` 렌더링, setgid 보장, runtime drift 수집
- UI: SMB 공유 생성/수정, 상세/목록의 effective 정책 표시
- Test: API contract, backend unit, SystemVM renderer, AD 클라이언트 E2E, reconcile/reboot

**테스트 게이트**

- 부모 디렉터리 `1001001:1001001`, mode `2775`에서 허용된 AD 사용자가 만든 새 파일이 `1001001:1001001`, 새 디렉터리가 `1001001:1001001` 및 setgid mode로 생성됩니다.
- 부모 디렉터리의 owner, inode, 기존 파일 owner는 변경되지 않습니다.
- READ_ONLY 또는 `valid users`에 없는 계정은 소유권 상속 정책이 있어도 쓰기 권한을 얻지 못합니다.
- 서로 다른 부모 디렉터리에서는 각 부모의 UID/GID를 독립적으로 상속합니다.
- desired-state 재적용과 SystemVM 재부팅 후 `testparm`의 `inherit owner`, 디렉터리 setgid, 실제 생성 결과가 유지됩니다.
- 정책을 기본 모드로 되돌리면 `inherit owner`가 제거되고 기존 파일은 변경되지 않습니다.
- #908의 강제 UID/GID 정책 및 #903의 교차 프로토콜 공통 path policy와 충돌하는 조합을 사전 차단합니다.

**완료 조건**

- 운영자가 smb.conf를 직접 수정하지 않고 API/UI에서 `inherit owner`와 setgid 그룹 상속을 명시적으로 설정할 수 있습니다.
- 설정이 DB desired state, SystemVM Samba 설정, runtime 상태, UI에서 일치합니다.
- 기존 데이터에 재귀 변경을 가하지 않고 새 객체만 부모 UID/GID를 안전하게 상속합니다.
- reconcile 및 재부팅 후에도 정책이 지속됩니다.

### [#916 [SharedFS][NFS/SMB] 공유 하위 디렉터리별 POSIX 소유권·ACL 정책 관리](https://github.com/ablecloud-team/ablestack-cloud/issues/916)

상태: OPEN; 원문 갱신 시각: 2026-08-25T00:52:45Z.

**구현 범위**

- DB: protocol-neutral POSIX directory policy 및 ACL entry
- API: create/update/delete/list/apply 및 response
- Backend: canonical path lock, protocol impact 조회, validation, desired-state, rollback/reconcile
- SystemVM: 비재귀 owner/mode/access/default ACL 적용 및 drift 수집
- UI: NFS/SMB 공통 정책 목록, 생성/수정/삭제, preview, effective 상태
- Test: API contract, backend unit, SystemVM path safety, NFS/SMB E2E, 교차 프로토콜, reconcile/reboot

**테스트 게이트**

- 대상 디렉터리 owner `1001001:1001001`, mode `2775`가 적용되고 기존 inode와 하위 데이터는 변경되지 않습니다.
- 허용된 Domain Users가 SMB 계정 ACL과 POSIX ACL을 모두 통과해 읽기/쓰기할 수 있습니다.
- 허용된 NFS 클라이언트가 export ACL과 POSIX ACL을 모두 통과해 읽기/쓰기할 수 있습니다.
- SMB에서 #915와 결합해 만든 새 파일/디렉터리는 `1001001:1001001`을 가집니다.
- NFS에서 만든 새 객체는 NFS identity mapping UID와 setgid 부모 GID를 가지며 default ACL을 상속합니다.
- `root_squash`, `no_root_squash`, numeric UID/GID 모드별 POSIX ACL 판정이 예상과 일치합니다.
- 정책 대상 밖의 다른 하위 디렉터리는 owner/mode/ACL 변경 영향을 받지 않습니다.
- NFS CIDR ACL 또는 SMB 계정 ACL을 통과하지 못한 주체는 POSIX ACL이 있어도 접근 권한을 얻지 못합니다.
- 경로 이탈, symlink, 다른 mount, 다른 정책 소유 경로를 적용 전에 차단합니다.
- desired-state 재적용과 SystemVM 재부팅 후 owner/mode/access/default ACL 및 drift 상태가 복구됩니다.
- 정책 삭제 후 실제 디렉터리와 파일은 보존됩니다.

**완료 조건**

- 운영자가 SystemVM에서 수동 `chown/chmod/setfacl`을 실행하지 않고 NFS/SMB가 사용하는 하위 디렉터리별 POSIX 정책을 API/UI로 관리할 수 있습니다.
- 프로토콜 접근 ACL과 POSIX 디렉터리 정책이 독립적으로 저장·표시되고 실제 접근 판정에서 함께 적용됩니다.
- 동일 canonical path의 desired/effective 정책이 NFS/SMB/API/DB/SystemVM/UI에서 하나의 값으로 일치합니다.
- 기존 데이터에 재귀 변경을 가하지 않고 새 객체 상속 정책을 안전하게 구성할 수 있습니다.
- reconcile 및 SystemVM 재부팅 후 정책이 복구됩니다.

### [#919 [SharedFS][SMB] 공유별 create/directory mask와 force mode 정책 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/919)

상태: OPEN; 원문 갱신 시각: 2026-08-26T05:28:16Z.

**테스트 게이트**

- [ ] 기본값이 기존 `0660/0770` 동작과 동일
- [ ] share별 `0775/0775` 강제 적용
- [ ] SMB 생성 일반 파일이 정확히 0775
- [ ] SMB 생성 디렉터리가 정확히 0775
- [ ] 다른 SMB share의 mode 정책 불변
- [ ] Default POSIX ACL과 named group ACL 유지
- [ ] 적용 전후 smbd/nmbd/winbind PID 동일
- [ ] 기존 SMB 세션과 open file handle 유지
- [ ] reload 실패 시 이전 config 복구 및 API job 실패
- [ ] SystemVM 재부팅 후 desired-state로 같은 설정 복구
- [ ] SystemVM 재생성 시 정식 템플릿/runtime에서 같은 기능 제공
- [ ] NFS/SMB 교차 프로토콜 경로 회귀 없음

**완료 조건**

- 운영자가 공유별 새 파일/디렉터리 mode를 API/UI에서 설정할 수 있습니다.
- 정확한 `create mask`, `force create mode`, `directory mask`, `force directory mode`가 runtime과 UI에 표시됩니다.
- 단순 share option 변경은 기존 SMB 연결을 종료하지 않고 적용됩니다.
- 설정이 DB, desired-state, SystemVM config, runtime에서 일치하고 재부팅 후 복구됩니다.

### [#910 [SharedFS][NFS/SMB] 동일 백킹 볼륨 하위 디렉터리의 중첩 Export/Share 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/910)

상태: OPEN; 원문 갱신 시각: 2026-08-20T01:40:42Z.

**완료 조건**

- NFS와 SMB 모두 같은 백킹 볼륨의 하위 디렉터리를 독립된 export/share로 생성·수정·삭제할 수 있다.
- 물리 백킹 경로와 클라이언트 노출 경로가 API, DB, SystemVM, UI에서 일관되게 표시된다.
- 경로 이탈, 교차 볼륨 중첩, 부모 삭제, 볼륨 연결 해제에 대한 데이터 안전 검증이 적용된다.
- 기존 직접 자식 경로 및 NFS/SMB 교차 프로토콜 공유 동작에 회귀가 없다.

### [#908 [SharedFS][SMB] 공유별 force user/group 기반 고정 POSIX UID/GID 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/908)

상태: OPEN; 원문 갱신 시각: 2026-08-20T00:57:15Z.

**구현 범위**

- API: SMB create/update command와 response ownership 필드
- Backend: config persistence, validation, desired-state payload, rollback/reconcile 연계
- SystemVM: 관리 UNIX identity 수명주기, directory ownership 적용, Samba `force user/group` 렌더링, runtime drift 수집
- UI: SMB 공유 생성/수정, 목록/상세, 경고 및 상태 표시
- Test: API contract, backend unit, SystemVM renderer, AD/로컬 클라이언트 E2E, 재부팅 복구

**테스트 게이트**

- AD 사용자와 로컬 사용자가 각각 새 파일과 디렉터리를 생성했을 때 서버 `stat -c %u:%g`가 설정 UID:GID와 일치합니다.
- 인증되지 않은 계정과 READ_ONLY 계정은 고정 소유권 설정이 있어도 쓰기 권한을 얻지 못합니다.
- 서로 다른 허용 AD/로컬 계정이 같은 기존 파일을 읽고 쓸 수 있으며, 강제 UNIX identity에서 POSIX 권한을 제거하면 인증 성공 후에도 접근이 거부됩니다.
- `valid users`에 없는 계정은 강제 UNIX identity가 파일 권한을 가져도 공유 연결이 거부됩니다.
- SMB 세션과 감사 로그에는 실제 접속 AD/로컬 사용자 이름이 유지됩니다.
- 중첩 디렉터리와 새 파일에서도 강제 UID/GID가 유지됩니다.
- ADMIN ACL과 guest access 조합이 고정 identity 정책을 우회하지 않으며, 지원하지 않는 조합은 apply 전에 차단됩니다.
- `AUTHENTICATED_USER` 모드의 기존 공유 동작이 회귀하지 않습니다.
- 정책 변경 전 존재한 파일의 UID/GID는 변경되지 않습니다.
- 동일 경로 NFS+SMB 구성에서 공통 path policy와 UID/GID가 일치하고 NFS 클라이언트 표시도 예상과 같습니다.
- SystemVM 재부팅 후 mount, share, ownership mode, UID/GID와 계정 ACL이 복구됩니다.
- 충돌 UID/GID, 보호 ID, 적용 실패를 사전 차단하거나 이전 revision으로 롤백합니다.

**완료 조건**

- 공유별로 AD/로컬 인증 주체와 무관한 고정 POSIX UID/GID 파일 작업 정책을 선택할 수 있습니다.
- 허용된 AD/로컬 인증 사용자는 SMB ACL과 강제 identity의 POSIX 권한을 모두 만족할 때 강제 UID/GID 소유 파일에 접근할 수 있습니다.
- 인증/계정 ACL과 파일 소유권 정책이 API, SystemVM, Samba, 모니터링 캐시, UI에서 명확히 분리되고 일치합니다.
- 기존 공유와 기존 파일에 파괴적 변경이 발생하지 않습니다.
- NFS/SMB 교차 프로토콜 공유에서도 단일 POSIX path policy를 위반하지 않습니다.

### [#902 [SharedFS][SMB] AD 가입 시 다중 서비스 호스트명·DNS 별칭 등록 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/902)

상태: OPEN; 원문 갱신 시각: 2026-08-24T15:07:04Z.

**구현 범위**

- DB/API: SMB AD service alias 자원과 create/update/delete/list API
- Backend: endpoint 연동, 충돌 검사, rollback, audit event
- SystemVM: AD alias/SPN/DNS 등록·검증·정리 명령
- UI: 도메인 가입/편집 모달의 다중 호스트명 입력과 상태 테이블
- Test: 복수 IP/별칭 Kerberos SMB 접속, 중복 SPN, 권한 실패, IP 삭제, leave/rejoin, 재부팅

**완료 조건**

- 하나의 AD computer account에서 두 개 이상의 SMB FQDN으로 Kerberos 인증 연결이 성공합니다.
- 각 FQDN이 의도한 endpoint IP로 해석되고 UI 상태와 일치합니다.
- 부분 실패 시 기존 정상 별칭과 도메인 가입 상태가 보존됩니다.
- 삭제한 endpoint의 DNS/SPN만 정리되고 다른 별칭은 유지됩니다.

### [#907 [SharedFS][SMB] 공유별 클라이언트 IP/CIDR 접근 허용 정책 지원](https://github.com/ablecloud-team/ablestack-cloud/issues/907)

상태: OPEN; 원문 갱신 시각: 2026-08-19T07:22:39Z.

**테스트 게이트**

- API unit test: IPv4/IPv6, CIDR prefix, 중복, 쉼표 입력, hostname/와일드카드 거부
- Backend test: 계정 ACL과 네트워크 ACL 분리, idempotent create/update/delete, 공유 삭제 cascade
- SystemVM test: 규칙 없음은 미렌더링, 규칙 존재 시 공유별 `hosts allow`, `testparm` 실패 rollback
- 허용 CIDR 클라이언트에서 로컬 사용자 및 AD 사용자 mount/read/write 성공
- 비허용 CIDR 클라이언트에서 동일한 정상 계정으로 tree connect/mount 실패
- 허용 CIDR이지만 계정 ACL이 없는 사용자는 접근 실패
- guest access 공유도 네트워크 allow-list 밖에서는 접근 실패
- 서로 다른 allow-list를 가진 두 공유가 독립적으로 동작
- 다중 SMB endpoint, SystemVM/Samba 재시작 및 재부팅 후 정책 복구
- DB desired, SystemVM `smb.conf`, 모니터링 캐시, API, UI 목록 정합성
- NFS/SMB 교차 공유 상태에서 NFS ACL에 영향이 없는지 회귀 테스트

**완료 조건**

- 공유별 IP/CIDR allow-list와 계정/그룹 ACL을 동시에 적용할 수 있습니다.
- 허용 및 비허용 클라이언트의 실제 연결 결과가 DB, runtime, API, UI 상태와 일치합니다.
- 기존 SMB 공유는 별도 네트워크 규칙이 없을 때 기존 접근 동작을 유지합니다.
- 실패·재부팅·재적용 후에도 네트워크 정책이 유실되거나 다른 공유에 전파되지 않습니다.

### [#901 [SharedFS][NFS] 프로토콜·리스너 정보를 행 기반 목록으로 분리 표시](https://github.com/ablecloud-team/ablestack-cloud/issues/901)

상태: OPEN; 원문 갱신 시각: 2026-08-18T04:33:59Z.

**구현 범위**

- UI: `nfsListenerRows`, `nfsExportRows`, listener/export column 정의와 cell renderer 정리
- API: UI가 문자열을 재파싱하지 않도록 listener와 effective endpoint 배열 계약 확인 및 보완
- i18n: listener type, protocol mode, linked export count, runtime state 레이블
- Test: wildcard, dedicated, 복수 포트 그룹, dual mode, 긴 IPv6/별칭 값, 다크모드

**완료 조건**

- NFS listener가 한 셀의 결합 문자열이 아니라 행 기반 목록으로 표시됩니다.
- export와 listener의 관계를 중복 문자열 없이 확인할 수 있습니다.
- 가로 스크롤 중에도 식별 칼럼과 작업 칼럼이 유지됩니다.

## 코드 경로와 공통 계약

| 계층 | 기존 경로·확장 위치 | 구현 시 확인 |
|---|---|---|
| 호환 API | `api/src/main/java/org/apache/cloudstack/api/command/user/storage/sharedfs` | 기존 create/list/update/lifecycle 계약 유지 |
| 확장 API | `api/src/main/java/org/apache/cloudstack/api/command/user/storage/dataservice`, 관리자 dataservice API | 권한, async job, revision/operation 응답 |
| 서비스 | `server/src/main/java/org/apache/cloudstack/storage/sharedfs/SharedFSServiceImpl.java`, `server/src/main/java/org/apache/cloudstack/storage/dataservice/StorageServiceManagerImpl.java` | validate → desired state → host command → apply/verify |
| DB | `engine/schema/src/main/java/org/apache/cloudstack/storage/dataservice`, `engine/schema/src/main/resources/META-INF/db` | 정확한 table/column migration, 기존 데이터 호환 |
| 제공자 | `plugins/storage/sharedfs/storagevm` | Storage VM/NIC/데이터 볼륨 수명주기 |
| Host Agent | `core`의 StorageService 명령/응답, `plugins/hypervisors/kvm`의 wrapper | QGA 실행, timeout, 전송/검증 및 오류 분류 |
| SystemVM | `systemvm/debian/usr/local/bin/ablestack-storagectl`, runtime updater/boot reconcile 및 systemd units | 원자적 적용, 멱등 재개, 프로토콜 readiness |
| UI | `ui/src/config/section/storage.js`, `SharedFSTab.vue`, `CreateSharedFS.vue`; #924는 `ui/src/config/section/tools.js`와 tools 뷰 | Vue + Ant Design Vue, 라이트/다크, 한국어/영어, 부분 갱신 |
| 검증 | SharedFS/StorageService server tests, KVM wrapper tests, `systemvm/test`, `ui/tests`, SharedFS lifecycle integration smoke | 이슈별 API/DB/runtime/UI/I/O 증거 |

실행 경로는 `Mold UI → Management API → async job → Mold Host Agent → QGA → SystemVM`이다.
사용자 화면과 관리 서버가 guest 명령을 직접 실행하는 경로를 추가하지 않는다.
#911 runtime rollback과 #892 desired-state rollback은 책임을 분리하고 공통 lock/감사를 공유한다.
포맷 timeout 후 실제 장치 상태를 확인하기 전 재포맷하지 않는다.

## 첫 구현 대상 #924

1. Root Admin 전용 `도구 > Storage Service 런타임 번들` 목록·상세·등록·수명주기 UI.
2. artifact/manifest/signature/checksum 격리와 검증, 동일 버전 멱등성과 다른 해시 거절.
3. `REGISTERED → VERIFIED → AVAILABLE` 및 `DISABLED`, `DEPRECATED`, `REVOKED` 전이.
4. 현재 사용 대상·사용 수·감사 이력, 사용 중 물리 삭제 보호.
5. ABI/schema/관리 서버/Agent/template 호환성 평가와 SharedFS 소비 화면 필터링.
6. 기존 AVAILABLE/DISABLED 데이터 migration 및 #911 전송/활성화 계약 보존.
7. 관리자/비관리자 권한, 변조·전이·호환성·재시작 및 라이트/다크 UI 검증 후 이슈별 PR.

## 13번 사전 점검

| 대상 | 결과 |
|---|---|
| 관리 서버 | `10.10.13.10:22`, `mold=active`, UI HTTP 200, 관리자 API 정상 |
| 호스트 | `10.10.13.1:22`, `10.10.13.2:22`, `mold-agent=active`, API Up/Enabled |
| 기존 패키지 | `4.23.0.0-Mold.Europa-202610011115` |
| 기존 설치 코드 | `3db2d1e2e3676d6715c002136d0cdb0d72c7104a` |
| 존/클러스터 | `13-Zone` / `Cluster`, 라우팅 호스트 2개 |
| 기존 SharedFS | `nfs-test`, UUID `487a3d3b-b583-4499-be02-c40b45a7e6b1`, Ready/Running, 100 GiB |
| Storage Service | UUID `1d3f751a-a254-43b7-9336-b34ff9c64625`, Running |
| Guest | `.2`의 `i-2-39-VM`, QGA guest-ping 정상, `10.1.1.9:2049` listener |
| 런타임 코드 | storagectl/updater/boot-reconcile SHA-256 각각 최신 소스와 일치 |
| 번들 카탈로그 | 현재 등록 번들 0개 |
| DB | 버전 4.23.0.0 Complete, 기존 Storage Service instance/protocol/runtime bundle/upgrade 테이블 존재 |

13번 신규 설치에 대한 사용자 확인 후 기존 SSH 지문을 갱신했다. 인증값은 문서나
파일에 저장하지 않는다. 대상은 사용자가 지정한 `.10`, `.1`, `.2`이다.

최신 모듈에는 `cloud.vm_process_profile` 신규 테이블과
`europa-4.23-s12-process-profile-v1` migration 단계가 포함된다.
정확한 schema는 `engine/schema/src/main/resources/META-INF/db/schema-europa-4.23-s12-process-profile.sql`이다.
기존 `DatabaseUpgradeChecker` 시작 경로가 CREATE TABLE IF NOT EXISTS를 수행한다.
SharedFS 데이터 볼륨/guest runtime/template 교체는 이번 모듈 업데이트 대상에 포함하지 않는다.

## 빌드·배포 증거

최종 결과는 같은 경로의 `epic-898-cluster13-preparation-20261006-evidence.md`에 기록한다.
빌드 원본 로그와 산출물은 `/root/work/epic898-preparation`에 보관한다.

이 준비는 미완료 하위 이슈의 구현 또는 4개 프로토콜 전체 I/O·10 TiB 포맷·템플릿 교체
검증을 대체하지 않는다. 해당 검증은 각 이슈의 데이터 보존 및 복구 게이트를 적용해 수행한다.

## 준비 중 발견한 기준선 결함

기존 Epic 27개와 별도로 #1269(SharedFS 초기 조회 범위 변경 후 무한 로딩)를
등록하고, 이슈 브랜치 `sharedfs/issue-1269-initial-loading` 및 PR #1270으로 수정했다.
13번 최종 UI는 upstream 기준선에 이 수정 커밋 `4d21099f6f41ff3d8916d0756cdae0db1a19bb75`를
적용한 별도 검증 산출물이다. backend 기준선은 처음 동기화한 upstream SHA를 유지한다.

#924 UI 구현 전 #1270 리뷰/병합 및 local base 동기화 후 개발 브랜치를 rebase한다.
#1269를 Epic의 기존 기능 목록에 임의 추가하거나 기존 26개 OPEN 기능을 완료 처리하지 않는다.
