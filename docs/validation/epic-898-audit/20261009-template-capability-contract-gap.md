# 템플릿 producer/consumer capability 키 불일치 발견

새 프로토타입의 보호된 registrationDetails를 그대로 정규 등록에 사용하는
경로를 검토해 P1 계약 불일치를 확인했다. manifest writer는
storage.service.template.data.identity.inspect=true를 생성하지만,
StorageTemplateCompatibility consumer는 storage.service.data.identity.inspect를
요구한다. 완성 fc3e prototype의 독립 readonly attestation도 producer 키만
존재함을 확인했다. 임의 template detail을 추가해 우회하지 않는다.

producer→consumer meaningful regression과 canonical capability 키 통일,
명시적 private USER fixture 선택·보호 scope의 정상 검증을 진행한다.
최소 버전의 네 번째 부분 비교와 비정상/다섯 부분 버전 거부도 해당 consumer의
후속 검토 대상으로 전달했다. 원래 SYSTEM 기본 선택과 원본 데이터는 보존한다.

현재 Cloud template 등록·새 Cloud VM 생성·ROOT 교체는 0회다. 이 발견은
로컬 SPARSE 이미지 빌드 성공을 취소하는 것이 아니라 관리 consumer 인수가
별도로 남아 있음을 뜻한다. 최종 UI #1275는 시작하지 않았다.
