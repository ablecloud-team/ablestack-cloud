# #971 / PR #983 통합 검증

## 병합과 중복 방지

PR #983은 2026-09-10 13:32:21 KST에 ablestack-europa로 병합되었다. 실제 병합 커밋은 `014895d8f3dc2f062f379b51ee62d36a0adae88a`이다. 현재 작업 브랜치와 git merge-tree 검사 및 실제 병합 모두 충돌 없이 완료했다. 통합 커밋은 `10f9e78019`이다.

#971은 DR 모듈/API/UI를 수정한다. #983의 5개 KVM VIF 드라이버 수정은 원본 커밋을 병합하여 사용하며 재구현하지 않았다. #982는 이 PR로 코드 수정이 병합되어 종료했다. #981은 DR 테스트 VM 생성 시 NIC 유지와 명시적 비활성화라는 별도 역할이므로 유지한다. 두 값(enabled/link_state)을 false로 맞춘 뒤 시작하므로 #983의 linkState 기준 동작과 일치한다.

## 빌드와 배포

- DR 모듈: 442 tests, failures/errors 0.
- #983 포함 KVM 모듈: 729 tests, failures/errors 0, skipped 3.
- 31/32 관리 서버: DR 변경 클래스 배포 후 mold active, /client HTTP 200, WEB-INF 보존.
- 31.1~3 / 32.1~3: #983의 Bridge/Direct/Ivs/Ovs/VRouter 클래스 배포. 기존 JAR 백업 및 선택된 클래스 외의 내용 보존 검증. VM UUID 목록, agent.properties 해시 유지, mold-agent active.
- 32에서는 Agent stop이 외부 재시작과 충돌하여 취소되었다. 배포 중에만 runtime mask를 적용했으며 완료 후 모두 unmask했다.
- #983의 PR CI에는 RAT/pre-commit 실패와 진행 중 작업이 있어 전체 CI PASS로 판단하지 않는다. 통합 모듈 검증 결과와 구분한다.

## 원본 단절 중 통합 기능 검증

장애 주입은 대상 관리 서버에서 원본 Mold TCP 8080 연결만 차단했다. source VM이나 전체 사이트 전원 장애 시험과 동일시하지 않는다. 자동 해제 타이머를 설정했다.

| 경로 | Run | 테스트 VM | 결과 |
|---|---|---|---|
| qcow2 → qcow2, 13 → 31 | 315 / 6341ecbe-9609-4618-81b6-7d8ed1ec6446 | i-2-260-VM | 원본 단절 중 SUCCEEDED, QGA_VALIDATED |
| RBD → RBD, 22 → 32 | 424 / 5c94dc29-ecc6-4e9e-9ac4-b1545d61550f | i-2-305-VM | 원본 단절 중 SUCCEEDED, QGA_VALIDATED |

두 VM 모두 NIC가 존재하고 DB enabled=false/link_state=false이며, 실제 libvirt XML에서도 link state=down을 확인했다. qcow2는 virtio, RBD는 e1000 모델이다. NIC 삭제나 수동 링크 교정 없이 통과했다.

정리 단계에서 DB getDrPlan 응답은 stopTestFailover.enabled=true이지만 오래된 보호 스냅샷이 UI 버튼을 disabled로 덮어쓰는 별도 문제가 재현되어 #985로 등록했다. fetchProtectionView가 최신 DB 계획 응답을 함께 읽고 작업 가능 상태를 최종 병합하도록 수정하여 재검증 중이다. 전체 #971 완료 판정과 별개로 기록한다.