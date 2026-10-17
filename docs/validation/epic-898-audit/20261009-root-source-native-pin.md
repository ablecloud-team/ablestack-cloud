# ROOT 원본 캡처·재개 native 소스 검증

소스 4f6095c4e9d52bb1bcf650675ce9f18706e4cced의 8개 파일을 독립 candidate의
SHA-256과 대조해 모두 일치했다. AD 진행 중 변경은 이 단위에 포함하지 않았다.

ROOT 원본 baseline 캡처와 같은 원본으로의 재개에는 실제 자기 FD 9의 exclusive
flock, root 소유 0600 writer 파일의 inode, 유지보수 marker, PID/start/boot scope가
필요하다. 호출자가 임의 환경 변수나 skip flag로 이를 대체할 수 없다.
canonical 7개 파일은 존재 여부와 raw bytes SHA를 보존하며, 원본 재개가 새 native
generation을 seed하거나 증가시키지 않는다. 불확실한 재개는 marker를 유지하고
RECOVERY_REQUIRED로 남긴다.

CURRENT_PRIVATE_VAULT baseline은 canonical 설정, secret slot, NVMe instance와
SMB private DB의 일치를 확인한다. credential reference 존재 자체는 실제 인증
성공을 의미하지 않는다. 후속 실제 all4 인증과 I/O를 별도로 검증한다.

검증은 RootSourceRecovery 11, RenderedDriver 13, RenderedRuntime 10,
RenderedCredentials 18, InlineSources 5, Quiesce 7의 합계 64 focused tests다.
bash syntax, owned diff와 candidate bytes가 통과했다. 전체 suite를 재실행한
결과로 표시하지 않는다. proof는 native-root-source-clean-proof.json,
로그 SHA는 ecbdf98a12d89f24dc92893736b2078d8241d5e60364469c0907354e118fffa1이다.

실제 13번 적용, ROOT swap, source automaticPrevious 인증·I/O는 모두 0회다.
production fullFour와 AD identity 완료는 false다. 관리 서버의 marker-only enter →
source capture → quiesce 단계 연결과 서비스 중지 후 private DB 캡처, retained
latest-source AEAD 이관, AD lifecycle/POSIX authority 인수를 계속한다.

신규 디스크는 SPARSE/FAT만 사용한다. 최종 UI 표준화 #1275는 착수 직전 전체
작업을 중단·보고하고 추가 지시를 기다리는 조건을 유지하며 아직 시작하지 않았다.
