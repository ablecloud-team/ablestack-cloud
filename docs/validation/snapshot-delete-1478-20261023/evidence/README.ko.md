# 검증 증거 안내

- `31/32-operation-journal.jsonl`: API 요청 시각, 비동기 job UUID, 응답. 인증 정보는 수집 시 마스킹하며 로그인 및 무관한 인벤토리 응답은 제외한다. 개발 중 실패와 최종 재검증을 모두 남긴다.
- `*-fault-failure/retry.json`: 실물 장애 중 실패 및 장애 해제 후 제품 API 재시도.
- `*-owner-validation.json`: 일반 소유자 복구와 타 소유자 접근 거부.
- `*-quota-verification.json`: 최종 서버 변경 반영 후 동시 삭제·반복 호출, 표준 기준값 대비 원시 DB 계수와 단일 삭제 이벤트.
- `*-db-final.txt`, `*-physical-final.json`, `*-cleanup.json`: SQL 보정 없는 API 정리 결과. 삭제된 과거 이력 행은 유지한다.
- `*-inventory-before/after.json`, `*-inventory-preserved.json`: 시험 전 기존 자원의 UUID·상태·소유자·경로 보존. 31의 새 무관한 볼륨 1건은 건드리지 않았다.
- `*-live-verification.json`: 활성 JAR 클래스 21개와 UI 정적 파일 917개 해시, 설정 및 WEB-INF 보존.
- `*-agent-deploy.json`: 32 호스트별 변경 클래스 2개, VM 인벤토리 및 FTCTL 프로그램 보존.
- PNG와 UI 텍스트는 실제 브라우저 조작 증거다. 화면의 다른 시스템 경보는 이번 시험 대상이 아니다.

fixture 주입은 실패 상태를 만들기 위한 작업이다. 제품 API/UI 실행 후 DB 상태를 수동으로 종결한 사례는 없다. `snapshot_store_ref`의 Primary 이력은 볼륨 expunge 후 정상 GC될 수 있어 삭제 직후와 최종 정리 시점을 구분한다.

TSV의 빈 마지막 필드는 `[empty]`로 표기한다.
