# SharedFS #1269 실제 UI/API 인수 완료

소스: 77b222a8d8c531c984ab1e2db274fcb2fc64172a

판정: PASS. 13번 환경의 실제 Chromium UI, 브라우저 한정 읽기 실패 주입, 별도의 직접 API 본문 검증을 완료했다.

| 완료 게이트 | 결과 |
|---|---|
| direct-existing-new-login-refresh-rapid-tabs | PASS |
| loaded-assets-source-pin | PASS |
| health-530-transport-timeout-preservation-and-UI-retry | PASS |
| initial-details-instances-failure-and-reference-VM-retry | PASS |
| overlap-request-coalescing | PASS |
| same-document-SPA-unmount-back-forward-no-listener-growth | PASS |
| actual-older-HTTP200-cannot-clear-newer-failure | PASS |
| light-dark-healthy-warning-and-UI-retry | PASS |
| unrouted-normal-reload-twice-with-HTTP-cache | PASS |
| direct-API-positive-and-identity-state-preserved | PASS |
| cleanup-and-no-uncontrolled-browser-errors | PASS |

원 FS 8개, Storage Service 8개, 원 VM Running 및 ROOT/DATA 2개 Ready의 UUID·상태·용량 매핑은 시험 전후 동일하다. F1의 현재 볼륨 5개는 모두 SPARSE/Ready이다. 자원 생성·삭제·기능 writer는 실행하지 않았다. 기존 원 시험 자원의 thin 디스크를 읽기만 했으며 신규 디스크 생성은 없다.

상세/목록 popstate 3/1/3/1/3은 Router 1개와 상세의 ResourceView·SharedFSTab 각 1개에 대응하며 증가가 없다. SPA 이탈의 취소 경로와, 실제 옛 HTTP 200이 새 오류를 덮지 않는 18b 경로를 구분하여 확인했다.

route 없는 별도 정상 로그인 context에서 일반 reload 2회를 수행했고 실제 HTTP cache 사용을 CDP로 관측했다. 두 번 모두 Ready/XFS/100GiB, spinner 0, partial 0과 현재 app/vendor/chunk URL을 확인했다. 마지막 정상·다크 오류 context는 logout HTTP 200 후 종료했다.

## 시험 하네스 보정 기록

- Initial route glob missed trailing slash: no injected failure was counted.
- First timeout follow-up saw automatic recovery before manual retry; isolated timeout/manual retry passed separately.
- First stale attempt held the newer request; only successful 18b temporal proof was counted.
- Optional hidden-volume empty response omitted array; exact count 0 and count-only envelope validated separately. Positive FS/VM/volume array assertions were retained.
- Initial NFS literal Ready predicate false had no runtime table; explicit details Ready/available/error/spinner/retry case passed.
- Ephemeral error notifications obstructed settings entry; final clean context used standard UI settings.

각 원시 보정 기록은 observations-public.json 및 final-theme-api-public.json에 보존했다. 실패 주입이 실제로 발생하지 않았거나 원래 의도와 순서가 다른 시도는 제품 인수 성공으로 세지 않았다.

## 이미지

- 02-new-login-original-details.png — SHA256 16dfbb7ff707b94e521c1bba6387d72944ac4c200395dc1fe09f20de0efedade
- 05-health530-partial.png — SHA256 5af41cfceeef087789222952e40ae2f53316e398531939982a4c4214b441330f
- 07-health-transport-partial.png — SHA256 8590384991430a87cc358df2de3a2de02db9f765a3515a74f9639e298c08bbd0
- 09-isolated-timeout-partial.png — SHA256 54abe6d16b70b4a39d18c03e49caf382f22eee77884703d9e4d8ad6042481bb1
- 09-isolated-timeout-ui-retry.png — SHA256 c98758d5b273c45de095d5d03151b061743cbad7279b9ae848d0c135b7d2c17a
- 11-reference-vm-failure.png — SHA256 39271674f3d35385f70f426304b4a0b3ebd3f0d488573b11f677322b2001b84f
- 14-initial-details-failure-Ready.png — SHA256 aaafea95037a90627ea169e7a8e2de1f8c30f9ea349de0e8645aff266dafc165
- 16-SPA-other-FS-after-old-release.png — SHA256 82005b173e0e0095c4891fbf49dd280eaaf30fd520747588e4a523f363dda947
- 18b-stale-success-newer-error-preserved.png — SHA256 cf010fca1a2ebaf09f42870a837148c6355619f9ad5bf49ba7682eaae98cc578
- 20-dark-NFS.png — SHA256 951efc663b5c632c52e00af7275c5c00c87fb64dfbef35b5a739a318c7576520
- 20-dark-details.png — SHA256 a0fc94f50947faec350afd997679772df07d15b3c590b17285d4fc75209e5482
- 20-light-details.png — SHA256 b68d03135e7565ccd224d150f16c3de9de5186ce38a1f69b4b753baeb4737295
- 22-unrouted-same-session-reload-1.png — SHA256 de97e97ccf15df37d446557bbea47a3c845dbf113ff9deec4f48d339f702ed5d
- 22-unrouted-same-session-reload-2.png — SHA256 de97e97ccf15df37d446557bbea47a3c845dbf113ff9deec4f48d339f702ed5d
- 25-dark-health530-partial.png — SHA256 48662fefc6e0b5b7e608f235332625f8f3abbabf114ac68881c141b8c01eaa79
- 25-dark-health530-ui-retry.png — SHA256 d6046c76c12d35e2e89e47b4c224527555c024ae4d01c452dab13db679bd0416

## 공개 증거

- observations-public.json — SHA256 965f72a9143e7c673d2788b0aa339266a9ae24ad9e4f31b8d01420da8ac133f4
- network-public.json — SHA256 eaa0382b6bba5ebc9b73c0164120f1182dd0c3f6695bd4510a2208fcc213de7c
- final-theme-api-public.json — SHA256 f181948bbe0e875bd4ae458264899fa1ab39aaf2fc4cf10da827879859bc61f6
- final-cache-public.json — SHA256 4a6b5a866ca0b2ffa4701f8a7776c182257f2f292a2ac2b3ee6d5f842a5a3a21
- final-dark-warning-public.json — SHA256 bd1d9788d0df19f94b2bacc570a04e5ea647d08a94d2c707bd07e2f5502cb7e5
- cleanup-privacy-public.json — SHA256 7869cb4ec03d56af1026f83244df832c21a3d7e1239fc891af99d00d9955f2c1
