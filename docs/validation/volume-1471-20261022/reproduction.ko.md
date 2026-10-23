# #1471 원래 조건 재현과 종료 검사

1. 전용 계정/프로젝트에 startvm=false VM을 생성한다. Stopped, DB pod/host/last_host null, ROOT Allocated·pool/path null을 확인한다.
2. 같은 소유자의 legacy thin 1GiB DATA를 생성한다. Allocated·pool/path/instance_id null을 확인한다.
3. 원본 JAR에서 attachVolume을 실행한다. 31 job 14957/14959, 32 job 2273/2275의 NPE 실패와 연결/VM/볼륨 불변을 확인했다.
4. 최종 server 변경을 배포한다. 같은 VM·볼륨 UUID를 유지하고 먼저 기동하지 않은 상태에서 일반 사용자/프로젝트 UI로 검색·UUID 선택·연결한다.
5. 최종 UI job 14975/14979 및 2286/2288의 성공, 정확한 연결/소유권과 DATA Ready·실물 1GiB를 확인한다. VM placement는 여전히 null, ROOT는 Allocated여야 한다.
6. 그 뒤 첫 시작, Running/기동 이력이 있는 Stopped, Ready/Uploaded, 지원 정책 및 부적격 태그 실패를 확인한다. Uploaded는 64KiB 0x5a와 나머지 전체 zero tail을 검사한다.
7. 전용 자원만 정리한다. 활성 DB/이미지/VM·라우터 정의·임시 파일 0, 운영 자원 차이와 별도 이동 이력, 웹앱/설정/타 JAR·FT 작업 보존을 대조한다.

중간의 null 처리만 적용한 31 job 14968 실패는 최종 성공과 분리한다. 전체 Cloud CI·게스트 I/O·다중 풀 전체 회귀를 이 개별 결함 종료 검사와 구분한다.
