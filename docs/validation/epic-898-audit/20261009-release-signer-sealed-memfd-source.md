# 정식 릴리즈 signer의 sealed RAM descriptor source 보완

d8201f42765의 helper·template build test2파일이 before/after/staged bytes와
일치하고 TestStorageTemplateBuild15 tests 및 diff가 통과했다.
memfd는 CLOEXEC/ALLOW_SEALING/root 소유0600이며 실제 WRITE/GROW/SHRINK/SEAL
네 봉인을 추가하고 F_GET_SEALS로 확인한다. 부분write를 끝까지 처리하고
보호 실패·child 시작 실패에서도 입력 bytearray wipe와 FDclose를 수행한다.

실제 write/shrink/grow/addseal EPERM, child의 필요한FD만 상속, 공개PEM만저장
회귀를 포함한다. descriptor의 변경금지를 증명하는 범위이며 모든 Python
불변문자열이나 전체processheap이 완전히 지워졌다고 주장하지 않는다.
초기fixture문제2개는3.9상수와mock범위를 바로잡았고 최종15PASS를 사용한다.

이 source단위의 stable key 사용·artifact signing·Cloud effects는0이다.
기존localprototype와protocol tests의 별도sealedRAMwrapper서명 계보를
변경하지 않는다. 조직의stableCI key403 미확인 게이트도해소했다고
표시하지 않으며 최종exactsource image/runtime/package/CI 인수를 이어간다.
최종UI #1275는 미착수다.

sourceproof=signing-sealed-memfd-source-validation.json,
testlogSHA784d65e978d0d4e9b92f7c026c6d3fca5463349f0a5c788c967eb7d1e087fc0f다.
