# 증거 안내

- `focused-tests.json`, `ui-tests.log`, `ui-lint.log`, `ui-build-tail.log`: 132 Maven / 34 UI 검사와 범위.
- `*-api-operations.jsonl`: 로그인·일반 인벤토리를 제외한 보관/삭제/비동기 결과. 비밀 필드는 REDACTED.
- `*-archive-*.json`, `*-*-archive-failure/retry.json`: 보관 적격성, 검증 실패, Allocated 재사용, 동시 요청.
- `*-force-*.json`, `*-ui-force-jobs.json`: 강제 DB 정리·실물 정리 분리와 재시도. 작업 결과 physical=false를 성공적 실물 삭제로 해석하지 않는다.
- `*-db.json`, `*-physical.json`: 스냅샷/참조/계수/감사 이력 및 NFS/GFS/RBD 전후 실물. fixture 구성은 `*-injection.json`/장애 단계 JSON과 구분한다.
- `*-force-plan/db-only/complete.png`, `*-normal-deleted.png`, `*-recovery-settled.png`와 AX 텍스트: 실제 브라우저 UI 증거. 스크린샷은 Windows managed worktree에서 추가한다.
- `final-verification.json`: 활성 잔여 0, 원래 인벤토리 보존 해시·건수, 계정 삭제 경로에서 별도 발견된 usage 중복을 구분한 결과.
- `*-runtime.json`, `backend-classes.json`, `ui-manifest.json`: 실제 배포 해시·기존 JAR/설정 보존. `*-hosts.jsonl`은 Routing host 3개 Up.
- `*-ftctl-before/preserved.json`: 작업 트리 기준과 보존 결과. Windows와 WSL의 서로 다른 dirty FTCTL 브랜치를 변경하지 않았다.

원본 작업 디렉터리는 WSL ext4 `/home/ablecloud/work/snapshot-archive-1477-20261023`이다. 전체 운영 인벤토리·로그인 기록은 로컬에만 남기고, 저장소에는 보존 결과 해시/건수와 시험 자원 중심 증거를 보존한다. `scripts/verify_evidence.py <원본 디렉터리>`는 기록을 다시 대조하며 클러스터를 변경하지 않는다. 계정 삭제 usage 중복은 검증 도구의 예상 기존 결함으로 명시하며 이를 전체 PASS로 바꾸지 않는다.
