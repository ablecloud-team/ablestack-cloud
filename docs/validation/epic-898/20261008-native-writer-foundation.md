# 중단된 변경 작업 복구를 위한 native writer 잠금 기반

native 변경 명령은 보호된 파일의 flock을 획득하고 명령이 끝날 때까지 descriptor를 유지합니다. 관리 서버가 중단돼도 게스트 작업이 계속 실행 중이면 다음 변경과 writer-idle/preflight는 STORAGE_WRITER_BUSY로 차단합니다. 공개 관측 조회는 잠금 대기로 멈추지 않습니다.

잠금 파일의 owner·mode·symlink 및 부모 쓰기 권한을 검사합니다. 중첩 native 복구는 같은 descriptor를 상속하고, 암호화된 NVMe identity 재적용도 해당 descriptor만 전달해 잠금을 유지합니다. 관리 서버의 RUNNING/RECOVERY_REQUIRED 작업은 암호화된 복구 자료를 삭제하지 않으며 완료·rollback 등 확정된 terminal 상태에서만 정리합니다.

조사 중 NFS idmapping preflight가 이후 실제 export 적용으로 이어지는 제어 흐름 결함을 발견했습니다. 사전 점검 성공 후 종료하도록 수정했습니다. 이전 소스의 실제 명령 분기를 실행한 통제 회귀는 UNEXPECTED_MUTATION으로 실패했고 수정 소스는 통과했습니다.

검증:
- 실제 flock 경쟁, symlink/쓰기 가능한 부모 거절, 중첩 Bash 및 Python descriptor 상속, NFS preflight 비변경 회귀를 포함해 native 70개 통과.
- 미완료 checkpoint 보존과 rollback 후 지정된 두 파일만 정리하는 회귀 및 원자적 변경 회귀를 모듈 package로 검증.

아직 native 런타임 배포·실제 경쟁 차단 검증과 관리 서버 재시작 후 작업 재개·generation·취소/배수 완료 게이트는 남아 있습니다.
