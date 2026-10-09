# 상세 조회 실패의 공개 원인 안내와 UI 모듈 배포

상세 조회가 일부 실패하면 실패한 섹션과 업데이트 버튼만 표시하던 경로에 고정된 원인 종류를 추가했다. 시간 초과·통신 실패·조회 실패를 구분하고 원문 오류/요청 context를 표시하지 않는다. 기존 값·Ready 상태·15초 조회 deadline·scope 변경 최대2회·중복 조회 병합·stale/unmount 소유권과 기존 재시도 버튼을 유지했다.

공통 readErrors 배열 계약을 유지하고 부가 errorKinds를 제공한다. 영어/한글 메시지3개씩 추가했으며 최초 조회 실패 notification도 공개 분류만 사용한다. 기존 경고의 list item 텍스트 외 template·CSS·탭 배치·모달/header/footer는 바꾸지 않았다. 이 변경은 #1269 조회 기능이며 최종 UI 표준 정리 #1275는 미착수다.

소스29de의 집중34 tests/4 suites(신규16), 정상366/21을 통과한 뒤 lint에서 테스트 형식3건을 확인했다. 원 후보/실패 기록을 보존하고 테스트2파일만 교정해 dc1453cc782로 고정했다. 생산4파일은 동일하다. 최종 정상366/21·lint --no-fix·production/config는 모두exit0이며 production은1회/709초/850파일이다. UI964 소스의 Git archive/canonical과 각 검증 단계 전후 SHA가 동일하다.

검토한10파일만 실제13번 관리 서버에 반영하고 index를 마지막에 원자적으로 교체했다. SourceMap도 해당 bundle과 함께 반영했으며 다른 hashed assets를 보존했다. 생성 config44d는 운영 configd3e와 달라 배포에서 제외했고 기존 symlink·WEB-INF·color.less·다른파일·관리 JAR15f361/PID1331803을 보존했다. 관리/agent/VM 재시작은0이다.

실제 적용 index는 e75592ad9a85cc4de523bf05d4c8e830ada0e49b988236925ba4b1f69f4291e8, 백업은 /root/epic898-ui-p0-read-causes-dc1453-backup-20261010-044425-c54ab6, 적용 시각은2026-10-10 04:44:31 KST다. 제공된10파일을 HTTP200/정확 크기/SHA로 다시 확인했다.

원래487a 공유의 기존세션·목록진입·직접URL·새로고침·빠른탭전환과 조회 상태 안내의 실제 브라우저 검증은 이 기록 시점에 진행 중이다. 정적 산출물/단위 render 성공을 실제 UI 인수로 대신하지 않으며, 실제 부분 실패 조건을 검증하지 못한 경우 미확인으로 남긴다. 이슈#1269는 아직OPEN이다.

[정상 모듈·실제 제한 배포의 공개 증거](20261010-p0-read-causes-ui-366-public-proof.json). 원본 SMB 복구의 [호스트 진단과 별도 실패 경계](20261010-kvm-safe-transport-diagnostic.md)는 별도로 추적한다.
