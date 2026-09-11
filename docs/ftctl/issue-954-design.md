# #954 대상 컴퓨트 사양 코드 수준 설계

기준 Cloud `c117226bc8` (#980 포함), 안전 브랜치 `codex/fix-954-target-compute-sizing`. qemu `fb6df9b` 계약을 재사용하며 변경 필요가 없으면 별도 qemu 브랜치/빌드는 만들지 않는다.

## 원인과 변경
1. `DrPlanList.vue`: targetcpunumber/targetcpuspeed/targetmemory를 전송하지만 입력 컨트롤이 없다. 선택 오퍼링 inventory의 cpu/speed/memoryMb 및 requires*와 min/max를 사용하여 CPU 수, CPU 속도(MHz), 메모리(MiB)를 표시한다. 고정 필드는 읽기 전용, 가변 필드만 입력 가능하다. 필드별 정수/범위 오류를 표시한다.
2. `utils/dr/planForm.js`: 고정 오퍼링 값 > 기존 사용자/저장 값 > 최초 원본 초기값 순서로 정한다. 원본 재조회는 사용자 입력을 덮어쓰지 않는다. 명시적 오퍼링 전환만 이전 값을 초기화한다. 원본에 CPU 속도가 없으면 임의의 호스트 속도를 숨겨 넣지 않고 명시 입력하도록 한다.
3. `DrPlanTargetPlacementResolverImpl.resolveComputeSizing`: 고정 필드는 offering 값을 우선하며 dynamic 필드는 명시 요청값을 검증한다. CPU 속도가 미지정된 dynamic offering의 worker CPU fallback을 제거하여 미리보기/저장/실제 배치 간 사양이 달라지지 않도록 한다. 기존 positive/range validation과 active/non-system offering 검증을 재사용한다. 대상 계정 접근은 Cloud offering 접근 검사, 실제 자원 quota/capacity는 기존 VM 생성 admission 경로로 확인한다. Plan 생성으로 자원을 예약했다고 주장하지 않는다.
4. 기존 `DrPlanGuidedSpecBuilder`가 nested target와 호환 top-level 값에 동일 resolved 사양을 저장한다. 기존 Plan은 저장값을 유지하고 값이 비어 있는 사용자 정의 필드는 편집/미리보기에서 보완한다. source live lookup을 재해 failover의 필수 조건으로 추가하지 않는다.

## 검증
UI helper 정수/범위/부분 고정/오퍼링 전환/원본 재조회 회귀 및 DR Maven 모듈 빌드를 WSL ext4에서 수행한다. 31/32 기존 WEB-INF를 보존하여 UI static만 배포하고 필요한 관리 클래스만 교체한다. 실제 UI에서 고정 및 사용자 정의 오퍼링의 값 표시, 잘못된 입력 거부, 원본보다 작은/큰 값 저장·재조회·편집을 검증한다. VMware 원본 전원/QGA 조건을 추가하지 않는다. 실행별 override 및 기존 replica 재사이징은 #955 범위로 유지한다.

## 구현 중 확정한 API/운영 정책
- `DrPlanGuidedSpecBuilder.applyIfRequested`는 명시적인 비정상 값/범위 오류(`TARGET_COMPUTE_SIZE_INVALID`)를 저장 전에 거절한다. 미입력 값만 있는 기존 초안의 보완 가능성은 유지하며, 실행은 기존 readiness 조건을 통과해야 한다.
- 사양 API의 정수형 및 resolver min/max 검증을 재사용한다. 고정 사양은 입력값보다 오퍼링의 값이 우선이다.
- UI 배포는 WEB-INF뿐 아니라 `/etc/cloudstack/management/config.json`으로 연결된 기존 config.json 심볼릭 링크도 보존한다. 이전 시험 JAR 백업의 별도 보관은 SHA256 검증 후 수행하고 현재 동작 JAR는 삭제하지 않는다.

## #954 실제 UI 편집에서 발견한 설정 유실 및 추가 수정

32번 UI에서 정지된 VMware R10-EFI-LEGACY-01(vm-4366)을 대상으로 사용자 정의 오퍼링의 1 CPU / 2000 MHz / 2048 MiB 계획 생성은 성공했다. 그러나 CPU=4, 메모리=8192만 편집 저장하면 변경되지 않은 CPU speed와 offering/disk/network 필드가 빠졌다. 최초 시험 Plan54는 실패 재현이며 PASS에 합산하지 않는다. UI 일반 삭제로 시험 초안을 정리했다.

원인은 UpdateDrPlanCmd.applyGuidedSpec가 변경 필드만 포함된 spec으로 전체 mapping/schedule/policy를 재생성하는 경로다. 기존 저장 spec에 명시된 변경값을 합치고, generated JSON과 기존 JSON을 병합하여 비변경 확장 필드도 보존하도록 수정했다. DrPlanReadinessValidator의 기존 mapping→spec 해석을 재사용하며, 사양 편집 때문에 기존 디스크/네트워크/정책을 잃지 않는 회귀를 추가했다. #954의 저장·재조회·편집 보존 범위에서 처리하며 별도 중복 이슈로 유예하지 않는다. 최종 배포 후 UI 재검증 결과를 이어서 기록한다.
