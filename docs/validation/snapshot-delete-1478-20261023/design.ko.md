# #1478 스냅샷 삭제 종결 및 재시도 설계

기준: Europa a7543feb62a728a5a806283b1e0e6134a7305260. 구현 브랜치: dhslove/epic-1473-storage-snapshots.

## 원인과 책임

같은 snapshot/store/role에 중복 참조가 있으면 SnapshotDataFactory는 여러 객체를 만들지만 각 객체의 경로와 상태 갱신은 findByStoreSnapshot의 첫 행을 다시 선택한다. 다른 참조는 Destroying에 남을 수 있다. 상위 Destroyed 상태는 재삭제를 거부한다. DefaultSnapshotStrategy는 일부 성공을 전체 성공으로 합산하고, SnapshotManager의 계수 감소 블록은 중복돼 있다.

#1477의 보관 중복 생성 방지는 별도 범위로 유지한다. 이번 수정은 기존 중복·누락 파일·부분 실패·전역 Destroyed 잔여를 안전하게 삭제한다.

## 구현 계약

1. 삭제용 SnapshotInfo에 선택된 참조 ID를 고정한다. 저장소 상태 머신은 snapshot/store/role과 참조 ID를 검증하고 정확한 행을 갱신한다. 다른 경로와 소유자의 참조를 선택하지 않는다.
2. Default 전략은 모든 선택 참조의 결과를 합산하고 마지막에 미종결 참조를 재조회한다. 예외나 일부 실패는 성공이 아니다. 참조 체인을 유지하기 위한 기존 Hidden 의미는 보존한다.
3. Destroyed 전역 상태여도 ACL·Zone·백업/사용 제약을 확인한 뒤 남은 참조를 처리한다. 이미 종결된 대상은 무변경 성공한다. 동시 삭제는 snapshot별 분산 잠금으로 직렬화하고 잠금은 finally에서 해제한다.
4. 생성 경로의 논리 크기 예약/차감과 표준 Secondary 재계산의 물리 크기 기준을 구분한다. 삭제 시 차감량을 추정하지 않고 해당 소유자의 snapshot/secondary_storage 두 계수를 표준 재계산 서비스로 대조한다. 부분 실패·재시도·중복 요청도 같은 기준을 사용한다. 다른 종류의 자원 계수는 변경하지 않는다. annotation은 실제 종결 후 정리하고 이미 숨겨진 과거 잔여의 usage 삭제 이벤트를 중복 발행하지 않는다.
5. deleteSnapshot(dryrun=true)은 변경 없이 대상 이름/UUID/전역 상태/Zone 범위/저장소 참조를 응답한다. 기존 일반 삭제 성공 응답은 유지한다.
6. 스냅샷 목록에 삭제 잔여 복구 대화상자를 추가한다. UUID와 선택 Zone을 진단한 뒤 고정된 대상 요약을 확인하고 재처리한다. 입력 변경·늦은 응답·진행 중 중복 제출을 차단한다. 대규모 UI 표준화는 후속 이슈 범위다.

## 검증 및 배포

변경 Maven 모듈만 WSL ext4에서 검사·빌드한다. 전체 Cloud 빌드와 qemu 빌드는 실행하지 않는다. 기존 관리 JAR를 백업하고 변경 클래스만 overlay해 기존 다른 기능을 보존한다. 정적 UI는 WEB-INF/META-INF를 보존하고 index를 마지막에 갱신한다. 31 먼저 점검 후 32에 반영한다.

각 클러스터의 전용 SNAP1478 접두어 자원만 사용한다. 일반/Zone 삭제, 중복 참조, 없는 파일, Destroyed+Destroying, 반복 삭제, 부분 실패 복구, 권한 거부와 다른 자원 보존을 API/UI/DB/실물로 확인한다. 결함 상태 구성에 필요한 fixture 주입과 제품 복구를 구분하고 수동 SQL 종결은 PASS로 인정하지 않는다. 기존 자원 응답과 FTCTL 작업 트리 해시를 전후 비교한다.
