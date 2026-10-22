# Epic #1422 다음 작업

#1423과 #1424의 구현·검증 결과는 누적 Draft PR #1435에 포함한다. Epic 브랜치 `dhslove/epic-1422-volume-offerings`를 계속 사용하며, 개별 이슈마다 별도 PR을 만들지 않는다.

| 순서 | 이슈 | 진행할 내용 | 완료 증거 |
|---|---|---|---|
| 1 · P1 | [#1426 / VOL-04](https://github.com/ablecloud-team/ablestack-cloud/issues/1426) | RBD Agent 광고·실제 probe, raw Thin과 검증된 Full create/copy 어댑터, 성공 증거 기록·실패 정리 | 32번의 실제 이미지 크기·할당량·zero/기존 데이터 비교, 미지원 연산 거절, 정리 0 |
| 2 · P2 | [#1425 / VOL-03](https://github.com/ablecloud-team/ablestack-cloud/issues/1425) | 실제 GFS2/QEMU probe와 qcow2 metadata/falloc/full, raw off/falloc/full 적용 | 31번의 qemu 정보·할당량·zero/복사 비교와 ROOT/DATA 경로 |
| 3 · P2 | [#1431 / VOL-09](https://github.com/ablecloud-team/ablestack-cloud/issues/1431) | 새 API 필드와 capability를 사용하는 정책 선택 UI, 요청/확정/적용/관측 분리 표시 | 지원 여부·이유·형식·작업별 선택 제약과 구 Agent/조회 실패 UI; 실제 생성 성공 검증은 1·2 이후 |
| 4 · P2 | [#1427 / VOL-05](https://github.com/ablecloud-team/ablestack-cloud/issues/1427) | 오퍼링 변경·확장·복원·이동·복제의 정책 보존/재적용/거절, 증거 무효화·갱신 | 양쪽 환경의 실제 데이터와 실패 시 원본 보존; 검증되지 않은 RBD Full 연산은 계속 거절 |
| 5 · P2 | [#1433 / VOL-11](https://github.com/ablecloud-team/ablestack-cloud/issues/1433) | 기능 이슈 완료 후 볼륨/컴퓨트·디스크 오퍼링을 VM 목록·상세·탭·탭 콘텐츠·대화상자 기준으로 통일 | 상태·로딩·실패 보존·재시도·버튼 순서·빈 참조 링크·권한별 UI 행렬 |
| 6 · P1 | [#1434 / VOL-12](https://github.com/ablecloud-team/ablestack-cloud/issues/1434) | 31·32 최종 통합 검증, 잔여 API/UI 매핑과 정리, 누적 PR 검토 및 Epic 종료 | 모든 필수 하위 이슈와 최종 소스·배포·실물 증거 일치 |

#1425와 #1426은 공통 계약 위에서 독립적으로 착수할 수 있다. 우선순위가 높은 RBD를 먼저 진행하는 순서를 권장한다. #1431은 API 연결을 먼저 시작할 수 있지만 backend 성공까지 완료한 것으로 보고하면 안 된다.

별도로 #1428(편집·삭제 보호·권한), #1429(IOPS/확장 UI), #1430(오퍼링 cache/QoS), #1432(API/UI 잔여 매핑), 기존 #1154/#1209/#1210도 Epic 선행 관계에 따라 진행한다. #1433과 #1434 전에 이 범위를 빠뜨리지 않는다.

후속 구현 시 지킬 계약:

- 구 thin/sparse/fat 열을 새 정책의 적용 증거로 사용하지 않는다. sparse→falloc 일괄 변환을 하지 않는다.
- Agent 선언·형식·작업·방법과 실행 어댑터가 모두 확인돼야 신규 정책 실행을 연다. 저장소 이름이나 UI 선택만으로 지원을 표시하지 않는다.
- RBD lazy thick/falloc, Full resize/clone/restore, discard 이후 영구 예약 등의 지원을 추정하지 않는다. 실제로 확인한 방법만 제시하고 검증 전에는 차단한다.
- 성공한 새 볼륨에는 요청 스냅샷과 공통 evidence 계약을 사용한다. 수명주기에서는 이전 증거를 그대로 복사해 성공으로 표시하지 않는다.
- VM/NIC/볼륨 DB 생성 전 거절 가능한 요청은 먼저 검증하고, 실패 후 활성 자원이나 이미지를 남기지 않는다.
- 전체 Cloud 빌드는 사용자 요청이 있을 때 GitHub Actions로 실행한다. 현재 모듈/관련 검사 결과를 전체 CI 성공으로 바꾸어 보고하지 않는다.
