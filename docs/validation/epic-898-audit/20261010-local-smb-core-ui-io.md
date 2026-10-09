# LOCAL SMB의 실제 UI 권한·CIDR·클라이언트 검증

원 SOURCE 복원 완료 뒤 F1의 기존 f1-smb/FILE2와 C1/C2를 재사용했다. 새 디스크·포맷·원 fef 추가 제출은0이다. 새 credential의 UI 입력은 CUA 런타임의 credential-entry handoff 정책을 지켰다. 최초 LOCAL_USER/ACL은 정상 API의 RAM setup으로 만들고 UI에서 상태를 확인했다. CreateStorageSmbAclCmd가 초기 password와 ACL을 함께 처리하므로 이를 GUI 생성으로 주장하지 않는다. 이후 권한·CIDR 변경은 실제 UI로 실행했다.

초기 ACL65bb49a4는 job9c81a395 성공/Ready/READ_WRITE였다. C1 인증·4096B 쓰기, 자기 파일의 정확한 서버 FD fsync, C1/C2 fresh 재접속 읽기가 동일 SHA65ea0e1d를 반환했다. UID:GID1001:1001/mode0660이며 잘못된 password는 양쪽 모두 LOGON_FAILURE/I/O0이었다. 이 값은 관리 API 성공만의 인수가 아닌 실제 SMB 전송 결과다.

최초 재사용 harness는 baseline 종료 때 임시 password를 폐기했다. 같은 사용자·ACL의 credential-only 정상 API refresh1회(jobebf51055)를 수행했고, 이번에는 전체 UI/deny-allow 종료까지 private RAM controller를 유지했다. 추가 CREATE와 private credential 추출·원문 파일/argv/log은0이다. 이 refresh를 UI 기능 실행으로 세지 않는다.

실제 UI 편집으로 READ_ONLY(job7ec66abd)에 적용한 뒤 fresh C1 쓰기가 ACCESS_DENIED/I/O0으로 거절됐다. UI READ_WRITE 복원(job97db76b8) 뒤 fresh 쓰기/읽기2×4096B가 동일 SHA로 성공했다. password 필드는 빈 값으로 유지했다.

UI의 C1/32 한정 네트워크 규칙(job07592fa9)은 설정/실행 CONSISTENT였고, 같은 정상 credential의 C2 fresh 읽기는 ACCESS_DENIED/I/O0, C1 읽기는 계속 성공했다. 편집 폼은 CIDR 하나만 허용해 둘을 한 row에 넣으려던 확인은 dispatch/job0으로 거절했다. 기존 C1 row를 유지한 채 정상 UI로 C2/32 규칙을 따로 생성(job33fdcd45)하고 C2의 fresh 읽기 허용 복귀를 확인했다. 무 credential 접속도 양쪽 모두 거절/I/O0이었다.

[기본 접속·동기화·재접속·wrong credential](local-smb-core-20261010/smb-local-external-client-ad297-proof.json), [실제 UI 변경과 7개 client 결과](local-smb-core-20261010/smb-ui-negative-controller-ad297-proof.json), [최종 native/data 보존](local-smb-core-20261010/local-smb-functional-preservation-public-proof.json).

최종은 GEN10/10d361de·pending없음이다. BOOT/CLI19ad/VM/ROOT+DATA2·mount·NFS sentinel과 원 immutable SOURCE journal/key/cipher/ref/compat를 보존했다. 정상 holder2/deletedfalse, 자기 파일4096B/uidgid1001/0660/SHA65ea, RO 거절 파일 부재를 확인했다. 모든 client는 정상 종료·mount0·UI sessions0이고 임시 credential은 폐기했다.

이번 결과는 LOCAL core subset이다. loaded network SID 직접 확인, mixed POSIX/다중 endpoint/cold, all4/backup/ROOT/AD 완료로 확대하지 않는다. 이후 기존 SPARSE RAW2를 정상 UI target/LUN/namespace pipeline으로 재사용한다. 최종 UI 표준 정리 #1275는 계속 미착수다.
