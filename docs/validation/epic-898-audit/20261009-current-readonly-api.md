# 현재 관리 API 읽기 전용 상태

2026-10-08T17:15:51Z (2026-10-09 02:15:51 KST)의 새 API 로그인으로
정식 listStorageServiceInstances/listHosts/getStorageServiceControlPolicy를 조회했다.
7개 인스턴스가 Running이고 3개 Routing host가 Up/Enabled다.
서비스 7개의 정책은 enabled=false/revision=0/active=false이며 globalEnabled=false다.

이것은 프로세스 및 공개 정책 상태의 보존 증거다. partial10TiB의 FS 건강,
네 프로토콜 실제 I/O, source662 모듈 배포나 원래 서비스 전체 인수 성공을
뜻하지 않는다. writer/lease/drain/서비스 변경은 0회다.
비밀 필드 없이 저장한 proof는 current-readonly-api-20261009.json이다.

사전 helper의 오기 API는432, operation-specific API의 operationid 누락은431로
거절됐다. 정식 UI 정책 조회 명령을 소스에서 확인해 위 명령으로 검증했으며
어떤 상태 변경 요청도 수행하지 않았다.

최종 UI #1275는 미착수이며 착수 전 전체 중단·보고와 추가 지시 대기를 유지한다.
