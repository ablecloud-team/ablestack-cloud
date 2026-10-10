# SharedFS native configuration generation 중간 검증

## 구현과 배포

- Backend source: 2c3719d5c5b2ce44c9431182bb25a133be94faa4. 모듈 빌드 성공, backend 31개 테스트 통과.
- SystemVM source: 93a30a7397b, epic898-20261008-config-generation-agent. SystemVM 테스트 80개 통과.
- 서명 검증된 카탈로그: f15ce21b-a4d2-49cf-a827-184a4a69213b, AVAILABLE. VM 39/41/43/48/49 업그레이드 COMPLETE.
- 관리 서버 변경 클래스 14개만 교체. 다른 JAR 엔트리의 바이트 유지 검증. 관리 서버 PID 1041698, JAR SHA-256 aa058b2e8da4aba40f5ada8309031321cb2de172bffea9b1d3a636dd3c85f8b5.
- 배포 백업: /root/epic898-backup-20261008-033317.

## 정상 검증

시험 서비스 65260ea9-bdb1-4ce4-bd6e-55cd755b5c59, instance 67673fb1-f83c-4da7-a395-14d3f5321fe7.

1. 첫 API 상태 조회는 호스트 agent가 전달한 빈 payload 파일을 JSON으로 해석해 BLOCKED로 종료했다. 기존 revision 4와 ACTIVE_LKG는 유지됐다. 실제 전송 방식에 대한 회귀 테스트를 추가한 수정 런타임으로 재검증했다.
2. API 검증 job 7f7f20cb-abcb-4a3b-8cbe-9c8e1c800c30 완료. operation 7b6c6865-0c9f-485f-9d38-b5c50facb614의 desired/runtime revision 5가 일치했다.
3. Chrome 상세 화면의 **현재 구성 검증** 버튼으로 다음 검증을 실행했다. operation 7612d2f2-2943-45dc-bba9-172404abf8c1, desired/runtime revision 6, ACTIVE_LKG b3b868ec-15aa-4651-a4c3-6fd75f94192b.
4. native status: IN_SYNC, pendingOperationUuid null. 설정 SHA-256 88964cf84c63637f52736d8f2f1ba08350098c8950efd9245d11e36420e72f8e.
5. 기존 UI의 구성 복원 지점은 즉시 갱신되지만 작업 이력은 별도 업데이트를 요구하는 문제가 발견됐다. 작업 종료 이벤트와 scoped 이력 갱신, generation 검증 메타데이터 표시를 보완했다. 관련 UI 테스트 27개 통과. 새로운 UI 산출물 배포·실제 화면 검증은 별도 기록한다.

## 실제 SMB 변경 후 native commit 보상 롤백

- 공유 c0679c42-c4b3-4769-a5ae-1507241d3f93 browseable true → false 변경.
- job d88ae4cf-856e-40b0-800e-b102c4ff5179, operation e7a79664-51b2-461a-a1b1-18922648ecce, staging revision 7.
- 시험 서비스의 활성 포인터 행만 잠근 상태에서 prepared CANDIDATE를 관측했다. native commit 이후 해당 서비스의 정확한 DB promoter 연결에만 연결 중단을 주입했다. 전역 DB 설정이나 권한은 변경하지 않았다.
- API jobstatus 2, operation ROLLED_BACK 100, candidate FAILED. 이전 ACTIVE_LKG revision 6이 유지됐다.
- 이전 SMB browseable true, native runtime revision 6, IN_SYNC, pending null로 복구됐다.
- 별도 VM41의 실제 SMB 인증 읽기 성공, 아래 기준 데이터 hash 일치. 보호 capsule과 키는 기존 cleanup 경로를 사용한다.
- DB pause guard는 finally에서 rollback/연결 종료됐다. 사용자 DATA를 삭제하거나 포맷하지 않았다.

| 기준 | 검증 후 및 롤백 후 |
| --- | --- |
| DATA filesystem UUID | 5108fdba-7bb0-4b7a-907d-be4b35970767 |
| 기준 파일 SHA-256 | bb4a726771dac07790d3ad29f65bc839b73bfdef5ab5df842edb827bf6c031d0 |
| UID/GID, mode, inode | 1001001:1001001, 0775, 16777346 |
| VM boot ID | 5ce47946-391f-4be3-8b94-93ebe31751ac |
| Samba master PID | 1015 |

Chrome의 작업 이력 업데이트로 revision 7 **이전 구성 복구 완료**와 정상 검증 revision 6/5를 확인했다.

![Chrome native generation 롤백 이력](20261008-native-generation-rollback.png)

## 완료 범위의 한계

generation journal과 current 포인터의 원자 교체, revision 일치 승격, 보상 롤백의 이번 경로를 검증했다. 네 프로토콜 전체 rendered 설정의 원자 교체, 여러 중단 단계와 재부팅, 장기 작업 drain/cancel, 구성 복구 전체 수명주기와 최종 UI 정리 완료를 의미하지 않는다. Epic #898의 #892/#897/#909는 계속 진행한다. SMB AD만 사용자 요청으로 보류한다.

## 지속 heartbeat 및 배포 UI 재검증

- 장기 guest 호출/DB promotion 대기 중 heartbeat 컬럼만 instance/operation ID·UUID 범위로 갱신한다. phase/progress/diagnostic과 terminal state를 덮어쓰지 않는다. scheduler의 DB context는 갱신마다 닫는다.
- Backend heartbeat 테스트 29개 및 모듈 빌드 통과.
- 최초 실제 대기 시험에서는 SQL CURRENT_TIMESTAMP의 DB 지역 시간과 기존 GenericDao의 GMT 저장 규칙 차이로 API heartbeat가 9시간 미래에 표시됐다. 완료 전 단계의 시간 신선도 판정에 영향을 주므로 기록 방식을 기존 DAO와 동일한 GMT 문자열 바인딩으로 수정했다.
- 수정 source 78022784e995d001ddc1f296d2e895ba86cd4a62, schema module build 성공. DAOImpl 클래스 한 개만 추가 교체했다.
- 관리 서버 PID 1044962, JAR SHA-256 19a51aae598210fa58bf33aac93de7d02c3fc0752f9f4cae0238fd2625ef2553. 백업 /root/epic898-backup-20261008-035109.
- 실제 재검증 job 8fb4b92f-3d55-443a-b624-c08adcc4ccb5, operation d98258d4-52c1-412e-a8e0-0c28705f7584. VERIFYING/80/RUNNING을 유지한 채 heartbeat 03:52:52 → 03:53:08 → 03:53:28 갱신을 관측했다. 각 polling에서 실제 heartbeat age -2~30초 범위를 검사했다. guard 해제 후 COMPLETE revision 8, ACTIVE_LKG 5ae2f3bc-5c9b-4fc4-826e-c824a694f3f4.
- 생산 UI source 8eac92cd5af72c003f6430e09e2909089c79df76, 테스트 27개/lint/build 성공. 정적 파일 841개 배포 검증, 설정/WEB-INF/관리 서버 PID 유지. index SHA-256 edd41c140e3c8d9c0bac8fcaa52bce4bcf940972094c2dfa3ec1d50f09bc920b.
- Chrome의 현재 구성 검증으로 revision 9 완료. 별도 이력 업데이트 클릭 없이 새 COMPLETE 행이 표시됐다. operation 25914662-9b03-4b34-a37c-03b64c244836과 native revision 9/IN_SYNC/pending null이 일치했다.
- 마지막 정상 구성 확장 행에 검증된 runtime revision 9, operation UUID, 구성 SHA-256이 표시된다.
- 기준 DATA hash/inode/UID/GID/mode, boot ID 및 Samba master PID가 유지된다.
- 이 검증은 final UI #1275 및 모든 장기 operation 게이트의 완료를 의미하지 않는다.

![Chrome 검증된 runtime generation 상세](20261008-verified-runtime-generation.png)
