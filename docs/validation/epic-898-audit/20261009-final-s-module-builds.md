# 최종 동일 소스 모듈 검증 — 2026-10-09

기능 구현과 점유 NBD 보호 validator를 b6aa0338bd961496c4b61c207eb5ab75c142b306으로 고정했다. 같은 소스에서 Java·UI 모듈을 빌드했다. 아래는 소스와 모듈 검증이며 실제13번 최신 관리 서버 배포·새 이미지·전체 인수 완료를 의미하지 않는다.

| 범위 | 결과 |
| --- | --- |
| Java | 정상 Maven -am package·Checkstyle, 837 tests /124 classes, failure/error/skip0 |
| Java 소스 | 9893파일 NUL SHA 5820c23d1c8722b4ebdece30259c5f833693979de6189c0644cd3bc4f03e4fb8, 이전837검증·전후 동일 |
| immutable 모듈 | 5모듈6281파일. 이전6282 중 stale SQL2개 제거·공개시험 trust1개 추가. 공통6275파일 SHA동일, 5JAR의 공통 payload bytes동일 |
| UI | Node14.21.3, 244 tests /17 suites·lint·production850파일·config PASS |
| UI 소스 | 추적963파일 원본/후보/전후 NUL SHA 9afa86cb371c7d5e6a4d6693409ced0a749da56e71238df0642a5d44a473406e 동일 |
| 최소 관리 overlay 검토 | 기존219entry+공개trust1, 220entry/211classes/9792참조. 기존배포 라이브러리 signature/member resolution missing0. 기동·Spring 초기화·기능 인수는 별도 |

관리 overlay 후보 ZIP SHA ebaa853fb49d6fc811cb56f9f86a920320075f6057aad6a9d442628ad8ac73d0이며 baseline JAR ade51548bcdce95d6f9783458da009432c6f7f0904618b742000176c6f6443e5를 대상으로 검토했다. 공개 시험키 epic898-test-b6aa0338bd96의 SHA ed6db358cd07b64a8d5c31b59c770cc53a4a670c22ca2eba67091d18dd078358는 immutable모듈 및 빌드 source 주입 파일과 같다. 완성 이미지와 번들의 실제 공개 trust readback은 빌드 완료 후 검증한다. private 시험 signer는 sealed RAM에만 두며 정식 CI 서명으로 표시하지 않는다.

실제 Packer 이미지와 base/update/rollback runtime 번들 빌드는 진행 중이다. SSH 대기는 회복되었으며 커널6.12.95 설치와 Ganesha5.5.3 컴파일·설치를 진행했다. 전체 빌드 exit·ACL selftest·validator·manifest/서명 완료 전에는 성공으로 기록하지 않는다.

13번의 최신 읽기 조회에서 기존7개 SharedFS Running/3hosts UpEnabled와 관리 PID1189913·기존707 JAR·ca82 UI index를 확인했다. live UI config는 /webapp/config.json이 /etc/cloudstack/management/config.json을 가리키는 기존 symlink이며, 실제 내용 SHA d3e285317289b4eb4034141b63e4604ffd014d8bc1385aa28a007a4317c397f5다. 후보 생성config SHA44d080…와 구분하고 실제 배포에서 기존 링크와 내용을 보존한다.

새 SPARSE/FAT 검증 자원에서 API 및 실제 Chrome UI를 통한 all4·복원·ROOT·AD·작업제어 인수를 계속한다. SMB-Client OOBE 약관/새 암호와 정확한 신규10TiB 용량 선택은 기존 사용자 회신을 기다린다. 기존 DATA와 partial10TiB는 보존한다.

최종 UI표준 #1275는 미착수다. 해당 작업 시작 전에 전체 작업을 중단해 보고하고 추가 지시를 기다린다.

증빙: /root/work/epic898-preparation/final-s-java-b6aa/source-validation.json, output-count-comparison.json, /final-s-ui-b6aa/proof.json, output-manifest.json, /management-final-s-b6aa-review/manifest.json, /final-s-predeploy-current-readonly.json.
