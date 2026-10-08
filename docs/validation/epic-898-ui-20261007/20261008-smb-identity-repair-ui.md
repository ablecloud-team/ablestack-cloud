# SMB 인증 연결 복구의 실제 UI 검증

기능 UI 0c70b1cafb1637bf71d1b2f8cb44ff0823231b1a를 독립 git archive에서 생산 빌드했다. 844개 정적 파일의 해시를 대조해 배포하고 기존 config.json, WEB-INF 및 관리 서버 PID를 보존했다. index.html SHA-256은 471f32b10ed7967d38fa86296c6b3016c71f7000413349421d5fc50005fc6e3c이다. 최종 표준화 #1275 작업은 수행하지 않았다.

Chrome에서 실제 서비스 SMB 탭을 열어 신규 인증 연결 복구 버튼을 확인했다. 초기 확인 버튼이 비활성인 상태와 정확한 서비스 이름 및 유지보수 확인 체크 후 활성화됨을 검증했다. 해당 테스트 서비스만 UI로 실행했다.

작업 cb8bb5c9-d717-45ac-b351-a4a8afe0199a / async job ebf709c3-cec0-48c4-98a6-4c77ca9d5965는 게스트 rebind 단계에서 실패하여 RECOVERY_REQUIRED로 보존되었다. 실제 UI는 오류와 job ID를 유지했다. 성공 처리하거나 새 키로 자동 재실행하지 않았다.

![실제 복구 실패 및 작업 ID](smb-identity-repair-ui-recovery-required.png)

읽기 검사에서 옛 master A1291/B67681은 소유된 dedicated unit의 A346908/B346911로 바뀌었고 현재 passdb/secrets descriptor는 정렬되었다. expected와 현재 scope, bootId, generation11, 설정 해시 및 보호 DB identity가 모두 같다. TCP endpoint 준비와 소유권도 확인되었으며 TCP/SMB/tree/open/byte lock은 0이다. 재기동 직후 readiness 검사 시점에 대한 추가 진단과 같은 operation의 멱등 재개가 필요하다. 이 증빙만으로 실제 SMB 인증·파일 I/O·재부팅을 통과했다고 주장하지 않는다.

기존 DATA와 비밀번호·desired generation은 변경하지 않았다. 실제 인증 검증과 held FD·cross-endpoint lock·선택 B 변경·정상 재부팅이 다음 게이트이다. original39/41/49와 partial SPARSE10TiB VM51은 제외했다.

## 동일 작업의 정식 재개 결과

scope와 부팅·generation·설정·DB identity 및 endpoint 준비가 그대로인 것을 확인한 뒤 원래 작업 키를 사용해 정상 API를 재개했다. 새 async job cb73a412-93f0-4e92-ac08-c0c4c3c34b5d가 성공했고 원래 operation cb8bb5c9가 COMPLETE_NO_CONFIG_CHANGE / SMB_IDENTITY_VERIFIED / 100으로 전환됐다. native 결과는 idempotent=true, generationAdvanced=false, configurationUnchanged=true, databasesUnchanged=true였다. 두 master PID/startTicks가 재개 전후 동일하며 이미 정렬된 분기에서 추가 stop/signal/systemctl 실행은 없다.

실제 Chrome 작업 탭에서도 동일 완료 이력을 확인했다. 기존 UI의 공통 완료 상태가 '준비 재개 완료'로 표시되고 과거 오류 진단이 남는 점을 발견했다. 소스에서 기능 중립적인 '설정 변경 없이 완료'로 고쳤으며 실패한 작업도 같은 키를 보존하는 UI 회귀 6개가 통과했다. 이 변경은 아직 새 UI 빌드로 배포되지 않았다. 성공 시 진단 교체와 초기 기동 준비 대기의 native 보완은 후속 구현 중이다.

![실제 동일 작업 완료 이력](smb-identity-repair-ui-complete-history.png)

이후 새 RAM 전용 테스트 자격 증명을 정상 ACL API로 1회 설정해 실제 인증·held FD·cross-endpoint lock 검증을 진행한다. 이전 인증 실패의 비밀번호를 저장하지 않아 이 새 테스트와 원래 1회 reset의 증빙을 분리한다. 완료 이력만으로 실제 파일 인증을 완료 처리하지 않는다.

## 실제 인증과 선택 B 변경

복구 후 새 정상 ACL reset job bd1edb90가 1회 성공했다. passdb 비밀번호 비교 true, 게스트 A/B × unqualified/qualified/명시 domain 6개 인증 및 tree 읽기가 성공했다. 호스트13.1의 unqualified·명시 domain CIFS read-only 연결과 원본 해시 검증도 성공했다. Windows 형식 qualified username을 mount.cifs에 넘긴 1개 형태는 권한 오류이며 게스트 qualified 형태와 구분한다.

같은 RAM 자격 증명으로 A의 열린 파일에 연속 쓰기를 유지한 채 B의 인증·양방향 읽기/쓰기·byte-range lock 거절 및 unlock 후 접근을 검증했다. B만 삭제한 정상 API job 8ec8520e가 성공했고 B445 포트는 닫혔다. A master346908/startTicks2976913과 열린 inode16777346의 쓰기·해시가 유지됐다.

그 후 실제 UI에서 existing IP .241/port445를 선택해 B를 재활성화했다. operation0f7abe13 generation14가 완료됐고 A master는 그대로, B는 새354713으로 기동했다. 같은 RAM 자격 증명으로 B 인증·교차 I/O·잠금을 다시 확인했다. 클라이언트 정상 정리 전 A의940회 쓰기에서 오류0, 최고fsync지연86.65ms였다. 원본 sentinel은 inode16777345/UID:GID1001:1001/mode0660/SHAfe084가 보존됐다.

![B 재활성화 후 실제 UI](smb-selective-b-ui-restored.png)

## 정상 재부팅의 실패 게이트

클라이언트 writer 종료·FDclose·양쪽 mount 정리를 확인한 뒤 실제 VM UI에서 강제 옵션 없이 정상 재부팅했다. 새 bootId, QGA, 원본 DATA XFS UUID와 sentinel identity·로컬 UID1001·MAC·기본 route는 보존됐다. 그러나 약67초/12회 시도 뒤 boot reconcile 서비스가 실패했고 SMB 리스너0/보조 .241 없음이 확인돼 부팅 완료로 인정하지 않았다.

실제 로그는 ROOT endpoint primary IP does not belong to its pinned MAC과 SMB endpoint IP is not assigned 오류를 보여준다. Cloud DB primary .241과 STATIC guest primary .240의 기존 불일치가 보호된 endpoint 검증 및 보조 IP 복원 순서와 충돌했다. NIC 식별 복구 UI dry-run은 DB .241→runtime .240/alias .241 보존/ELIGIBLE이다. 현재 적용은 수행하지 않았고, 보호된 endpoint manifest까지 정상 경로로 새 관측을 반영할 수 있는지 확인 중이다. 수동 DB 수정·IP 추가·서비스 재시작으로 우회하지 않았다.

![NIC 식별 복구 사전 점검](smb-coldboot-nic-identity-dryrun.png)

RAM 인증 클라이언트의 재부팅 실패 관측 경로에도 unmount 후 파일을 읽는 테스트 도구 버그가 있어 세션이 종료됐다. 비밀 stderr를 공개하지 않았고 비밀번호는 폐기됐으며 추가 reset은 없다. 성공한 재부팅 전 검증과 실패한 부팅 검증을 구분하고, 도구를 보완해 다음 재검증을 수행한다. 최종 UI 표준화는 계속 미착수이다.
