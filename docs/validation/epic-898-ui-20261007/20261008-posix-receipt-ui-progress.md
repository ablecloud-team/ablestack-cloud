# POSIX 부팅 재적용 및 승인 UI 검증 — 2026-10-08

실제 재부팅에서 이전 미리보기의 UID65534가 저장된 요청에 남고 실제 적용된 UID0와 충돌하여 부팅 복구가 실패했다. 이 상태를 정상 복구로 판정하지 않았다.

`7b86b9f94598972fbdedfc4550a513d1cafec18a`는 적용 후 정확한 instance/policy/revision/volume/path/request SHA와 디렉터리의 filesystem/device/inode/UID/GID/mode/ACL SHA를 보호 증빙에 저장한다. 부팅 중 동일 요청과 현재 상태가 모두 일치할 때만 메타데이터 및 canonical desired 쓰기 없이 재적용을 생략한다. 기존 증빙 없는 정책과 foreign inode/FS/ACL은 자동 승인하지 않는다. 안정 런타임 native123+ROOT9 테스트 통과.

실제 설치 대상은 안정 base4a3b41에 POSIX 전용 패치만 더한 합성본이다. 에픽의 전체 rendered/AD 소스를 설치한 것으로 표시하지 않는다. CLI SHA `01b8b5f3928ffa766be49446d921a4be479c3257240457f73680f18c39e039aa`, archive `5652fa1af354ccedd2f69e0163992f0ec1c33d1899b43a0b7c10f3ab0055fc09`, signed manifest `dba8b87729ea64c8accc27e6e7fad6c18ea184e3416aa968a194eee7aefeabde`.

카탈로그 `9338fd6d-f176-4254-8e40-9d84fe96c9a7` AVAILABLE 서명 검증 후 VM50 실제 UI에서 사전 점검과 업그레이드 COMPLETE100%를 확인했다. 기존 b38 RuntimeUpgradeManagerImpl을 유지한 현장 혼합 구성의 버전 관측 UNKNOWN 제한은 그대로 남아 있으며, 최종 strict compatibility 전체 인수로 승격하지 않는다.

![서명 런타임 UI 완료](20261008-posix-receipt-runtime-ui-complete.png)

다음 재적용 UI 미리보기에서는 정책의 actual config0:0/0770 대신 NFS 권고65534:65534/0775가 예상 권한에 표시돼 적용을 취소했다. backend intent.config와 실제 apply payload는 저장된 정책을 정확히 사용함을 소스에서 확인했다. UI373a5ef02fb8은 미리보기 응답 config의 applyOwner/ownerUid/ownerGid/directoryMode로 예상값을 표시하고 소유권 보존이면 현재 관측 값을 사용한다. unrelated recommendation·미승인 draft로 대체하지 않는 3개 회귀를 포함해11개 테스트 및 lint가 통과했다. 생산 빌드/현장 새 UI 재적용/부팅 NOOP 증빙은 진행 중이다.

![표시 오류로 적용 중단](20261008-posix-preview-wrong-recommendation-held.png)

정책 실제 변경과 재부팅 성공 검증은 아직 완료하지 않았다. 최종 UI 표준화 #1275는 시작하지 않았으며 기능 완료 후 시작 직전에 전체 작업을 중단하고 사용자 지시를 기다린다.
