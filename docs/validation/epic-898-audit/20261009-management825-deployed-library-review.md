# AD 공통 승인·LKG·복제 825 모듈의 배포 라이브러리 대조

소스 ec40eb3f618bc5dd5b0bff4b78b68848928d356b의 정상 Maven·Checkstyle 825개 테스트/124개 클래스와 전후 동일 Java NUL SHA192f60e8…를 가진 immutable5 출력6279개에서 후보를 만들었다. live JAR을 변경하지 않았다.

기존 관리 서버 JAR ade51548bcdce95d6f9783458da009432c6f7f0904618b742000176c6f6443e5 및 읽기 전용으로 복사한 배포 의존 JAR9개의 라이브러리에 후보219 entry(클래스211/리소스8)를 대조했다. strict Runtime outer·$1·$RuntimeResourceScope를 같은 출력으로 포함했다. 클래스 signature/type211개와 constant-pool method/interface/field descriptor9768개가 누락0/exit0이다.

payload SHA256: 2dcbe62bbe66759b252526b71830f8dba589847cebfe7bdd2e3fdf68ac552e2f. 검증자료는 /root/work/epic898-preparation/management-source825-review/manifest.json 및 class-signature-link-proof.json, member-link-proof.json이다.

이 대조는 접근 제어·클래스 초기화·Spring 기동·실제 서비스 영향 또는 기능 인수 검증을 수행하지 않았다. readyToDeploy=false이며 현재 실제 서버는707 출력+기존 Runtime2다. 남은 ROOT forward/SERVICE/inverse 소비자와 최종 단일 source 출력에 대해 다시 적용 전 검토한다. 이슈 OPEN, 최종 UI#1275 미착수이다.
