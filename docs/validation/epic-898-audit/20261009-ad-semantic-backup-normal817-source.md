# Epic #898 암호화 신원 백업·인증된 복원 정상 모듈 검증

Java 22개 파일 982d5ba0cc48bbb409df250c899124d10f5f11ac와 템플릿 5개 파일 b1c91c0b4910801b14078becc4ac9a86735c6c28을 검증해 반영했다. 정상 Maven -am package·Checkstyle 통과, 817개 테스트 / 124개 클래스, 실패·오류·생략 0이다. API/schema/server/KVM/storagevm immutable 산출물 6,277개 파일을 보존했다.

- Java 9,893개 경로·NUL·내용·NUL SHA-256: 60b87c9f3fa0c7801e8d97c69fbe0d45db3e927491cbd7fbc4d7ca4bc0f090fb (전후 동일).
- 정상 로그 SHA-256: 154e3b8820459a57555afb3d50a7a41a99d922d80e63c781b01ad1aad608d06b.
- native 14개와 템플릿/seed helper 등 총 19개 producer 파일 해시도 전후 동일.
- 기존 metadata_json·uuid 필드를 재사용하며 신규 DDL 0.

BACKUP 원래 행의 종류·완료 상태·소유자·source UUID·archive SHA·만료·원래 operation 완료 및 보호된 key/vault를 확인한 권한만 plan/apply에 연결했다. 중지 승인·SERVICE 원본 예약, domain-FIRST 가입, 암호화 LOCAL 복원·fresh AD principal, POSIX/share·all4 coordinator 호출을 연결했다. LOCAL 암호를 재생성하지 않는 실제 CRUD와 검토한 공유 UUID·별칭 매핑 소비를 검증했다.

Java 실제 RSA OAEP(SHA256/MGF1SHA256)+AES-GCM 캡처·descriptor를 DB 암호화 저장에서 복구해 committed CLI4c494의 sealed 입력으로 왕복 확인했다. 잘못된 RSA 키는 거부했고 비공개 PEM 출력은 없었다. 실제 Java SMB payload→committed pure renderer→격리 Samba4.22 testparm/net→읽기 전용 SID→Java 생성/보존 응답 검증도 통과했다. 4.17 실제 binary 인수로 확대하지 않는다.

새 Packer 이미지에만 고정 9개 seed 경로를 정리하고 writer/validator가 부재 증빙을 발급한다. 링크·실행 중인 알려진 identity 프로세스를 거부하는 8개 테스트와 seed metadata→Java AD clone 명시 template gate를 검증했다. 과거 이미지·운영 게스트는 변경하지 않았고 새 이미지 build는 0이다.

실제 13번 관리 서버 적용·AD·ROOT 교체·새 Cloud fixture는 0이다. production AD/fullFour는 false다. LKG TARGET의 별도 중지 신원 캡처·원본 보존·재개, ROOT의 opaque AD 역동작 및 전체 새 이미지·클러스터 API/UI/외부 I/O·Windows 인수는 남았다. 이 결과를 전체 Epic 완료로 표시하지 않는다. #902/#909/#920는 OPEN, 최종 UI #1275는 미착수다.
