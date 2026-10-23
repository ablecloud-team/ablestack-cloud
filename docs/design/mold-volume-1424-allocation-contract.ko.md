# VOL-02: 볼륨 할당 정책 공통 계약 v1

대상: Epic #1422 / #1424. 기존 `provisioningtype`의 의미를 유지하면서 #1425(GFS2), #1426(RBD), #1427(수명주기), #1431(UI)이 공유할 요청·응답·지원 조회 계약을 제공한다.

## 요청과 호환성

`createDiskOffering`, `createServiceOffering`에 다음 선택 파라미터를 추가한다.

| 필드 | 값 | 규칙 |
|---|---|---|
| allocationpolicy | off / metadata / falloc / full | allocationformat과 함께 지정 |
| allocationformat | qcow2 / raw | raw + metadata는 잘못된 파라미터 |
| provisioningtype | thin / sparse / fat | 신규 두 필드와 동시 지정 금지. 생략 시 기존 thin 기본값 유지 |

신규 문자열은 `provisioningtype`에 추가하지 않는다. 기존 thin→off, sparse→metadata, fat→full의 기존 QEMU 해석을 바꾸지 않는다. 특히 sparse를 falloc으로 변환하지 않는다. 잘못된 기존 문자열도 `NotImplementedException`/HTTP 530 대신 파라미터 오류를 반환한다.

구 DB 열은 그대로 유지한다. 명시적 신규 정책의 호환 열 값은 off=thin, metadata=sparse, falloc/full=fat이다. 이 열은 신규 정책의 실행 명령이나 적용 증거가 아니다. 신규 계약이 있는 오퍼링을 기존 실행 경로로 넘기지 않는 차단이 반드시 함께 배포되어야 한다. 구 관리 서버와 혼용하거나 차단만 롤백한 상태로 신규 오퍼링을 사용해서는 안 된다.

## DB와 업그레이드

기존 `disk_offering_details`를 사용한다. DDL, SQL view 변경, 기존 행 백필이 없으므로 같은 버전 업그레이드에도 기존 provision 값·볼륨·오퍼링을 수정하지 않는다. 신규 생성 요청에만 아래 세 행을 저장한다.

| name | value |
|---|---|
| allocation.contract.version | 1 |
| allocation.requested.policy | 정규화한 정책 |
| allocation.requested.format | 정규화한 형식 |

서비스 오퍼링에 내부 디스크가 있으면 같은 세 행을 내부 `disk_offering_details`에 저장한다. 연결 디스크가 있으면 해당 디스크의 행을 읽고 연결 대상 자체를 수정하지 않는다. 일반 메타데이터 API로 `allocation.*`를 추가·수정·삭제하거나 전체 삭제로 지우는 것을 금지한다. 공개 create API의 details 파라미터로도 예약 이름을 우회 입력할 수 없다.

과거 행에 신규 계약이 없으면 `legacy`이다. 버전만 있거나 정책/형식이 일부 누락되거나 모르는 버전이면 손상된 계약으로 거절한다. 부분 레코드를 legacy로 취급하지 않는다. 롤백은 신규 명시적 오퍼링의 비활성화·사용 여부 확인 후 진행하며 기존 행의 의미를 변환하지 않는다.

볼륨에 확정할 요청 스냅샷과 실제 적용 증거는 백엔드 성공 이후 저장해야 한다. 공통 `VolumeAllocationPolicy.evidence()`가 요청·형식·작업·방법 조합을 검증하고 저장할 map을 만들며, `describe()`가 이를 검증해 응답한다. `volume_details` 저장 키는 위 요청 3개와 `allocation.fixed.policy`, `allocation.evidence.version=1`, `allocation.applied.backend`, `allocation.applied.operation`, `allocation.applied.method`이다. 선택 관측값은 `allocation.observed.bytes`(음수 금지), `allocation.observed.at`(UTC Instant), `allocation.observed.source`이며 셋을 함께 제공한다. 요청 map을 수정하지 않고 별도 스냅샷을 반환한다. 손상된 증거나 실제 형식 불일치는 effectivepolicy=unknown/evidence=invalid로 표시한다.

이 단계는 성공한 신규 실행이 없으므로 실제 볼륨에 Full이나 falloc을 기록하지 않는다. #1425/#1426은 성공 콜백에서 이 공통 evidence 계약을 이용해 저장하는 구현을 추가해야 한다. 수명주기 동작에서는 원본 증거를 복사해서 성공으로 표시하면 안 된다.

## 공통 resolver와 우선순위

`VolumeAllocationPolicy`가 파라미터 정규화·저장 계약 검증·연결 디스크 상속·지원 조합 검증을 담당한다.

1. 일반 데이터 디스크는 선택한 디스크 오퍼링의 계약을 사용한다.
2. 서비스 오퍼링의 내부 디스크(compute-only disk offering)는 서비스 생성 요청의 디스크 계약을 사용한다.
3. 외부 디스크 오퍼링을 연결한 서비스 오퍼링은 연결 디스크의 계약을 사용한다. 서비스에 신규 정책을 함께 지정하면 동일한 값이어도 모호한 중복으로 거절한다. 명시적 정책이 있는 디스크를 연결하면서 구 provisioningtype도 명시하면 충돌로 거절한다.
4. 명시적 신규 계약이 없으면 기존 provision 경로를 유지한다. 기존 디스크를 새 정책으로 자동 전환하지 않는다.
5. VM의 공통 디스크 크기 검증에서도 정책을 먼저 검사한다. 루트 디스크의 미지원 요청을 VM/NIC DB 생성 전에 거절하여 Error VM이 남지 않게 한다. ROOT/DATADISK의 raw/템플릿 생성, 업로드 루트, import, duplicate 경로에도 검증을 유지한다. API의 디스크 오퍼링 접근 검사로 생성·오퍼링 변경·확장·스토리지 선택을 차단한다. 신규 정책 오퍼링의 clone은 후속 수명주기 구현 전까지 거절한다.

## 조회 응답

디스크·서비스 오퍼링 및 볼륨 응답에 `allocation` 객체를 추가한다.

| 키 | 현재 의미 |
|---|---|
| contractversion | legacy 또는 1 |
| requestedpolicy | legacy의 thin/sparse/fat 또는 명시적 새 정책 |
| requestedformat | 명시적 형식 또는 unspecified |
| policyorigin | legacy / explicit |
| fixedpolicy | unknown: 볼륨에서 확정한 새 정책 증거 없음 |
| effectivepolicy | unknown: 실제 적용 성공 증거 없음 |
| appliedmethod | unknown: 실제 실행 방식 증거 없음 |
| actualformat | 볼륨에 이미 저장된 형식 또는 unknown |
| evidence | unverified |

오퍼링의 요청과 볼륨의 적용 결과를 구분한다. 특히 RBD의 과거 fat/sparse 값이나 형식만으로 실제 Full이라고 표시하지 않는다. 기존 `usedphysicalsize`, `usedfsbytes` 등 사용량 응답도 할당 정책 증거로 쓰지 않는다. 관측 바이트 수와 관측 시각은 후속 Agent 계측에서 출처와 함께 제공하며, 복제본을 포함한 클러스터 전체 물리 사용량으로 해석하지 않는다.

## Agent capability v1

관리자 API `getVolumeAllocationCapabilities(storageid, hostid, allocationformat, operation[, allocationpolicy])`를 추가한다. host/pool 연결 관계와 KVM/Up 상태를 검사한다. 작업은 create/copy/resize/restore/migrate/clone이다.

JSON 응답 본문은 `volumeallocationcapability` 객체를 사용한다. 실제 운영 Spring XML에 API 제공자를 등록하고, 빈 주입·명령 발견과 실제 API 직렬화의 객체 이름/false 지원 값을 회귀 검사한다.

Agent는 시작 시 host detail `volume.allocation.capability.version=1`을 광고해야 한다. 광고가 없는 구 Agent에는 새 Command를 보내지 않는다. 광고가 있으면 `GetVolumeAllocationCapabilitiesCommand`에 버전·pool UUID·형식·작업을 담아 질의한다. Answer는 동일 범위와 버전, 실제 런타임에서 확인한 backend(GFS2/RBD), 정책→적용 방식 map을 반환해야 한다. 저장소 이름이나 태그가 GFS2라는 이유로 지원을 추정하지 않는다.

| 백엔드/형식 | 허용 선언 | 적용 방식 식별자 |
|---|---|---|
| GFS2 qcow2 | metadata / falloc / full | qemu-preallocation-metadata / -falloc / -full |
| GFS2 raw | off / falloc / full | qemu-preallocation-off / -falloc / -full |
| RBD raw create | off / 검증된 full | rbd-thin / rbd-thick-create |
| RBD raw copy | off / 검증된 full | rbd-thin / rbd-thick-convert-n-S0 |
| RBD raw 기타 작업 | off 선언만 허용 | rbd-thin; Full은 후속 검증 전 거절 |

허용 식별자는 지원을 자동 부여하는 목록이 아니다. 실제 Agent 선언과 개별 실행 어댑터 검증이 모두 필요하다. RBD의 falloc/lazy thick, raw literal metadata, Full resize/clone 등을 만들어내지 않는다. thick create와 미리 생성한 대상에 `qemu-img convert -n -S 0`으로 쓰는 copy는 기존 실험 증거가 있는 경로이며 #1426에서 제품 동작으로 연결하고 재검증해야 한다. discard/COW 이후 영구 예약이나 복제 물리 용량 보장은 포함하지 않는다.

응답에는 `supported`, `reason`, `agentpolicies`가 있다. 현재 실행 어댑터가 미구현이므로 supported는 false이다. 정상 Agent 선언을 받아도 `ALLOCATION_EXECUTION_NOT_IMPLEMENTED`이며 선언은 agentpolicies로만 구분한다. 실패 코드는 `AGENT_CAPABILITY_NOT_ADVERTISED`, `HOST_NOT_AVAILABLE`, `CAPABILITY_QUERY_FAILED`, `INVALID_AGENT_CAPABILITY`이다. null/일반 Answer/실패/잘못된 범위·방법·버전/조회 예외는 지원으로 바꾸지 않는다. 미지원 요청은 생성 성공으로 표시하거나 thin으로 대체하지 않는다.

## 후속 구현과 종료 기준

- #1425: 실제 GFS2 파일시스템·QEMU 기능 probe, 생성/복사 적용, 실패 정리, 볼륨 요청 고정·적용 증거 기록.
- #1426: RBD Thin/Full create/copy만 검증된 방법으로 구현, 미지원 수명주기 차단과 사용량 관측 계약 확정.
- #1427: 확장/복원/변경/이동/복제마다 정책 보존·재적용·거절 계약 및 실제 데이터 검증.
- #1431: 새 필드와 capability를 사용한 선택 UI, 요청/확정/실제/관측의 분리 표시. VM UI 표준은 #1433에서 최종 통일.
- #1434: 31·32 최종 통합 행렬과 Epic 종료. #1424의 공통 계약 검증을 백엔드 생성 성공으로 확대 보고하지 않는다.

공식 근거: [QEMU 이미지 형식별 preallocation](https://www.qemu.org/docs/master/system/images.html), [Ceph Reef rbd create/thick provisioning](https://docs.ceph.com/en/reef/man/8/rbd/). 기존 실제 스토리지 실험은 Epic #1422의 감사 보고서를 참조한다.
