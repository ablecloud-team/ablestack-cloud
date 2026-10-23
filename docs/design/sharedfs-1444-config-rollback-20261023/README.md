# #1444 설계 자료

- [코드 수준 설계·범위·검증·구현 계획](design.ko.md)
- [VM UI 기준·간격·다크/라이트·현행/개선 목업](ui-review.ko.md)
- [기계 판독 요약](summary.json)

설계 사전 시험만 수행했다. probes는 실제 class/guest 함수의 격리 호출 및 설계 스케치이며 제품 구현/전체 인수를 뜻하지 않는다. 운영 설정·계정·서비스·디스크는 변경하지 않았다.

Java probe는 기준 소스의 server target/test dependencies classpath에서 별도 임시 class 디렉터리로 컴파일한다. 설치 class SHA를 대조한 뒤 실행했다. Guest probe는 설치 storagectl SHA를 먼저 검사하고 TemporaryDirectory만 사용한다. iSCSI executor는 모형이고 NVMe 속성은 정규 파일이다. 실 targetcli/configfs를 이 코드로 바꾸지 않는다.

UI preview 재현은 [mockup 안내](mockup/README.md)를 따른다. 기존 node_modules만 사용하며 public/app.js 등 빌드 산출물/운영 자격 증명은 포함하지 않는다.
