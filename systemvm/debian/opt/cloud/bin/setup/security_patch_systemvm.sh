#!/bin/bash

###############################################################################
# SystemVM - Debian / Ubuntu
#
# Session Timeout : 900 seconds
#
# PAM / Password Security Policy
#
# Password:
#   MAX_DAYS : 90
#   MIN_DAYS : 1
#   WARN_AGE : 7
#
# Password Quality:
#   MINLEN   : 9
#   UPPER    : 1
#   LOWER    : 1
#   DIGIT    : 1
#   SPECIAL  : 1
#
# faillock:
#   FAILURES       : 5
#   UNLOCK_TIME    : 600 seconds
#   ROOT_LOCK      : enabled
#   ROOT_UNLOCK    : 600 seconds
#
# IMPORTANT:
#   - PAM configuration is backed up before modification.
#   - pam_faillock authsucc is used to clear failed-login records
#     after successful authentication.
#   - Do NOT use enforce_for_root in pwquality.conf.
#   - Keep an existing root SSH session open while applying/testing.
#
###############################################################################

set -u
set -o pipefail

###############################################################################
# Configuration
###############################################################################

HOSTNAME_NOW="$(hostname -s 2>/dev/null || hostname)"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

BACKUP_DIR="/root/pam_security_backup_$(date +%Y%m%d_%H%M%S)"

LOGIN_DEFS="/etc/login.defs"
PWQUALITY_CONF="/etc/security/pwquality.conf"
FAILLOCK_CONF="/etc/security/faillock.conf"

COMMON_AUTH="/etc/pam.d/common-auth"
COMMON_ACCOUNT="/etc/pam.d/common-account"
COMMON_PASSWORD="/etc/pam.d/common-password"
SSHD_CONFIG="/etc/ssh/sshd_config"
PAM_SSHD_CONFIG="/etc/pam.d/sshd"

PROFILE_FILE="/etc/profile"
MOTD_FILE="/etc/motd"
ISSUE_FILE="/etc/issue"
ISSUE_NET_FILE="/etc/issue.net"

WARNING_MSG='********************************************************************************
WARNING: Authorized access only.

This system is restricted to authorized users only.
Unauthorized access is prohibited.
All activities may be monitored and recorded.
********************************************************************************'

###############################################################################
# Root check
###############################################################################

if [ "$(id -u)" -ne 0 ]; then
    echo "[ ERROR ] root 권한으로 실행해야 합니다."
    exit 1
fi

###############################################################################
# Debian / Ubuntu check
###############################################################################

if [ ! -f /etc/debian_version ]; then
    echo "[ ERROR ] Debian / Ubuntu 계열이 아닙니다."
    exit 1
fi

echo "======================================="
echo "SystemVM 보안 정책 설정"
echo "======================================="

echo
echo "[INFO] OS 정보"

if [ -f /etc/os-release ]; then
    . /etc/os-release
    echo "  OS       : ${PRETTY_NAME:-Unknown}"
fi

echo "  Hostname : $HOSTNAME_NOW"
echo "  Scope    : SystemVM 템플릿 및 런타임"

###############################################################################
# Backup directory
###############################################################################

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

echo
echo "[INFO] 백업 디렉터리"
echo "       $BACKUP_DIR"

###############################################################################
# Backup function
###############################################################################

backup_file()
{
    local FILE="$1"

    if [ -f "$FILE" ]; then
        cp -a "$FILE" "$BACKUP_DIR/$(basename "$FILE").bak"

        echo "[BACKUP] $FILE"
        echo "         -> $BACKUP_DIR/$(basename "$FILE").bak"
    fi
}

###############################################################################
# [1] Session Timeout
###############################################################################

echo
echo "======================================="
echo "[1] Session Timeout 설정"
echo "======================================="

if [ -f "$PROFILE_FILE" ]; then

    backup_file "$PROFILE_FILE"

    # 기존 TMOUT 설정 제거
    sed -i \
        -e '/^[[:space:]]*TMOUT=/d' \
        -e '/^[[:space:]]*export[[:space:]]*TMOUT/d' \
        "$PROFILE_FILE"

    cat >> "$PROFILE_FILE" <<'EOF'

# Security Policy
TMOUT=900
export TMOUT
EOF

    echo "[ OK ] TMOUT=900 설정 완료"
    echo "      Session Timeout : 900초"

else
    echo "[ ERROR ] $PROFILE_FILE 파일이 없습니다."
    exit 1
fi

###############################################################################
# [2] Backup PAM files
###############################################################################

echo
echo "======================================="
echo "[2] PAM 설정 파일 백업"
echo "======================================="

backup_file "$LOGIN_DEFS"
backup_file "$PWQUALITY_CONF"
backup_file "$FAILLOCK_CONF"
backup_file "$COMMON_AUTH"
backup_file "$COMMON_ACCOUNT"
backup_file "$COMMON_PASSWORD"
backup_file "$SSHD_CONFIG"
backup_file "$PAM_SSHD_CONFIG"

###############################################################################
# [3] PAM package check
###############################################################################

echo
echo "======================================="
echo "[3] PAM 패키지 확인"
echo "======================================="

for PACKAGE_NAME in \
    libpam-modules-bin \
    libpam-modules \
    libpam-pwquality \
    libpam-runtime \
    libpam-systemd \
    libpam0g \
    libpwquality-common \
    libpwquality1
do
    if dpkg-query -W -f='${Status}' "$PACKAGE_NAME" 2>/dev/null |
        grep -q "install ok installed"; then
        echo "[ OK ] $PACKAGE_NAME 설치 확인"
    else
        echo "[ ERROR ] $PACKAGE_NAME 가 설치되어 있지 않습니다."
        exit 1
    fi
done

###############################################################################
# faillock command check
###############################################################################

if ! command -v faillock >/dev/null 2>&1; then

    echo "[INFO] faillock 명령을 찾을 수 없습니다."
    echo "[INFO] pam_faillock.so 파일 존재 여부만 확인합니다."

fi

if ! find /lib /lib64 /usr/lib /usr/lib64 \
        -name "pam_faillock.so" 2>/dev/null | grep -q .; then

    echo "[ ERROR ] pam_faillock.so를 찾을 수 없습니다."
    exit 1
fi

echo "[ OK ] pam_faillock 확인"

###############################################################################
# [4] Required files
###############################################################################

echo
echo "======================================="
echo "[4] 필수 파일 확인"
echo "======================================="

for FILE in \
    "$LOGIN_DEFS" \
    "$PWQUALITY_CONF" \
    "$COMMON_AUTH" \
    "$COMMON_ACCOUNT" \
    "$COMMON_PASSWORD"
do

    if [ ! -f "$FILE" ]; then
        echo "[ ERROR ] 파일 없음: $FILE"
        exit 1
    fi

done

echo "[ OK ] 필수 파일 확인 완료"

###############################################################################
# [5] /etc/login.defs
###############################################################################

echo
echo "======================================="
echo "[5] /etc/login.defs 설정"
echo "======================================="

set_login_defs()
{
    local KEY="$1"
    local VALUE="$2"

    # 기존 활성/주석 설정과 중복 설정을 모두 제거한 뒤 정책값을 하나만 추가
    sed -ri \
        "/^[[:space:]]*#?[[:space:]]*${KEY}[[:space:]]+.*/d" \
        "$LOGIN_DEFS"

    printf '%s    %s\n' "$KEY" "$VALUE" >> "$LOGIN_DEFS"
}

set_login_defs PASS_MAX_DAYS 90
set_login_defs PASS_MIN_DAYS 1
set_login_defs PASS_WARN_AGE 7

for POLICY in \
    "PASS_MAX_DAYS 90" \
    "PASS_MIN_DAYS 1" \
    "PASS_WARN_AGE 7"
do
    POLICY_KEY="${POLICY%% *}"
    POLICY_VALUE="${POLICY#* }"

    if ! grep -Eq "^[[:space:]]*${POLICY_KEY}[[:space:]]+${POLICY_VALUE}[[:space:]]*$" "$LOGIN_DEFS"; then
        echo "[ ERROR ] login.defs 정책 적용 실패: $POLICY"
        exit 1
    fi
done

echo "[ OK ] login.defs 설정 완료"

###############################################################################
# [6] Existing user password aging
###############################################################################

echo
echo "======================================="
echo "[6] 기존 사용자 비밀번호 만료 정책"
echo "======================================="

while IFS=: read -r USER X UID GID GECOS HOME SHELL
do

    case "$UID" in
        ''|*[!0-9]*)
            continue
            ;;
    esac

    # 일반 사용자만 적용
    [ "$UID" -ge 1000 ] || continue

    # nobody 제외
    [ "$USER" != "nobody" ] || continue

    # 로그인 불가 계정 제외
    case "$SHELL" in
        */nologin|*/false)
            echo "[SKIP] $USER : 로그인 불가"
            continue
            ;;
    esac

    if chage -M 90 -m 1 -W 7 "$USER" 2>/dev/null; then
        echo "[ OK ] $USER : MAX=90 MIN=1 WARN=7"
    else
        echo "[WARN] $USER : chage 적용 실패"
    fi

done < /etc/passwd

###############################################################################
# [7] pwquality.conf
###############################################################################

echo
echo "======================================="
echo "[7] pwquality.conf 설정"
echo "======================================="

set_pwquality()
{
    local KEY="$1"
    local VALUE="$2"

    # 기존 활성/주석 설정과 중복 설정을 모두 제거한 뒤 정책값을 하나만 추가
    sed -ri \
        "/^[[:space:]]*#?[[:space:]]*${KEY}[[:space:]]*=.*/d" \
        "$PWQUALITY_CONF"

    printf '%s = %s\n' "$KEY" "$VALUE" >> "$PWQUALITY_CONF"
}

set_pwquality difok 1
set_pwquality minlen 9
set_pwquality dcredit -1
set_pwquality ucredit -1
set_pwquality lcredit -1
set_pwquality ocredit -1

for POLICY in \
    "difok = 1" \
    "minlen = 9" \
    "dcredit = -1" \
    "ucredit = -1" \
    "lcredit = -1" \
    "ocredit = -1"
do
    if ! grep -Fxq "$POLICY" "$PWQUALITY_CONF"; then
        echo "[ ERROR ] pwquality 정책 적용 실패: $POLICY"
        exit 1
    fi
done

# root password 변경 시 pwquality 강제 옵션 제거
sed -i \
    '/^[[:space:]]*enforce_for_root[[:space:]]*$/d' \
    "$PWQUALITY_CONF"

echo "[ OK ] pwquality.conf 설정 완료"

###############################################################################
# [8] faillock.conf
###############################################################################

echo
echo "======================================="
echo "[8] faillock.conf 설정"
echo "======================================="

if [ ! -f "$FAILLOCK_CONF" ]; then

    touch "$FAILLOCK_CONF"
    chmod 644 "$FAILLOCK_CONF"

fi

# 기존 설정 제거
sed -i \
    -e '/^[[:space:]]*deny[[:space:]]*=/d' \
    -e '/^[[:space:]]*unlock_time[[:space:]]*=/d' \
    -e '/^[[:space:]]*root_unlock_time[[:space:]]*=/d' \
    -e '/^[[:space:]]*audit[[:space:]]*$/d' \
    -e '/^[[:space:]]*silent[[:space:]]*$/d' \
    -e '/^[[:space:]]*even_deny_root[[:space:]]*$/d' \
    "$FAILLOCK_CONF"

cat >> "$FAILLOCK_CONF" <<'EOF'

# SystemVM Security Policy

deny = 5
unlock_time = 600
root_unlock_time = 600
audit
silent
even_deny_root

EOF

chmod 644 "$FAILLOCK_CONF"

echo "[ OK ] faillock.conf 설정 완료"
echo "      실패 횟수 : 5회"
echo "      잠금 시간 : 600초"
echo "      root 잠금 : 적용"
echo "      root 해제 : 600초"

###############################################################################
# [9] Existing PAM configuration backup / inspection
###############################################################################

echo
echo "======================================="
echo "[9] 기존 PAM 구성 확인"
echo "======================================="

echo
echo "--- common-auth ---"
cat "$COMMON_AUTH"

echo
echo "--- common-account ---"
cat "$COMMON_ACCOUNT"

echo
echo "--- common-password ---"
cat "$COMMON_PASSWORD"

if [ -f "$PAM_SSHD_CONFIG" ]; then
    echo
    echo "--- sshd PAM ---"
    cat "$PAM_SSHD_CONFIG"
fi

###############################################################################
# [10] Safety check before modifying common-auth
###############################################################################

echo
echo "======================================="
echo "[10] PAM 구성 안전성 확인"
echo "======================================="

# 이미 pam_faillock이 여러 번 들어가 있으면 중복 설정 위험
FAILLOCK_COUNT=$(grep -Ec \
    '^[[:space:]]*auth[[:space:]].*pam_faillock\.so' \
    "$COMMON_AUTH" || true)

echo "[INFO] 현재 common-auth pam_faillock auth 라인 : $FAILLOCK_COUNT"

# 너무 복잡한 PAM 구성은 자동 덮어쓰기하지 않음
if grep -Eq \
    'pam_sss\.so|pam_ldap\.so|pam_winbind\.so|pam_pkcs11\.so|pam_ecryptfs\.so' \
    "$COMMON_AUTH"; then

    if [ "${SECURITY_PATCH_FORCE:-false}" = "true" ]; then
        echo "[WARN] common-auth에 SSSD/LDAP/Winbind/PKCS11/eCryptfs 관련 PAM 모듈이 존재합니다."
        echo "[WARN] 템플릿 생성 강제 적용 모드: common-auth를 보안 정책으로 덮어씁니다."
        echo "[INFO] 기존 설정 백업 위치: $BACKUP_DIR"
    else
        echo
        echo "[ ERROR ]"
        echo "common-auth에 SSSD/LDAP/Winbind/PKCS11/eCryptfs 관련 PAM 모듈이"
        echo "존재합니다."
        echo
        echo "인증 체계를 보존하기 위해 common-auth 자동 덮어쓰기를 중단합니다."
        echo
        echo "백업 위치:"
        echo "  $BACKUP_DIR"
        echo
        exit 1
    fi
fi

###############################################################################
# [11] common-auth
###############################################################################

echo
echo "======================================="
echo "[11] common-auth 최종 적용"
echo "======================================="

cp -p "$COMMON_AUTH" \
    "$BACKUP_DIR/common-auth.before-faillock"

cat > "$COMMON_AUTH" <<'EOF'

#
# /etc/pam.d/common-auth
#
# SystemVM Security Policy
#

# Check whether account is locked
auth    required    pam_faillock.so preauth silent

# Unix password authentication
#
# success=1:
# pam_faillock authfail을 건너뛰고
# 다음 authsucc로 이동
#
auth    [success=1 default=bad]    pam_unix.so nullok

# Record failed authentication
auth    [default=die]    pam_faillock.so authfail

# Clear previous failed authentication records
# after successful authentication
auth    sufficient    pam_faillock.so authsucc

# Final authentication failure
auth    required    pam_deny.so

EOF

chmod 644 "$COMMON_AUTH"

echo "[ OK ] common-auth 적용 완료"

###############################################################################
# [12] Clear existing root faillock record
###############################################################################

echo
echo "======================================="
echo "[12] 기존 root faillock 기록 초기화"
echo "======================================="

if command -v faillock >/dev/null 2>&1; then

    faillock --user root --reset 2>/dev/null || true

    echo "[ OK ] root faillock 기록 초기화"

else

    echo "[WARN] faillock 명령을 찾을 수 없습니다."

fi

###############################################################################
# [13] PAM syntax / module check
###############################################################################

echo
echo "======================================="
echo "[13] PAM 구성 확인"
echo "======================================="

echo
echo "--- common-auth ---"

nl -ba "$COMMON_AUTH"

echo
echo "--- pam_faillock module ---"

find /lib /lib64 /usr/lib /usr/lib64 \
    -name "pam_faillock.so" 2>/dev/null | sort -u || true

echo
echo "--- common-account ---"

grep -nE \
    'pam_faillock|pam_unix|pam_deny|pam_permit' \
    "$COMMON_ACCOUNT" || true

echo
echo "--- common-password ---"

grep -nE \
    'pam_pwquality|pam_unix|pam_deny|pam_permit' \
    "$COMMON_PASSWORD" || true

###############################################################################
# [14] Current policy
###############################################################################

echo
echo "======================================="
echo "[14] 현재 적용 정책"
echo "======================================="

echo
echo "[Session Timeout]"

grep -nE \
    '^[[:space:]]*TMOUT=|^[[:space:]]*export[[:space:]]+TMOUT' \
    "$PROFILE_FILE" || true

echo
echo "[login.defs]"

grep -E \
    '^[[:space:]]*(PASS_MAX_DAYS|PASS_MIN_DAYS|PASS_WARN_AGE)[[:space:]]+' \
    "$LOGIN_DEFS" || true

echo
echo "[pwquality.conf]"

grep -E \
    '^[[:space:]]*(difok|minlen|dcredit|ucredit|lcredit|ocredit)[[:space:]]*=' \
    "$PWQUALITY_CONF" || true

echo
echo "[faillock.conf]"

grep -E \
    '^[[:space:]]*(deny|unlock_time|root_unlock_time|audit|silent|even_deny_root)' \
    "$FAILLOCK_CONF" || true

###############################################################################
# [15] root status
###############################################################################

echo
echo "======================================="
echo "[15] root 계정 상태"
echo "======================================="

passwd -S root 2>/dev/null || true

echo
echo "[root password aging]"

chage -l root 2>/dev/null || true

echo
echo "[root faillock]"

faillock --user root 2>/dev/null || true


###############################################################################
# [16] Warning Message
###############################################################################

echo
echo "======================================="
echo "[16] 로그인 경고문 설정"
echo "======================================="

configure_warning_message()
{
    local FILE

    for FILE in "$MOTD_FILE" "$ISSUE_FILE" "$ISSUE_NET_FILE"; do
        backup_file "$FILE"
        printf '%s\n' "$WARNING_MSG" > "$FILE"
        chown root:root "$FILE"
        chmod 644 "$FILE"
        echo "[ OK ] $FILE 설정 완료"
    done

    if [ -f "$SSHD_CONFIG" ]; then
        backup_file "$SSHD_CONFIG"

        sed -i -E '/^[[:space:]]*#?[[:space:]]*Banner[[:space:]]+/d' "$SSHD_CONFIG"
        printf '%s\n' 'Banner /etc/issue.net' >> "$SSHD_CONFIG"
        echo "[ OK ] SSH Banner 설정 완료"
    else
        echo "[WARN] SSH 설정 파일이 없습니다: $SSHD_CONFIG"
    fi

    if [ -d /etc/update-motd.d ]; then
        for FILE in /etc/update-motd.d/*; do
            if [ -f "$FILE" ]; then
                chmod -x "$FILE"
            fi
        done
        echo "[ OK ] Dynamic MOTD 비활성화 완료"
    fi

    if [ -f "$PAM_SSHD_CONFIG" ]; then
        backup_file "$PAM_SSHD_CONFIG"
        sed -i -E \
            's|^[[:space:]]*session[[:space:]]+.*pam_motd\.so.*motd=/run/motd\.dynamic.*$|session optional pam_motd.so|' \
            "$PAM_SSHD_CONFIG"
        echo "[ OK ] PAM MOTD 설정 확인 완료"
    fi

    if systemctl restart ssh 2>/dev/null || systemctl restart sshd 2>/dev/null; then
        echo "[ OK ] SSH 서비스 재시작 완료"
    else
        echo "[WARN] SSH 서비스 재시작 실패"
    fi
}

configure_warning_message

###############################################################################
# [17] Backup
###############################################################################

echo
echo "======================================="
echo "[17] 백업 정보"
echo "======================================="

echo
echo "백업 디렉터리:"
echo "  $BACKUP_DIR"

echo
echo "백업 파일:"

ls -la "$BACKUP_DIR" 2>/dev/null || true


###############################################################################
# Final
###############################################################################

echo
echo "======================================="
echo " 설정 완료"
echo "======================================="

echo
echo "Session Timeout:"
echo "  TMOUT          : 900초"

echo
echo "비밀번호 정책:"
echo "  최대 사용기간 : 90일"
echo "  최소 사용기간 : 1일"
echo "  만료 경고     : 7일"
echo "  최소 길이     : 9"
echo "  대문자        : 1개 이상"
echo "  소문자        : 1개 이상"
echo "  숫자          : 1개 이상"
echo "  특수문자      : 1개 이상"

echo
echo "로그인 실패 정책:"
echo "  실패 횟수     : 5회"
echo "  잠금 시간     : 600초"
echo "  root 잠금     : 적용"
echo "  root 해제     : 600초"

echo
echo "PAM:"
echo "  pam_faillock preauth : 적용"
echo "  pam_faillock authfail: 적용"
echo "  pam_faillock authsucc: 적용"
echo "  root 성공 로그인     : 실패 카운터 초기화"

echo
echo "백업:"
echo "  $BACKUP_DIR"

exit 0