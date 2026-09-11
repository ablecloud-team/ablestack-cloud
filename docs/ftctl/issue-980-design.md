# #980 테스트 정리 후 RESUME 재시도 멱등성 설계

## 기준 및 원인
Cloud 43330d38e3, qemu fb6df9b를 기준으로 한다. Cloud 안전 브랜치는 codex/fix-980-cleanup-resume-idempotency. restoreTestCheckpointProtection은 매번 startForwardTargetExport를 호출하며, 이 메서드가 새 세대 STOP(all historical workers) → START를 반복한다. 이미 성공한 RESUME의 응답 유실 이후 재시도도 동일하게 처리되는 것이 원인이다.

## 코드 설계
- DrExportOwnershipStore에 정리 Run별 durable export 준비 레코드를 추가한다. 별도 dr_cleanup_export_resume 테이블: cleanup_run_id, plan_id, revoke_generation, observed_worker_uuid, disk_fingerprint, drained. 인증정보/프로파일 전체/고정 배치 권한은 저장하지 않는다.
- 복원 전용 restoreForwardTargetExport를 추가한다. 매 시도 live placement를 다시 해석한다. 같은 live worker 및 디스크 계약이면 기존 세대를 재사용한다. worker/디스크 계약 변경 시에만 새 세대와 기존 전체 회수 ACK 경로를 사용한다.
- STOP ACK 전에는 drained=false, 모든 회수 ACK 후 START RPC 전 drained=true를 영속화한다. START 응답 유실/관리 재시작 후에는 같은 START 세대 재시도로 기존 qemu-nbd PID/포트를 재사용한다. 이전 STOP을 다시 보내지 않는다. 더 최신 세대는 qemu fencing이 거부하므로 과거 intent가 권한을 탈취하지 않는다.
- 원본 status probe는 export 변경 이전에 유지한다. RESUME 요청 UUID는 기존 sourceTransitionRunUuid(plan, cleanup Run, RESUME_SYNC)를 재사용한다. 단순 RUNNING만으로 이전 요청의 성공을 가정하지 않고 동일 요청의 멱등 전이를 재시도한다.
- PAUSE/RELEASE/FAILOVER, TARGET authority, disabled/removed Plan을 export 준비 전과 RESUME 직전에 재확인한다. cleanup 성공은 source restore 완료와 독립적으로 유지한다.
- qemu START 동일 세대의 기존 PID/포트 재사용 및 stale generation 거부를 검증한다. 필요한 결함이 없으면 qemu 소스를 중복 변경하지 않는다.

## 검증 및 배포
- 실제 서비스 경로에서 RESUME 처리 성공 뒤 응답만 유실하는 fault injection으로 최소 3회 재시도, export 세대 및 재시도로 인한 추가 STOP 부재 확인 (체크포인트 idle flush의 정상 PID 교체는 제외). VM 데이터 경로는 끊지 않는다.
- 준비 phase 전후 crash 재시도와 관리 서버 재시작 시 durable 레코드 재사용, worker 변경 시 ACK 순서, superseding operator intent 회귀.
- DR/schema 변경 Maven 모듈만 WSL ext4 빌드, baseline action/tombstone/checkpoint 스모크.
- 31 qcow2와 32 RBD UI 테스트 페일오버 → 정리 → 자동 재개 검증. VMware 양단 QGA 제외, migration 허용, source 불통을 disaster failover 전제에 추가하지 않는다.
- 4개 관리 서버 schema 선적용, 복원 서비스가 있는 31/32 관리 서버에 변경 클래스 배포 (13/22 원본 중계 JAR는 유지), 필요 시 qemu shell 파일만 배포. PR #1022/#56과 Epic #950 갱신.
