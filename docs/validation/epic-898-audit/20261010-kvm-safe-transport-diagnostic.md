# SharedFS 호스트 응답 실패의 안전한 진단과 제한 배포

원본 SMB 복구는 게스트의 캡처·서비스 재개 뒤에도 관리 서버에 HOST_EXCEPTION만 전달돼 실패 단계를 구분할 수 없었다. 비밀을 포함할 수 있는 오류 원문을 숨기는 기존 응답은 유지하고, 호스트 로그에 고정된 실패 단계·예외 종류·공개 libvirt 오류 번호만 추가했다.

소스 4e2dd59bc96의 KVM wrapper 한 개와 회귀 테스트를 고정했다. 집중11 및 정상61 tests/7 selectors·Checkstyle을 통과했으며 실패·오류·생략0이다. 기존 성공 응답·masked 실패 문구/resultJson·timeout·guard·payload를 유지하고, 로그 자체가 실패해도 기존 응답을 바꾸지 않는다. 민감 sentinel·Throwable 원문·stack·argv·stdout/stderr는 기록하지 않는다.

실제 호스트 라이브러리와 새 참조21개를 대조해 누락0을 확인했다. 기존 공개/보호 ABI는 유지하며 새 진단 메서드 한 개만 추가했다. Java17·Libvirt52cc·실제 Logger/JsonNull 제공자를 사용했다. 이 검증은 라이브러리 심볼 확인이며 원래 실패의 해결이나 서비스 기능 완료를 대신하지 않는다.

검토한 manifest946dd19a와 후보 JARa1cf47dc를 13.2 호스트에 적용했다. 원 JAR43b91add·wrapperfeff·PID3347391이 일치할 때만 root0700 백업을 만들고 wrapperb468 한 개를 교체했다. 다른482 파일의 내용/ZIP 메타데이터와510 원시 local record를 보존했으며 Libvirt JAR은 변경하지 않았다. 에이전트 새 PID는985133이다. 호스트 VM15개의 PID/start tick은 모두 같고 VM 재시작 명령은0이다. 백업은 /root/epic898-safe-host-wrapper-backup-20261009-190240에 있다.

배포 뒤 원 SOURCE·키/cipher/호환 증빙·GEN4/BOOT·DB 메타데이터·두 DATA/XFS·NFS sentinel과 pending fef가 동일했다. Native status는 RESUMED/rc0이고 현재 소유권·세션 없음·복원 가능 조건을 통과했다. 관리 HTTP200/API 로그인·서비스8 Running·호스트3 Up·기존 작업 이력도 확인했다. 관리 모듈·운영 UI·게스트 CLI19ad는 이 배포에서 바꾸지 않았다.

정상 UI의 동일 fef 복구 한 번(job87de15c4, 04:08:36~04:10:48 KST)은 530 오류로 끝났고 관리 작업은 RECOVERY_REQUIRED에 남았다. 04:10:48.370의 HOST_EXCEPTION을 확인했다. 원 SOURCE·키/cipher/호환 증빙·GEN4/BOOT·DB/DATA/NFS/pending은 동일하고 native status는 RESUMED/rc0 및 소유권·세션 없음·복원 가능을 유지했다. 추가 UI 제출·ACL·외부 I/O는0이다. 실제 새 진단행13개는 정확한7필드 검증을 통과했지만 초기 logger bracket 필터가 형식 차이로 제외했다. 원문을 숨긴 채 실제 logger의 canonical 끝3개 segment가 정확히 일치함을 확인해 resource.wrapper.LibvirtStorageServiceHostCommandWrapper 별칭만 추가했다. 관리 실패41ms 이전의 실제 진단은 LAUNCH/LIBVIRT/120044ms/domain10이며 code는 SDK UNKNOWN fallback으로 null이다. guest-exec PID 응답 이전 RPC 실패이며 stdout decode/JSON parser 원인으로 추정하지 않는다. 이전 공개 응답 크기 시험은 protected stdin을 포함하지 않아 동일 Bash/input-data 교차 시험을 별도로 진행한다. 아직 근본 원인은 확정하지 않는다. 앞선 [CODE 완료와 복구 응답 실패](20261010-code29-source-recovery-actual.md)는 당시 결과이며 강제 완료·재포맷·키 삭제는 하지 않는다. LOCAL ACL·외부 SMB 인증/I/O 완료는 아직0이다.

별도 공개 cross-probe는 같은Bash/입력1492B/EOF 읽기2건 및 실제JavaJNI wrapperb468 경로의 입력1492B+출력1518638B 조합1건을 통과했다. 원CLI/실제capsule/서비스·DATA 효과와 정리 신호는0이다. 입력/출력 조합만의 결함을 재현하지 못했으며 standalone connection과 실제agent 공유 연결·실제cached producer 차이는 남는다.

[고정 모듈·실제 배포의 공개 증빙](20261010-kvm-safe-transport-diagnostic-public-proof.json). 최종 UI 표준 정리 #1275는 미착수이며 착수 직전 중단·보고·추가 지시 대기 경계를 유지한다.
