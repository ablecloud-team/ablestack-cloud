# iSCSI 관리 적용의 LIO 설정 보존 source pin

b6b4485afc5의 cleanCLI와3새회귀를 포함한 독립2파일을 exactSHA로 pin했다.
50 focused tests(Inline6/IscsiAuth13/Lifecycle3/RenderedCredentials18/
RenderedRuntime10), bash·diff가 통과했고 같은 behavioral regression을
수정 전 적용하면2건 실패했다. 전체 suite 재실행으로 표시하지 않는다.

실제run2에서 확인된 rtslib targetctl unit의 clear→globalrestore 경로를
관리 적용에서 호출하지 않는다. apply 이전enable--now, Pythonpostrestart,
Bashpostenable/restart의 세 경로를 제거했다. LIO configfs와 protected private
vault를 정상 native writer가 적용하고 canonical/bootreconcile이 복구한다.
실제 생성 listener의 readiness 검사는 유지하며 존재하지 않으면 거절한다.

다른 target 설정을 global restore로 바꾸거나 새 live portal을 clear하지 않는
회귀와 rendered replay, missinglistener fail-closed 회귀가 포함된다.
기존 configfs owner/mode/inode·FD9 writer·private vault 보호를 유지하고
plaintext generic targetcli dump를 다시 만들지 않는다.

13번 실제 native 배포·login/RAW쓰기는 이 source단위에서0이다.
새 signedb6 런타임의 별도 로컬run3에서 실제 CHAP/mutual/negative/reconnect
I/O를 검증한다. 첫9a 및run2실패·원본/DATA 보존 proof를 그대로 보관하며
source50PASS를 실제kernel인증 성공으로 표시하지 않는다.

최종 UI #1275는 미착수다.
