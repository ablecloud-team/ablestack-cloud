# 신규 블록 리스너 기본 포트 수정 정상 검증

실제 정상 UI iSCSI 요청3260이 LISTENER/SYSTEM_EXIT로 롤백됐다. Source의 ensureProtocol 신규 endpoint 생성자는 모든 endpoint 종류에2049를 넣고 있었다. 소스cf6baa는 이 한 표현식을 defaultProtocolPort(protocolType)으로 바꿔 NFS2049/SMB445/iSCSI3260/NVMe4420을 생성한다. 실제9e payload2049 추가관측과는 구분하며 소스 연결은 추론으로 표시했다.

기존 custom IP/port·default-null 재사용·SMB 특례와 row활성화 동작은 유지한다. Manager의 나머지 bytes 및 nativec265/KVM95c/API/UI/schema는 변경하지 않았다. 새 StorageProtocolDefaultListenerTest는 실제 Manager/DAO→payload→pinned c265 native 선택을 연결해6 tests를 통과했다. 요청과listener 교집합0/historical2049 negative 및 wrong-port targetprepare/persist0을 확인했고 checkpoint orchestration은 명시적으로 fixture 밖으로 구분했다.

정상 관리 모듈은128 selector/968 tests에서 failure/error/skipped0 및 Checkstyle/reactor가 통과했다. tracked15,586 전후NUL/archive 동등, immutable4,864파일을 보존했다. 실제 live09e/8a baseline 대비 server제품 차분은 Manager outer1(8fb2ed→0b307ad)이며 API/storagevm/inner0이다. KVM45e→95c는 이미배포한guard진단 계보이며 이번관리payload에는 포함하지 않는다. Native470은 변경없어 반복하지 않았다.

[정상 모듈·소스·산출물 proof](block-listener-default-normal/normal-core-proof.json). 실제관리runtimeABI/freshbaseline/단일클래스제한배포와 동일 RAW UI 재시험은 후속이며 #892전체완료나실제iSCSI I/O 완료로표시하지않는다. 최종UI #1275는미착수다.


## 실제 관리 클래스 제한 배포

fresh actual09e/PID1388662·oldManager8fb/Runtime3/libs/UI/config/trust52와 원FS8/VM21/volume33/F1op19/두catalogpins를 고정했다. 2601 member refs/기존ABI·계층 보존을 확인한 outer0b307 한 개를 protected root0700 staging에서 승인manifest/trustmap·O_EXCL attempt로 검증하고 원JAR백업·atomic overlay·mold재시작을 한 번 수행했다.

새 JAR935408703611ec4c53b455b9c453f53d4be3a94bc6d52efe222147cb9e5f4cc7/PID1400221이며 백업은 /root/epic898-default-listener-cf6baa48-backup-20261010-142009다. 최초 startup API connection refused는 별도 보존하고 추가재시작 없이 bounded wait 후 정상login/3Up/8Running과 모든원refs/cats051·e069 AVAILABLE/pin 동일을 확인했다.

선택class1 이외논리JAR내용·Runtime3/libs9/UI/configsymlink/trust52를 유지했다. 전체ZIP rawbyte동일성과추가Native/agent/sourceKey/ROOT/DATA/DDL/UI변경은주장하지않는다. apply1/moldrestart1/rollback0/guest0이며 생성기능수정의실제UI인수는후속이다.

[실제 제한 배포와 원 자원 보존 proof](block-listener-default-normal/management-one-actual-postcheck-public.json).
