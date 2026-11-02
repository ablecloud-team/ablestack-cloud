# #1458 구현 착수 후 사전 SystemVM 시험 중단

작성: 2026-11-02. **제품 후보 코드 오류로 중단. 배포·제품 UI 인수·이슈 종료 없음.**

## 진행 범위

- local Europa를 upstream/origin의 `e54c9bb4fa5`로 갱신한 후 Epic 브랜치를 rebase했다. VM 표준 클래스와 runtime-catalog 클래스의 한 충돌은 두 기능을 보존했다.
- SharedFS capacity DTO·단건 details 옵션, DATA batch DAO 조회, SharedFS 응답 집계·bounded collector/cache, monitor/CLI 읽기 경로의 초안을 작성했다.
- **Maven server -am compile 26모듈 성공**. test/package는 하지 않았다. checkstyle/rat/license skip을 사용했다.
- 제품 UI 구현·UI 빌드·API/DAO 회귀·13번 제품 배포·새 Cloud 자원 준비·NFS/SMB 클라이언트 인수는 수행하지 않았다.
- 실패한 첫 native 사전 시험에서 중단했으며 기능 재시도나 guard 완화는 하지 않았다. 이어서 원인 확인용 read-only helper 진단만 수행했다.

## 정확한 오류

실제 VM d9c423e9-ce89-3ba9-9cd8-f48cdb791da2의 기존 10GiB **SPARSE XFS DATA**를 대상으로, 후보 CLI/monitor를 /run 전용 복사본·전용 cache로 실행했다. 활성 release에는 설치하지 않았다.

후보 monitor는 capacitySnapshotVersion=1 cache를 생성했지만 후보 CLI inventory는 실제 DATA를 아래처럼 반환했다.

~~~json
{"mappingStatus":"UNMAPPED","status":"UNAVAILABLE","volumeUuid":"e070a0aa-dbf5-48c0-83e0-09f874446277","filesystems":[]}
~~~

조회 장치는 /dev/sdb, serial=e070a0aadbf548c083e0, XFS UUID=9c8ddfe9-3e23-46f0-9476-5c794b004841이다. 실제 mountpoint는 /srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277이다.

### 발생 코드 경로

1. 새 monitor는 lsblk의 **MOUNTPOINT를 포함**해 blockDevices를 cache에 저장한다.
2. 새 CLI `runtime_capacity_inventory()`는 기존 `resolve_volume_device(..., expected_size=..., allow_blank_size_fallback=False)`를 호출하면서 **mount_source를 전달하지 않았다**.
3. 기존 `volume_identity.safe_candidate()`는 mountpoint가 존재하는 장치를, expected_mount_source가 없으면 제외한다. 이 보호는 다른 장치를 임의로 선택하지 않기 위한 정상 동작이다.
4. 결과적으로 후보 CLI가 정상 마운트된 실제 DATA를 UNMAPPED로 분류했다. 기존 helper, 실제 DATA 또는 서비스 장애가 아니다.

### 이전 설계 실증의 한계 정정

이전 #1458 설계의 lsblk 자료는 MOUNTPOINT 필드를 포함하지 않았다. 따라서 기존 helper의 ‘이미 마운트된 장치에는 source 확인이 필요하다’는 분기를 검증하지 못했다. 기존 설계 기록의 8개 격리 FS 시험과 5개 CLI 계약 결과 자체를 다시 통과했다고 주장하지 않으며, 이 조건에 대한 실증은 불충분했음을 명시한다.

## 원인 진단 결과

실제 SystemVM에서 활성 helper를 **읽기 전용으로만 호출**해 비교했다. 후보 monitor/CLI 기능을 다시 실행하거나 서비스 구성을 변경하지 않았다.

| 진단 | 실제 결과 |
|---|---|
| mountpoint 포함 + mount_source 생략 | REJECTED — 첫 실패와 같은 경로 |
| 같은 입력 + 실제 source /dev/sdb | RESOLVED / VOLUME_SERIAL |
| source는 같지만 잘못된 볼륨 UUID | REJECTED / identity mismatch |
| mountpoint 필드만 제외한 옛 시험 자료 | RESOLVED — 옛 실증에서 누락된 조건 입증 |
| ROOT의 serial + ROOT source | REJECTED — 기존 ROOT 보호 유지 |

[진단 원문](evidence/diagnosis-summary.json), [진단 스크립트](diagnose-mapping.py), [첫 후보 시험 결과](evidence/native-candidate-result.json).

## 최소 수정 방향 — 아직 적용하지 않음

- 현재 cache snapshot에서 기대 UUID/serial과 일치하는 DATA 후보를 하나로 제한한다.
- 해당 장치의 관측 mount source를 FS UUID·device/MAJ:MIN·cache 관측 시각/boot identity와 교차 확인한다.
- 검증된 source를 기존 helper의 mount_source 인자로 전달한다. 이후 expected size·ROOT 제외·UUID/serial·별칭 FS 중복 제거 보호를 유지한다.
- mountpoint 필드를 제거하거나 `safe_candidate()` 보호를 완화하는 방법은 사용하지 않는다.
- MOUNTPOINT가 포함된 실제 lsblk 자료와 실제 bind mount를 사용해 정상 mounted DATA·잘못된 UUID·ROOT·size mismatch·stale·0·일반 inventory 회귀를 보완한다.
- 사전 SystemVM 시험 통과 후에만 남은 API/DAO/UI 구현·모듈 package·13번 배포·실제 UI/클라이언트 검증을 재개한다.

## 환경·소스 상태

- 활성 CLI SHA256: 3854f98631ad075698e67c0068c3ca5ca002f4ffe2038342a26e6159b8718995.
- 활성 monitor SHA256: 896b1ea72e6fcbd9cea383c67ccea96b70486acb359eb0a511268bda19a4e4a6.
- 위 두 파일은 전후 동일하다. 기존 DATA 포맷·mount·권한·공유 구성·MAC/IP 변경은 0이다.
- 사전 시험은 /run 임시 복사본과 cache만 만들었고 finally에서 전용 디렉터리를 정리했다. 새 Cloud VM/DATA/클라이언트는 생성하지 않았다.
- 소스 초안과 진단 근거는 로컬 Epic 브랜치의 WIP checkpoint로 보존한다. 이 구현을 origin/Epic PR에 게시하거나 배포하지 않는다.
- #1458 OPEN/인수0/4. 사용자의 다음 지시를 기다린다.
