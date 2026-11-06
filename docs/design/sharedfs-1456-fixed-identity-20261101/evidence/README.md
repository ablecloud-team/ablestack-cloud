# 사전 실증 증거

native-summary.json은 최종24검사와 실제 계정 파일 불변을 요약합니다. native-result-r6.json은 해당 실제 SystemVM stdout/exitcode입니다. actual-guard-result.json은 현재 배포 helper의 추가3검사입니다.

native-result.json/r2는 절대 VFS 경로 준비, r3~r5는 표준 위치 모듈과 전체 실패 감사 경로의 조사 기록입니다. 일반 공유의 I/O 성공과 감사 경로의 실패를 구분했고 최종 정책은 성공 연산 allowlist/failure=none으로 검증했습니다. 초기 실패를 PASS로 계산하지 않았습니다.

native-prototype.py는 기존 SystemVM의 식별 UUID와 지정 SPARSE DATA를 확인한 뒤 private mount/network namespace에서만 실행하는 실증 코드입니다. /etc와 VFS 라이브러리는 RAM overlay, passdb도 RAM이며 운영 계정을 복사 복구하지 않습니다. 검증된 full_audit.so 경로를 --module로 지정해야 합니다. 파일은 audit-module-provenance.json의 공식 Debian 버전/hash와 같아야 합니다. package/so binary는 저장소에 넣지 않았습니다.

실제 AD 계정 해석은 inventory에 있으며 새 기능의 AD 인증/제품 API/UI 인수를 대신하지 않습니다.
