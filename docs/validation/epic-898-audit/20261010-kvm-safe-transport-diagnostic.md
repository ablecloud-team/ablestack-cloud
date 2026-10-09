# SharedFS 호스트 응답 실패의 안전한 진단과 제한 배포

원본 SMB 복구는 게스트의 캡처·서비스 재개 뒤에도 관리 서버에 HOST_EXCEPTION만 전달돼 실패 단계를 구분할 수 없었다. 비밀을 포함할 수 있는 오류 원문을 숨기는 기존 응답은 유지하고, 호스트 로그에 고정된 실패 단계·예외 종류·공개 libvirt 오류 번호만 추가했다.

소스 4e2dd59bc96의 KVM wrapper 한 개와 회귀 테스트를 고정했다. 집중11 및 정상61 tests/7 selectors·Checkstyle을 통과했으며 실패·오류·생략0이다. 기존 성공 응답·masked 실패 문구/resultJson·timeout·guard·payload를 유지하고, 로그 자체가 실패해도 기존 응답을 바꾸지 않는다. 민감 sentinel·Throwable 원문·stack·argv·stdout/stderr는 기록하지 않는다.

실제 호스트 라이브러리와 새 참조21개를 대조해 누락0을 확인했다. 기존 공개/보호 ABI는 유지하며 새 진단 메서드 한 개만 추가했다. Java17·Libvirt52cc·실제 Logger/JsonNull 제공자를 사용했다. 이 검증은 라이브러리 심볼 확인이며 원래 실패의 해결이나 서비스 기능 완료를 대신하지 않는다.

검토한 manifest946dd19a와 후보 JARa1cf47dc를 13.2 호스트에 적용했다. 원 JAR43b91add·wrapperfeff·PID3347391이 일치할 때만 root0700 백업을 만들고 wrapperb468 한 개를 교체했다. 다른482 파일의 내용/ZIP 메타데이터와510 원시 local record를 보존했으며 Libvirt JAR은 변경하지 않았다. 에이전트 새 PID는985133이다. 호스트 VM15개의 PID/start tick은 모두 같고 VM 재시작 명령은0이다. 백업은 /root/epic898-safe-host-wrapper-backup-20261009-190240에 있다.

배포 뒤 원 SOURCE·키/cipher/호환 증빙·GEN4/BOOT·DB 메타데이터·두 DATA/XFS·NFS sentinel과 pending fef가 동일했다. Native status는 RESUMED/rc0이고 현재 소유권·세션 없음·복원 가능 조건을 통과했다. 관리 HTTP200/API 로그인·서비스8 Running·호스트3 Up·기존 작업 이력도 확인했다. 관리 모듈·운영 UI·게스트 CLI19ad는 이 배포에서 바꾸지 않았다.

정상 UI의 동일 fef 복구 한 번(job87de15c4, 04:08:36~04:10:48 KST)은 530 오류로 끝났고 관리 작업은 RECOVERY_REQUIRED에 남았다. 04:10:48.370의 HOST_EXCEPTION을 확인했다. 원 SOURCE·키/cipher/호환 증빙·GEN4/BOOT·DB/DATA/NFS/pending은 동일하고 native status는 RESUMED/rc0 및 소유권·세션 없음·복원 가능을 유지했다. 추가 UI 제출·ACL·외부 I/O는0이다. 실제 새 진단행13개는 정확한7필드 검증을 통과했지만 초기 logger bracket 필터가 형식 차이로 제외했다. 원문을 숨긴 채 실제 logger의 canonical 끝3개 segment가 정확히 일치함을 확인해 resource.wrapper.LibvirtStorageServiceHostCommandWrapper 별칭만 추가했다. 관리 실패41ms 이전의 실제 진단은 LAUNCH/LIBVIRT/120044ms/domain10이며 code는 SDK UNKNOWN fallback으로 null이다. guest-exec PID 응답 이전 RPC 실패이며 stdout decode/JSON parser 원인으로 추정하지 않는다. 이전 공개 응답 크기 시험은 protected stdin을 포함하지 않아 동일 Bash/input-data 교차 시험을 별도로 진행한다. 아직 근본 원인은 확정하지 않는다. 앞선 [CODE 완료와 복구 응답 실패](20261010-code29-source-recovery-actual.md)는 당시 결과이며 강제 완료·재포맷·키 삭제는 하지 않는다. LOCAL ACL·외부 SMB 인증/I/O 완료는 아직0이다.

별도 공개 cross-probe는 같은Bash/입력1492B/EOF 읽기2건 및 실제JavaJNI wrapperb468 경로의 입력1492B+출력1518638B 조합1건을 통과했다. 원CLI/실제capsule/서비스·DATA 효과와 정리 신호는0이다. 입력/출력 조합만의 결함을 재현하지 못했으며 standalone connection과 실제agent 공유 연결·실제cached producer 차이는 남는다.

이후 같은원작업의원공개키 지문9fbb·원참조/GEN4/BOOT/소유권/세션없음·realFD9/hold/formatter사전조건으로 실제cached CLI를1회 검사했다. GuestRAM reducer경로는1.464초/원stdout1518638B를소비해805B공개metadata만반환했고 재수집·STOP·암호화·원자료변경은없었다. 다음samekey/cipher fullresponse isolatedJNI1회는 실제protectedBuilder와원CLI 그대로 launch1ms/status2137ms/전체2580ms에성공했다. 원response1518638B를호스트RAM에만두고capsule/ref/PUB/typedflags를검증했으며 원문·키·SID를출력/저장하지않았다. 전후10개상태·마운트·DBholders·native status가동일했다.

이 성공은 분리된연결의actualproducer/fullencryptedQGA경로이며 runningAgent의공유연결·동시요청·lifecycle차이는남아있다. 관리자작업terminal을완료로바꾸거나추가UI복구를하지않았고 원실패해결로선언하지않는다.

기존runningAgent PID985133의이미존재하는Connect token1979218253에서도공개1492B입력/1518638B출력1건이성공했다. 공개진단attach1회·QGA1회, launch2ms/status1108ms/전체1160ms이며같은map객체를유지했다. 연결생성/닫기/재설정·production변환·전역로그/출력변경·원CLI는0이고Agent/JAR/VM15/F1전후10개보존을확인했다. 표본의eventThread는RUNNABLE/alive/daemon이며알려진동시QGAframe은0이다. 별도diagnosticthread이므로정상worker/queue·과거동시호출실패까지입증하지않는다. 같은pool과실제cached body의조합은이기록시점에준비중이다.

[고정 모듈·실제 배포의 공개 증빙](20261010-kvm-safe-transport-diagnostic-public-proof.json). 최종 UI 표준 정리 #1275는 미착수이며 착수 직전 중단·보고·추가 지시 대기 경계를 유지한다.

## 실제 관리 단계의 재확인

이후 exact원fef/rev5/contextscope를읽기전용JDBC1SELECT로재확인했다. nativeIdentityCapsule참조/object와sourceResumed=true는이미관리서버에기록돼있고restored/authReplayed는없었다. 따라서원캡처·출판은완료됐으며 export만실패했다고좁힌앞선해석은불완전했다. Source started:275는참조가있으면export를건너뛰고후속restore:4566에서capsule+credentialPrivateKey+COMMON7/ref/SMBdomain을import한다. LAUNCH진단7필드에는명령종류가없으므로실제실패명령을확정하기전단계로구분한다.

지금까지1492B입력과1518638B출력을검증했지만큰입력은검증하지않았다. 후속import의큰protectedstdin을구별하기위해공개1518638B입력/작은출력1case를준비한다. 실제privatekey/import/STOP/상태변경은0이고추가pool-realexport진단은HOLD했다. 원snapshot·키·캡슐값은SQL조회결과나파일로출력하지않았다.
