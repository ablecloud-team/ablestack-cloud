# #1479 알림 통합 및 공통 작업 상세 설계

## 화면 계약

상세 화면 위에 표시하던 `StorageSnapshotOperations` 패널을 제거한다. 가상머신 상세의 `ResourceLayout` / `InfoCard` / `ResourceView`와 공통 도구 모음, 기존 상세·Zone·이벤트·코멘트 탭을 그대로 사용한다. ResourceLayout/ResourceView/InfoCard의 표준 자체는 수정하지 않는다.

기존 HeaderNotice 알림에 현재 보안 범위의 스냅샷 작업을 합친다. 작업 이름은 현지화하고 대상·상태·지역 날짜/시간을 표시한다. 선택한 작업 한 개만 공통 MoldDialog(md)에서 연다. UUID와 기존 작업 연결/차단 해제는 접힌 고급 영역으로 이동한다. 결과 리소스·기존 이벤트 화면으로 연결한다. 대상은 제출 당시 registry target을 사용하며 현재 페이지의 리소스로 치환하지 않는다.

## 안전 계약

- registry/tracker를 유일한 작업 추적기로 유지한다. 스냅샷 알림은 일반 `$pollJob`으로 보내지 않는다.
- 확인은 기존 job UUID 또는 제출 전에 발급된 customjobid를 조회한다. 생성·삭제·되돌리기 POST를 다시 제출하지 않는다.
- 알림 목록 지우기는 종결 항목만 숨긴다. checking/submitting/accepted/unknown은 잠금과 함께 보존한다. 과거에 숨긴 항목도 미해결 상태라면 표시한다. 일반 알림의 unknown도 지우지 않는다.
- F5 후 결과 본문이 없는 완료 항목을 열면 같은 job을 읽어 결과 링크를 복원한다.
- 조회 실패·대화상자 닫기는 lease를 해제하지 않는다. 수동 연결은 UUID/대상 검증 및 명시적 확인을, 미접수 요청 차단 해제는 기존 이력 확인과 명시적 동의를 유지한다.
- 계정·프로젝트·세션이 달라지면 선택 대화상자를 닫고 다른 범위의 항목을 숨긴다. 초기 로그인 복원에서는 hydrated registry를 삭제하지 않는 기존 계약을 유지한다.
- 강제 삭제에서 DB 정리 완료와 물리 잔여 가능성을 구분한다.
- 작업 종결 시 현재 목록/상세가 영향을 받는 snapshot/volume일 때만 `irefresh` 한다. 다른 VM/리소스 페이지를 갱신하지 않고 활성 탭을 보존한다.
- 공통 모달의 키보드/ESC/초점 처리를 재사용하고 닫은 뒤 알림 버튼으로 초점을 돌린다.

## 검증 계획

기존 129개 집중 회귀와 신규 알림·대화상자 테스트를 함께 실행한다. UI production build/no-fix lint 후 31(GFS2)·32(RBD)에 static manifest만 배포하고 WEB-INF/config/backend JAR 보존, client HTTP 200, Mold active 및 FTCTL 기존 마커를 확인한다.

`SNAP1479UI-31/32-*` 전용 자원으로 제출 응답 유실·조회 실패, 알림 지우기/F5, 원래 대상 조회/재제출 차단, 프로젝트·경로 전환, 최신 상태 재검증을 실제 UI에서 확인한다. 같은 customjobid의 DB job/resource 개수와 GFS2/RBD 복구 볼륨 데이터 패턴을 대조한다. 기본 상세와 공통 대화상자를 밝은/어두운 테마와 반응형 크기에서 관찰한다. 활성 fixture·참조·알려진 실물을 정리하고 운영 자원 및 보호된 dirty FTCTL worktree를 전후 대조한다.

전체 Cloud 빌드/Actions dispatch와 PR 병합은 이번 작업에 포함하지 않는다. #1489의 기존 삭제 잔여 결함은 별도 미완료 항목이며 수동 시험 정리를 제품 자동 복구 성공으로 보고하지 않는다.
