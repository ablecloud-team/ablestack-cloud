# Epic #898 AD fresh 신원 조회 API 소스 검증

1849d3496d0c6cb22f9edc3a707a61967cfc4b3f의 API·DTO·관리 서버·테스트 4개 파일을 정상 Maven reactor package로 검증했다. Checkstyle 통과, 792개 테스트 / 123개 클래스 통과, 실패·오류·생략 0이다. immutable 5개 모듈의 6,272개 산출 파일을 별도 보존했다.

- Java 9,891개 경로·NUL·내용·NUL SHA-256: 1ebb657dd0ce231b7aa64751d63f98cbaecb86cef194b1eba2a0a656554b5eee (빌드 전후 동일).
- 정상 로그 SHA-256: dd764653a320095946c317efda15fc364106959a6776705da64be8265d219ec6.
- native AD·generation 모듈·CLI와 상태 producer AST 및 템플릿 writer/validator 해시가 빌드 전후 동일.
- 신규 DDL 0, 실제 클러스터 배포·AD 외부 효과·ROOT 교체 0.

listStorageServiceDomainStatus에 선택적 fresh=true를 추가했다. 정확한 instanceid 한 개를 요구하며, 현재 settled generation·구성 SHA·부팅 ID를 inspect 전후 비교한다. 저장된 domain SID·SAM SID·컴퓨터 계정·SPN·DNS·idmap binding과 새로운 읽기 observation scope 및 60초 신선도를 확인한다. VM·account·domain 재바인딩, pending generation·이전 부팅·stale/문자열 boolean·미가입·부분 신원 증빙은 거부한다. DAO 갱신과 native 설정 변경은 없다.

응답에는 별도의 typed identityreceipt를 반환한다. 기존 config.identityReceipt는 fresh 증거를 대신하지 않는다. 실제 ApiResponseSerializer.toJSONSerializedString의 JSON 응답·로그 경로에서 nested boolean·numeric scope·deep copy·비공개 필드 미노출을 확인했다. XML 구조 검증은 이 단위의 범위가 아니다.

34000dbd6eb의 실제 committed CLI 상태 producer를 보호된 임시 generation에서 두 번 실행해 /proc의 boot UUID와 모든 구성·세대 파일 불변을 확인했고, 이 실제 응답을 Java 소비자에 연결한 테스트를 포함했다. mock에 부팅 필드만 추가해 생산자 검증을 대체하지 않았다.

AD 생성·상세 화면은 가입 job 완료 후 이 새 조회를 확인하고 AD 권한을 적용하도록 후속 작업 중이다. typed JOIN/LEAVE·bootstrap·암호화 backup authority·의미 복원 및 실제 API/UI/Windows 인수는 남아 있다. #902는 OPEN, 최종 UI #1275는 착수하지 않는다.
