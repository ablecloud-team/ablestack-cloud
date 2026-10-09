# 대용량 보호 입력의 메모리 청크 전송

원 복원의 관리 체크포인트는 이미 출판돼 있었지만 후속 import에 필요한 약1.5MB 입력이 QGA guest-exec의 PID 응답 전 시간 초과됐다. 같은 크기의 공개 입력도 재현됐고, 32KiB 청크의 공개 클러스터 시제품은 성공했다. [앞선 실제 재현과 시제품 보존](20261010-kvm-safe-transport-diagnostic.md)을 바탕으로 KVM 제품 전송을 수정했다.

소스 ad29797bef84b890e641ebdc606417037ddd7469는 wrapper와 회귀 테스트2파일만 변경한다. 32KiB 이하 입력은 기존 direct stdin을 유지한다. 큰 입력은 지원 RPC 확인 뒤 root 소유 anonymous memfd에 청크를 전송하고 write count·DATA close ACK·PID/start/argv/executable/inode를 확인한다. COMMIT 뒤 길이·SHA-256·전체 seals를 검증해 동일 CLI의 /dev/stdin에 전달한다. 입력의 정규 파일 fallback을 제공하지 않고 원문 argv·로그를 추가하지 않는다.

부분 전송·close 미확인·소유권 변경에서는 COMMIT하지 않는다. COMMIT 뒤 완료 응답을 잃으면 실행 여부 UNKNOWN으로 두고 CLI 자동 재호출이나 프로세스 신호를 보내지 않는다. receiver 대기는 최대300초이며 전송에 cleanup2초를 남긴다. 전체 CLI는 기존 command timeout을 유지하고 STATUS RPC에 남은 시간만 사용한다. 600초 command도 지원한다. 기존 capability 스키마와 작은 입력의 오류·성공 동작을 유지한다.

집중 Java19와 컴파일된 제품 receiver의 실제 로컬 kernel11 검사를 통과했다. 고정 커밋의 KVM 모듈 및 의존 모듈 정상 package 검사69/7 selectors·Checkstyle도 성공했고 실패·오류·생략은0이다. 소스 전후와 커밋 archive10764파일이 일치했다. 실제 소스 Java9903/KVM425/native142/UI719를 집계해 과거 수치를 재사용하지 않았다.

정상 산출물3050개를 별도 고정했다. 직전 immutable61과의 제품 차이는 Wrapper 클래스1개(SHA2eedeaf9)이고 API 및 다른 제품2665파일은 같다. 기존 public/protected14개 name·descriptor·visibility를 유지했다. checked InterruptedException 추가는 JVM descriptor를 바꾸지 않는다. fresh 실제 호스트5 JAR/12 class 및 JDK17에 대해 추가 member52/52를 해결했고 누락0이다.

[공개 검증 증빙](20261010-kvm-chunked-protected-input-normal-public-proof.json). 이 기록 시점에는 새 제품의 실제 호스트 배포·원 SOURCE의 정상 UI 복원·외부 LOCAL SMB 인증/I/O는 아직 수행 전이다. Native CLI·관리 서버·UI는 이번 수정에서 변경하지 않아 기존457/888/366 검증을 재사용한다. 최종 UI 표준 정리 #1275는 미착수이며 시작 전에 전체 작업을 중단·보고한다.
