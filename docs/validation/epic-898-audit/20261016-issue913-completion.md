# #913 SharedFS 네트워크 부팅 보존 검증

**기능 인수 완료.** L2 STATIC에서 초기 ifupdown과 늦은 cloud-init의 DHCP 재개입으로 주소·기본 경로가 유실되거나 추가되는 문제를 개선했다. 정상 빌드·13번 배포·기존 서비스와 신규 STATIC/DHCP의 재부팅·API/실제 Chrome·시험 자원 정리·원본 보존 검증을 완료했다. 이 문서는 완료 댓글과 이슈 종료 전에 발행하는 검증 근거이며, 종료 여부는 GitHub 상태를 기준으로 한다.

SharedFS 네트워크를 MAC에 묶고 초기 --bootstrap-only에서 선택 NIC의 ifupdown stanza를 안전한 manual로 만든 뒤 전용 unit을 networking보다 먼저 실행한다. 기존 alias·다른 NIC 바이트와 표준 empty include를 보존하며, 불명확한 hook/소유권·손상 상태는 거절한다. SharedFS용 cloud-init 제외 설정은 network rendering/activation 및 CloudStack DHCP fallback을 차단하고 NoCloud/ConfigDrive 사용자 데이터 경로는 유지한다. STATIC에 남은 미선언 dynamic 주소는 ADDRESS_MISMATCH로 표시하며 lease 권위 없이 삭제하지 않는다.

기존 gateway/선언 primary·CAS 보호를 재사용했다. KVM은 canonical helper/unit와 정확히 알려진 legacy boot 분기만 갱신한다. UI는 선언값과 실제 IP·route·DNS·DHCP·시각을 구분하고 ERROR/UNKNOWN을 정상으로 표시하지 않는다. NVMe 재부팅 장치명 변경은 고정 volume 신원으로 관측하며 canonical 원문을 다시 쓰지 않는다. 새 DB schema·공개 API는 없다.

| 검증 범위 | 확인 결과 |
|---|---|
| 정상 빌드 | Backend source347: 39 reactor·133 suite·1,142 tests·Checkstyle. Native 같은 source: 20 module·398 tests. UI source9cb 유지: 관련38 suite·940 tests·lint·production. 모두 실패/오류/skip0이며 서로 합산하지 않음. |
| 호스트3개 | wrapper1/helper1/network unit1만 변경. 새 agent PID344738/2347086/390092, 호스트별 다른247 JAR·배포 당시 VM process11/16/3 보존. MGT JAR563/PID2205817·UI2b695/configd3e 유지. |
| 기존65 hot/실제 lease | production installer 동안 NFS46 write/read·오류0. 격리된 실제 DHCP ACK2/renew/STATIC/expiry와 다른 NIC·원설정 보존/정리 PASS. |
| 기존65 cold11/Chrome | 정상 PW writer→Native17 뒤 정상 재부팅. 새 BOOT9ce76980…/IN_SYNC·current1f6119…·canonical7 원문·SID/cipher19·4 SPARSE 디스크 보존. 150초 이후3회 안정 및 같은 RAM 인증정보 NFS4.1/SMB3.1.1 파일 검증. 실제 UI all4 Ready·매핑/네트워크 확인. |
| 전체 이미지5 | Source347 정상 전체 빌드 exit0, 실측2,817초. RO attestation에서 설치14(직접 init5222 포함)·initial interfaces4dfe/empty include·image source321·서명/신원seed 검증 및 NBD/mount 정리 PASS. Native의608개 소스 파일 목록과 이미지의321개 소스 파일 대조는 테스트 수와 구분함. |
| 정상 private USER 등록 | TEST 공개 key0ae6…/단일 register POST/Ready template24dd891d…. 정확18 details hashcd7e0633…. qcow2 d9bbaec8…와 다운로드 bz2 5105397a…를 분리 검증. production 기본 SystemVM 교체0. |
| 신규 STATIC69 | 정상 create7965ad82…=1과 cold f92d164f…=1. 새 BOOT3ae34b22…에서153/155/158초 안정·단일237/16·default0·DHCPmasked/PID0, 실제 Chrome PASS. 선택 DNS 미지정은 기존 resolver 삭제를 뜻하지 않음. |
| 신규 DHCP70 | create3be87013…=1 및 정상 재부팅145df054…=1. BOOT 변경 뒤152/24·GW192.168.16.1·DNS[192.168.16.1,8.8.8.8]·전용 DHCP1·unit 정상. 기존 router 보존. 실제 Chrome에서도 같은 BOOT·DHCP tuple과 확인됨 표시 PASS. |

신규69/70 모두 SPARSE ROOT5,242,880,000B+DATA1GiB이며 실제 host QCOW2/noBacking/모든 필요 L2를 확인했다. 기존65의 **정식 CODE 원 소스는 bf869f26/CLI565**이고 network/template347·UI9cb와 같은 출처로 재표기하지 않는다. TEST 서명 private USER는 검증 전용이며 운영 기본/서명키 승인으로 확대하지 않는다.

STATIC69에 ROOT가 유한300초 DOC주소192.0.2.13/32를1회 추가했을 때 실제 API·Chrome이 ERROR/ADDRESS_MISMATCH와 두 주소를 표시했다. 정확한 소유 주소1회 제거 뒤 원상태 exact·실제 UI OK를 확인했다. 이 시험은 실제 DHCP lease라고 주장하지 않는다. 기존 상세문구가 주소/프리픽스 부재를 설명하는 한계는 원형대로 남기며, 추가주소 전용 문구로 보완했다고 주장하지 않는다.

최종 정상 정리는 destroySharedFileSystem3회와 deleteVolume5회, 총8 POST로 수행했고 모든 known job/동기 응답 성공·자동 재전송0을 확인했다. 뒤의 별도 **읽기 전용 witness는 POST0**이며3 FS·instance·VM와8 volume API 부재, 호스트3개 모두 domain/정확8 backing파일 부재를 확인했다. 정리 POST8과 관찰 POST0을 혼동하지 않는다.

최종 원본 RO 검사는 2026-10-16T22:00:17.812407+09:00에 PASS다. 원 collector55f485 소스는 바꾸지 않고 현재 UI index만 RAM에서 반영했다. F1 Native143·5디스크·BOOT/metadata, ref59 Native7·112B sentinel, retained61 Native2·원receipt2·85B canary/NFS Ready를 보존했다. 보호 partial은 API metadata만 비교해 completion/resume false·guest호출/변경0을 확인했고 기존 RECOVERY_REQUIRED10행 전체필드는 동일하다. MGT JAR563/PID2205817·UI/config, 호스트3개 Up/Enabled·새 agent active, pool factor4·실측 최소 여유3,752,053,248,000B를 확인했다.

공개 JSON의 기능 인수 상태는 FUNCTIONAL_ACCEPTANCE_COMPLETE이며 ROOT의 독립 대조를 완료했다. 구현과 본 근거는 기존 Epic PR #1271에 통합한다. #1269/#892/#897/#974의 완료 기록은 유지한다. 다음 제시 대상은 #914이며 자동 착수하지 않는다. #1275 최종 UI 표준 정리는 별도 시작 지시 전까지 미착수다. 새 이슈·개발 브랜치·PR은 생성하지 않았다.

이전66/67/68 및 cold/도구 false는 당시 실패·정리 결과로 보존하고 최종 통과로 덮지 않는다. JOINED AD DNS 우선순위는 Native 단위 회귀이며 실제 AD join/trust 재시험은 하지 않았다. 보호 partial에는 format/repair/mount/detach/delete/reboot를 하지 않으며 원본 전체 DATA checksum 검증이나 PNG export를 주장하지 않는다.

정상 실행별 수치·공개 scalar·실제 API/UI network wire·전체 SHA는 [공개 검증 근거](20261016-issue913-completion-public.json)에 있다. [설계](20261016-issue913-design.md)와 함께 읽는다. JSON의 sourceEvidencePath는 원형 prep 증거의 상대 위치이며 raw log/사설 snapshot/credential/archive를 복사하지 않았다. 최종25개 원형 SHA와 공개 scalar를 실제 증거에 대조했다.
