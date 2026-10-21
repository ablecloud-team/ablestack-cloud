# VOL-05 (#1427) 할당 정책 수명주기 설계

부모 Epic #1422 · 선행 #1424/#1425/#1426 · 정책 UI #1431 · 최종 통합 회귀 #1434

## 정책과 관측의 경계

할당 계약은 볼륨에 고정된 형식/정책이며 현재 오퍼링 이름과 별개다. 생성·독립 전체 복사의 실제 관측은 해당 시점의 증거다. 오퍼링 변경·게스트 쓰기/discard 뒤의 영구 예약이나 재측정값이 아니다. API allocation.evidencescope=creation-or-copy-time으로 이 범위를 표시한다. 기존 requested/fixed/applied/observed 값은 메타데이터 변경으로 다시 쓰지 않는다.

공통 VolumeAllocationLifecycle은 동결된 볼륨 계약과 대상 오퍼링 계약을 비교한다. 명시적↔legacy, 형식 변경, 정책 변경 및 legacy thin/sparse/fat 간 전환은 실제 변환 엔진이 없으므로 거절한다. 같은 계약 또는 같은 legacy provisioning의 오퍼링 교체는 기존 권한/크기/태그/암호화/스토리지 적합성 검증 뒤 허용한다. 이는 이미지 변환이나 실행 중 VM의 cache/QoS 재설정을 뜻하지 않는다. Cloud IOPS 값만 바꿀 때는 크기가 동일한 명시적 볼륨에 대해 이미지 resize 명령을 보내지 않는다. 해당 QoS의 실행 계층 적용/표시는 기존 경로 및 #1429/#1430의 별도 범위다.

## 지원표

아래 명시적 계약은 GFS2 QCOW2 metadata/falloc/full, GFS2 RAW off/falloc/full, RBD RAW off/full의 8개 backend 조합이다. 암호화·backing 없는 독립 생성/복사라는 선행 조건은 그대로 유지한다.

| 작업 | 명시적 8조합 | legacy | 상태/조건 |
|---|---|---|---|
| 빈 DATA 생성 | 선행 검증된 Agent 경로 허용 | 기존 경로 | 풀/태그/모든 eligible Up host capability 검증 |
| ROOT/템플릿 전체 복사 | 선행 검증된 독립 복사 허용 | 기존 경로 | 생성 정책·형식·크기·데이터·실제 할당 검증 |
| 오퍼링 변경 | 동일 계약·동일 크기만 허용 | 동일 provisioning 허용 | 기존 권한/태그/형식/크기/스토리지 검사 유지 |
| Cloud IOPS 값 변경 | 검증된 생성 증거와 동일 크기일 때 허용 | 기존 경로 | 이미지 resize 없음, 기존 VM 상태/권한/QoS 제한 유지 |
| 논리 크기 확장/축소 | 미지원으로 사전 차단 | 정상 thin 확장을 포함한 기존 검증 경로 | 명시적 Thin도 생성 receipt/실행·결과 검증을 지원하는 resize adapter가 없으므로 legacy fallback 금지 |
| URL/브라우저 업로드 | explicit 오퍼링 거절 | 기존 경로 | 개체 생성/리소스 계산 확정 전 검사 |
| 기존 이미지/VM 임포트 | explicit 오퍼링 거절 | 기존 경로 | 실행하지 않은 할당 증거 부여 금지 |
| 볼륨/VM 스냅샷 생성 | 기존 backend 지원·가드 유지 | 기존 경로 | base 생성 관측의 지속 보장을 추가하지 않음; RAW VM snapshot의 기존 미지원 유지 |
| 스냅샷 복원/revert·VM restore | 사전 차단 | 기존 경로 | source 및 target 동결 계약 확인, state/store 변경 전 재검사 |
| 볼륨/VM pool 이동·attach의 암묵적 이동 | 사전 차단 | 기존 경로 | 선택/backup 준비/복사·state 전 재검사 |
| VM clone/볼륨 duplicate·snapshot clone | 사전 차단 | 기존 경로 | source/target 모두 검사, 새 UUID/볼륨 생성 전 거절 |
| 같은 pool 연결/분리·이름/삭제 보호·삭제/복구 | 기존 경로 유지 | 기존 경로 | 기존 VM/FT/DR/권한/삭제 보호 가드 유지 |
| 관리자 path/pool/chain/state/type 변경 | 변경값이 다르면 차단 | 기존 권한·path 안전 검사 | 기존 증거를 다른 이미지/풀에 붙이는 우회 방지 |

resize/restore/migrate/clone Agent capability는 계속 미지원이다. 이 작업은 검증된 어댑터 없이 활성화하지 않는다. RBD falloc/lazy thick, Full 확장·COW clone·discard 이후 지속 할당을 제공하지 않는다. QEMU/Ceph 명령의 일반적인 존재를 Cloud 수명주기 보장으로 바꾸지 않는다.

## 실행 직전 검사와 실패 보존

API의 오퍼링 비교 뒤 실제 결정된 크기로 검증하고, 큐에서 실행하는 orchestrateResizeVolume에서 볼륨을 재조회해 기대 크기 및 대상 오퍼링 계약을 다시 검사한다. 정책/형식/크기가 바뀐 요청은 상태·오퍼링·관측 변경 전에 실패한다. 독립 StorageService의 copy/migrate/resize/restore/clone 진입에도 검사를 추가하고, orchestration의 move/migrate/duplicate 및 여러 볼륨 이동에서 전체 대상을 backup 준비 전에 검사한다. 불완전한 allocation.* 기록은 legacy로 취급하지 않는다.

saveVolumeDetails는 동결 계약 변경 및 기존 이미지에 새 explicit 계약을 backfill하는 작업을 거절한다. 새 Allocated 볼륨에 생성 의도를 기록하는 선행 동작은 유지한다. DB DDL과 과거 데이터 일괄 변경은 없다. FT/DR 보호를 해제하거나 #952의 보호 계약을 확장하지 않는다.

이 범위는 새 이미지 변환 엔진을 도입하지 않는다. 따라서 기존 생성/복사 어댑터의 소유권·stage·재시도 계약을 유지하며, 수명주기 미지원 요청은 원본/체인/상태/증거를 손대기 전에 거절한다. 실패 응답을 Ready/성공으로 전환하지 않는다.

## 검증

의미 있는 계약 전환·불완전 증거·지연 요청·orchestration 및 StorageService 우회 검사와 기존 볼륨 API 회귀를 수행한다. WSL ext4에서 변경 모듈만 빌드한다. 31/32 전용 자원으로 동일 정책 변경 및 거절·재시도, legacy thin 확장 후 기존 패턴/확장 zero, API/UI·DB·실물 대조와 정리를 수행한다. 기존 운영 자원·FT worktree·WEB-INF·정적 UI·스키마·기존 Agent 배포를 보존한다.

클러스터별 pool이 하나라 실제 다른 pool 이동은 미검증으로 기록한다. 거절 검사나 단위 검사를 실제 pool 이동 PASS로 바꾸지 않는다. 전체 Cloud/Release CI·guest OS workload와 별도 기능 UI 구현을 완료했다고 확대하지 않는다.

기술 근거: [QEMU qemu-img](https://www.qemu.org/docs/master/tools/qemu-img.html), [Ceph Reef rbd](https://docs.ceph.com/en/reef/man/8/rbd/). 실제 지원 판단은 선행 검증 및 배포 환경의 실행·거절 결과를 우선한다.

## GFS2 검증 명령의 오류 전달 보완

배포 전 시험에서 생성 비교 명령의 비정상 종료가 Script의 배출/오류 reader 경로에서 Stream closed로 가려졌다. GFS2 할당 어댑터에 한해 직접 인자 배열·통합 출력 파일·제한 시간 기반 실행기로 바꿔 실제 exit code와 출력을 보존한다. 출력은 1 MiB로 제한해 읽고 성공/실패/시간 초과 모두 전용 임시 파일을 정리한다. 전역 Script와 RBD/FTCTL 명령 실행기는 바꾸지 않는다. 이 변경만으로 원래 비교 실패 원인이 해결됐다고 판단하지 않으며 최종 Agent에서 실제 원인과 새 개체 결과를 검증한다.
