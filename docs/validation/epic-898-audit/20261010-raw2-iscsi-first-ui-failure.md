# RAW iSCSI 최초 UI 생성 실패와 보존 검증

F1의 기존 SPARSE 20GiB RAW 볼륨을 정상 UI로 iSCSI에 연결했으나 job 5df6d362-60e4-471a-8ea8-31cf5dc59519는 status2/code530으로 실패했다. 작업 df1641b6-c42a-443c-8521-a47fa6dc8c4f의 rev11은 RECOVERY_REQUIRED이며 native pending은 PREPARED다. 현재 구성은 GEN10을 유지한다. 이 시험을 성공한 target 생성이나 RAW I/O로 표시하지 않는다.

## 데이터와 구성 보존

ROOT는 /dev/sdb6이고 새 RAW는 /dev/sdd, serial db3de2b75fcb4cb5a0aa, 크기 21474836480B다. ROOT 제외, 파일 시스템·파티션·mount 없음, 앞64KiB와 offset1MiB/4096B 구역 blank를 확인했다. 실제 RAW 쓰기는0이다. NFS/SMB DATA 두 개의 XFS UUID·mount·NFS sentinel, BOOT·CLI·원 SOURCE reference·암호화 자료를 보존했다. 정상 checkpoint의 서비스 resume으로 PID는 바뀔 수 있으므로 이전 PID 고정을 보존 조건으로 오인하지 않는다.

iSCSI 볼륨은 실패한 정상 작업으로 연결됐고 NVMe 시험용 SPARSE RAW는 미연결이다. 포맷·새 볼륨·강제 완료·DB/journal 수동 변경은0이다. 현재 target/TPG/portal/LUN 및 관리 backstore가 없다는 관측은 첫 실패 단계의 증명이 아니다.

## 확인한 범위와 한계

실제 설치된 targetcli-fb 1:2.1.53-1.1, rtslib-fb 2.1.75-2.1의 의존성 검사와 RAW BLOCK 검사가 통과했다. 원 오류의 상세 stderr는 보존되지 않았고 현재 확정 가능한 내용은 민감 iSCSI native 적용의 exit1이다.

기존 vendor 소스를 재사용한 RAM 초기화 시험은 초기화/refresh까지 통과했다. 보호된 saved prefs의 선택 boolean·지원 레벨 검사도 통과하여 현재 unsupported loglevel 가설은 관측되지 않았다. 정확한 /iscsi create iqn.2026-10.local.epic898:f1-iscsi-raw20 명령을1회 검사한 결과 parser·WWN·dispatcher가 첫 configfs target mkdir 경계까지 도달했다. audit guard가 syscall 전에 생성1회를 차단했고 실제 target 생성은0이다. 예상 guard는 생성 경계의 호환성만 의미한다.

각 probe는 고정 GEN10/원 pending11/BOOT/CLI 및 자체 대상 부재 조건을 적용했다. 마지막 시험은 전후9메타 동일·자체 anonymous FD 종료·guest rc0/stdout1193B/stderr0을 확인했다. 원 targetcli main·실제 global prefs/lock·native apply/rollback는 호출하지 않았으므로 원 실패 원인을 입증하지 않는다. 비밀값·TDB 내용·원 stderr·credential·SID를 공개 증거로 저장하지 않았다.

기존 stored-reason JSON의 errorMessageBodyReadOrPrinted:false는 원 오류 본문을 출력하거나 필드로 추출하지 않았다는 뜻이다. 보호 JSON 전체는 RAM에서 읽었으므로 본문을 전혀 읽지 않았다는 증거로 사용하지 않는다. 최초 proof의 liveIdentityHolders:false도 정상 resume 뒤 PID 변경을 기록한 비교값이며 데이터 소실 증거로 해석하지 않는다.

custom IQN의 관리 prefix 불일치는 후속 인증/cleanup 검토 사항이다. ACL0의 최초 실패 원인으로 단정하거나 foreign target guard를 완화하지 않는다.

## 후속 작업

실패 단계와 제한된 오류 범주를 안전하게 보존하는 최소 제품 개선, 정상 UI의 원 작업 복구, 동일 볼륨의 정상 UI 재검증을 진행한다. iSCSI/CHAP/NVMe 실제 I/O는 복구와 Ready 검증 이후에 수행한다. 이 부분 결과로 Epic 또는 all-protocol atomic rollback #892를 닫지 않는다. 최종 UI 표준 #1275는 미착수이며 시작 직전에 사용자의 지시에 따라 중단·보고한다.

[evidence manifest](raw2-iscsi-first-failure-20261010/evidence-sha256.json)에 실제 공개 JSON의 SHA-256을 기록했다.
