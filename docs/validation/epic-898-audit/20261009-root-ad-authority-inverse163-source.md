# Epic #898 ROOT AD 원본 권한 및 소유된 JOIN 역동작 소스 검증

01788c642a28aa2c23b4777056bff2d31a57390d의 clean8을 반영했다. fresh clean163/working163 테스트, source before-after·독립 patch replay hash 일치, LOCAL NFS3+SMB4 총7개 출력 바이트 동일 및 sealed RAM synthetic signed bundle 검증을 확인했다. CLI SHA는 72afe4d6fb924e4c3d328ce414222b8598e12a5f451e348db0e406e7e5349b13이다. 첫 clean 후보의 inverse dispatcher 누락 실패1건은 별도 로그로 보존했고 실제 route 추가 뒤 최종163을 통과했다.

ROOT opaque 권한은 실제 RSA/AEAD 원본·정확한 source 구성·SID3·private config·alias/SPN·현재 boot를 검증한다. cold no-current의 bare marker만으로 권한을 인정하지 않고 정확한 ROOT4와 가져온 신원에 연결한다. 읽기 전용 SAM 조회는 신원을 생성하지 않는다.

JOIN은 새 컴퓨터 machine-auth SID를 custom SPN 이전에 durable journal에 저장한다. 원래 SOURCE가 미가입이며 로컬 SAM·구성·artifact inode와 외부 SID/SPN/DNS 소유가 일치할 때만 역동작을 수행한다. partial owned JOIN 정리·응답 유실 재호출 effect0, foreign SID/DNS/inode 변경 전 거부, disabled-but-present 및 삭제 후 SAM 소실의 RECOVERY 유지, 원래 JOINED source의 자동 역동작 거부를 검증했다.

외부 endpoint/daemon은 합성 관찰이며 실제 AD·13번·ROOT 교체는 0이다. production AD/fullFour는 false다. 서버 ROOT retain 요청의 정확한 opaque ref 전달과 externalJoinInverted 소비 후 완료 롤백, 일반 JOINED SERVICE의 authenticated decoded source·실제 SERVICE4 retain 재개 소비자가 남아 있다. 임의 scope3 retained callback·deferred capsule 출력 WIP는 이 커밋에 포함하지 않았다. 실제 isolated AD·same-VM ROOT 인수는 이후 새 artifact에서 수행한다. 최종 UI #1275는 미착수다.
