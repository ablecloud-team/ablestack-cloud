#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

function usage() {
    cat << USAGE
Usage: ./package.sh -d DISTRO [OPTIONS]...
Package CloudStack for specific distribution and provided options.

If there's a "branding" string in the POM version (e.g. x.y.z.a-NAME[-SNAPSHOT]), the branding name will
be used in the final generated package like: cloudstack-management-x.y.z.a-NAME.NUMBER.el7.centos.x86_64
note that you can override/provide "branding" string with "-b, --brand" flag as well.

Mandatory arguments:
   -d, --distribution string               Build package for specified distribution ("centos7"|"centos8"|"rocky9")

Optional arguments:
   -p, --pack string                       Define which type of libraries to package ("oss"|"OSS"|"noredist"|"NOREDIST") (default "oss")
                                             - oss|OSS to package with only redistributable libraries
                                             - noredist|NOREDIST to package with non-redistributable libraries
   -r, --release integer                   Set the package release version (default is 1 for normal and prereleases, empty for SNAPSHOT)
   -s, --simulator string                  Build package for Simulator ("default"|"DEFAULT"|"simulator"|"SIMULATOR") (default "default")
   -b, --brand string                      Set branding to be used in package name (it will override any branding string in POM version)
   -T, --use-timestamp                     Use epoch timestamp instead of SNAPSHOT in the package name (if not provided, use "SNAPSHOT")
   -V, --package-version string            Override the package base version (for example 4.22.0.0)
       --timestamp-value string            Override the timestamp used with --use-timestamp (format YYYYMMDDHHMM)
   -S, --build-srpm                        Build SRPM alongside binary RPMs
   -L, --local-fast                        Skip local-only expensive packaging steps such as cloud-apidoc and cloud-marvin
   -t --templates                          Passes necessary flag to package the required templates. Comma separated string - kvm,xen,vmware,ovm,hyperv

Other arguments:
   -h, --help                              Display this help message and exit

Examples:
   package.sh --distribution centos7
   package.sh --distribution rocky9
   package.sh --distribution centos7 --pack oss
   package.sh --distribution centos7 --pack noredist
   package.sh --distribution centos7 --pack noredist -t "kvm,xen"
   package.sh --distribution centos7 --release 42
   package.sh --distribution centos7 --pack noredist --release 42

USAGE
    exit 0
}

PWD=$(cd $(dirname "$0") && pwd -P)
NOW="$(date +%Y%m%d%H%M)"

# packaging
#   $1 redist flag
#   $2 simulator flag
#   $3 distribution name
#   $4 package release version
#   $5 brand string to apply/override
#   $6 use timestamp flag
#   $7 package version override
#   $8 explicit timestamp value
#   $9 build SRPM flag
#   $10 local fast build flag
function packaging() {
    RPMDIR=$PWD/../dist/rpmbuild
    PACK_PROJECT=cloudstack
    WORKTREE_MUTATED="false"
    EXPLICIT_PACKAGE_VERSION="${7:-}"
    EXPLICIT_TIMESTAMP="${8:-}"
    BUILD_SRPM="${9:-false}"
    LOCAL_FAST="${10:-false}"

    OSSNOSS_VALUE=""
    if [ -n "$1" ] ; then
        OSSNOSS_VALUE="$1"
    fi
    SIM_VALUE=""
    if [ -n "$2" ] ; then
        SIM_VALUE="$2"
    fi
    if [ -n "$EXPLICIT_TIMESTAMP" ]; then
        NOW="$EXPLICIT_TIMESTAMP"
    fi

    if [ "$6" == "true" ]; then
        INDICATOR="$NOW"
    else
        INDICATOR="SNAPSHOT"
    fi

    DISTRO=$3
    case "$DISTRO" in
        rocky9)
            SPECDISTRO="centos8"
            ;;
        *)
            SPECDISTRO="$DISTRO"
            ;;
    esac

    MVN=$(which mvn)
    if [ -z "$MVN" ] ; then
        MVN=$(locate bin/mvn | grep -e mvn$ | tail -1)
        if [ -z "$MVN" ] ; then
            echo -e "mvn not found\n cannot retrieve version to package\n RPM Build Failed"
            exit 2
        fi
    fi

    CURRENT_VERSION=$(cd $PWD/../; $MVN -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null | tail -1)
    if [ -z "$CURRENT_VERSION" ] ; then
        CURRENT_VERSION=$(cd $PWD/../; $MVN org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\[' | tail -1)
    fi

    if [ -n "$EXPLICIT_PACKAGE_VERSION" ] ; then
        VERSION="$EXPLICIT_PACKAGE_VERSION"
    else
        VERSION="$CURRENT_VERSION"
    fi

    if [ -z "$VERSION" ] ; then
        echo -e "Unable to determine project version from Maven\n RPM Build Failed"
        exit 2
    fi

    if ! echo "$VERSION" | grep -q '^[0-9]\.' ; then
        echo -e "Package version must start with a numeric release like 4.22.0.0\n RPM Build Failed"
        exit 2
    fi

    REALVER=$(echo "$VERSION" | cut -d '-' -f 1)

    if [ -n "$5" ]; then
        BRAND_RAW="$5"
        BRAND="${BRAND_RAW}."
    else
        BASEVER=$(echo "$VERSION" | sed 's/-SNAPSHOT//g')
        BRAND_RAW=$(echo "$BASEVER" | cut -d '-' -f 2)

        if [ "$REALVER" != "$BRAND_RAW" ]; then
            BRAND="${BRAND_RAW}."
        else
            BRAND_RAW=""
            BRAND=""
        fi
    fi

    if [ "$6" == "true" ]; then
        if [ -n "$4" ] ; then
            REL_VALUE="${BRAND}${INDICATOR}.$4"
        else
            REL_VALUE="${BRAND}${INDICATOR}"
        fi
    elif echo "$VERSION" | grep -q SNAPSHOT ; then
        if [ -n "$4" ] ; then
            REL_VALUE="${BRAND}${INDICATOR}.$4"
        else
            REL_VALUE="${BRAND}${INDICATOR}"
        fi
    else
        if [ -n "$4" ] ; then
            REL_VALUE="${BRAND}$4"
        else
            REL_VALUE="${BRAND}1"
        fi
    fi

    TARGET_SOURCE_VERSION="$VERSION"
    if [ "$USE_TIMESTAMP" == "true" ]; then
        TARGET_SOURCE_VERSION="$REALVER"
        if [ -n "$BRAND_RAW" ]; then
            TARGET_SOURCE_VERSION="${TARGET_SOURCE_VERSION}-${BRAND_RAW}"
        fi
        TARGET_SOURCE_VERSION="${TARGET_SOURCE_VERSION}-${NOW}"
    elif [ -n "$BRAND_RAW" ]; then
        TARGET_SOURCE_VERSION="${REALVER}-${BRAND_RAW}"
    fi

    if [ "$TARGET_SOURCE_VERSION" != "$CURRENT_VERSION" ]; then
        branch=$(cd $PWD/../; git rev-parse --abbrev-ref HEAD)
        (cd $PWD/../; ./tools/build/setnextversion.sh --version "$TARGET_SOURCE_VERSION" --sourcedir . --branch "$branch" --no-commit)
        VERSION="$TARGET_SOURCE_VERSION"
        WORKTREE_MUTATED="true"
    fi

    TEMP_VALUE=""
    if [ "$TEMPLATES" != "" ]; then
      if [[ ",$TEMPLATES," = *",all,"* ]]; then
        TEMP_VALUE="-Dsystemvm-kvm -Dsystemvm-xen -Dsystemvm-vmware"
      else
        TEMP=-Dsystemvm-"${TEMPLATES//,/" -Dsystemvm-"}"
        TEMP_VALUE="${TEMP}"
      fi
    fi

    echo "Preparing to package Apache CloudStack $VERSION"

    mkdir -p "$RPMDIR/SPECS"
    mkdir -p "$RPMDIR/BUILD"
    mkdir -p "$RPMDIR/RPMS"
    mkdir -p "$RPMDIR/SRPMS"
    mkdir -p "$RPMDIR/SOURCES/$PACK_PROJECT-$VERSION"

    echo ". preparing source tarball"
    (cd $PWD/../; tar -c --exclude .git --exclude dist . | tar -C "$RPMDIR/SOURCES/$PACK_PROJECT-$VERSION" -x )
    (cd "$RPMDIR/SOURCES/"; tar -czf "$PACK_PROJECT-$VERSION.tgz" "$PACK_PROJECT-$VERSION")

    echo ". executing rpmbuild"
    cp "$PWD/$SPECDISTRO/cloud.spec" "$RPMDIR/SPECS"

    RPMBUILD_MODE="-bb"
    if [ "$BUILD_SRPM" == "true" ]; then
        RPMBUILD_MODE="-ba"
    fi

    UNITDIR=$(rpm --eval '%{_unitdir}' 2>/dev/null || true)
    if [ -z "$UNITDIR" ] || [ "$UNITDIR" == "%{_unitdir}" ]; then
        UNITDIR="/usr/lib/systemd/system"
    fi
    RPMBUILD_ARGS=(
        --define "_topdir ${RPMDIR}"
        --define "_ver ${REALVER}"
        --define "_fullver ${VERSION}"
        --define "_rel ${REL_VALUE}"
        --define "_unitdir ${UNITDIR}"
    )
    NODE_BIN_DIR=${RPM_NODE_BIN_DIR:-$(dirname "$(command -v node)")}
    if [ ! -x "$NODE_BIN_DIR/node" ] || [ ! -x "$NODE_BIN_DIR/npm" ]; then
        echo "Node.js toolchain not found in NODE_BIN_DIR=$NODE_BIN_DIR"
        exit 3
    fi
    RPMBUILD_ARGS+=(--define "_node_bindir ${NODE_BIN_DIR}")
    if [ -n "$TEMP_VALUE" ]; then
        RPMBUILD_ARGS+=(--define "_temp ${TEMP_VALUE}")
    else
        RPMBUILD_ARGS+=(--define "_temp %{nil}")
    fi
    if [ -n "$OSSNOSS_VALUE" ]; then
        RPMBUILD_ARGS+=(--define "_ossnoss ${OSSNOSS_VALUE}")
    fi
    if [ -n "$SIM_VALUE" ]; then
        RPMBUILD_ARGS+=(--define "_sim ${SIM_VALUE}")
    fi
    if [ "$LOCAL_FAST" == "true" ]; then
        RPMBUILD_ARGS+=(--define "_localfast 1")
    fi
    RPMBUILD_ARGS+=("$RPMBUILD_MODE" SPECS/cloud.spec)

    (
        cd "$RPMDIR"
        printf '. rpmbuild args:'
        printf ' %q' "${RPMBUILD_ARGS[@]}"
        printf '\n'
        rpmbuild "${RPMBUILD_ARGS[@]}"
    )
    if [ $? -ne 0 ]; then
        if [ "$WORKTREE_MUTATED" == "true" ]; then
            (cd $PWD/../; git reset --hard)
        fi
        echo "RPM Build Failed "
        exit 3
    else
        if [ "$WORKTREE_MUTATED" == "true" ]; then
            (cd $PWD/../; git reset --hard)
        fi
        echo "RPM Build Done"
    fi
    exit
}

TARGETDISTRO=""
SIM=""
PACKAGEVAL=""
RELEASE=""
BRANDING=""
USE_TIMESTAMP="false"
PACKAGE_VERSION_OVERRIDE=""
TIMESTAMP_VALUE=""
BUILD_SRPM="false"
LOCAL_FAST="false"

unrecognized_flags=""

while [ -n "$1" ]; do
    case "$1" in
        -h | --help)
            usage
            exit 0
            ;;

        -p | --pack)
            PACKAGEVAL=$2
            if [ "$PACKAGEVAL" == "oss" -o "$PACKAGEVAL" == "OSS" ] ; then
                PACKAGEVAL=""
            elif [ "$PACKAGEVAL" == "noredist" -o "$PACKAGEVAL" == "NOREDIST" ] ; then
                PACKAGEVAL="noredist"
            else
                echo "Error: Unsupported value for --pack"
                usage
                exit 1
            fi
            shift 2
            ;;

        -s | --simulator)
            SIM=$2
            if [ "$SIM" == "default" -o "$SIM" == "DEFAULT" ] ; then
                SIM="false"
            elif [ "$SIM" == "simulator" -o "$SIM" == "SIMULATOR" ] ; then
                SIM="simulator"
            else
                echo "Error: Unsupported value for --simulator"
                usage
                exit 1
            fi
            shift 2
            ;;

        -d | --distribution)
            TARGETDISTRO=$2
            if [ -z "$TARGETDISTRO" ] ; then
                echo "Error: Missing target distribution"
                usage
                exit 1
            fi
            shift 2
            ;;

        -r | --release)
            RELEASE=$2
            shift 2
            ;;

        -b | --brand)
            BRANDING=$2
            shift 2
            ;;

        -T | --use-timestamp)
            USE_TIMESTAMP="true"
            shift 1
            ;;

        -V | --package-version)
            PACKAGE_VERSION_OVERRIDE=$2
            shift 2
            ;;

        --timestamp-value)
            TIMESTAMP_VALUE=$2
            shift 2
            ;;

        -S | --build-srpm)
            BUILD_SRPM="true"
            shift 1
            ;;

        -L | --local-fast)
            LOCAL_FAST="true"
            shift 1
            ;;

        -t | --templates)
            TEMPLATES=$2
            shift 1
            ;;

        -*)
            unrecognized_flags="${unrecognized_flags}$1 "
            shift 1
            ;;

        *)
            shift 1
            ;;
    esac
done

if [ -n "$unrecognized_flags" ]; then
    echo "Warning: Unrecognized option(s) found \" ${unrecognized_flags}\""
    echo "         You're advised to fix your build job scripts and prevent using these"
    echo "         flags, as in the future release(s) they will break packaging script."
    echo ""
fi

if [ -n "$TIMESTAMP_VALUE" ] && ! echo "$TIMESTAMP_VALUE" | grep -Eq '^[0-9]{12}$'; then
    echo "Error: --timestamp-value must be in YYYYMMDDHHMM format"
    exit 1
fi

# Fail early if working directory is NOT clean and temporary versioning is required
if [ "$USE_TIMESTAMP" == "true" ] || [ -n "$BRANDING" ] || [ -n "$PACKAGE_VERSION_OVERRIDE" ]; then
    if [ -n "$(cd $PWD/../; git status -s)" ]; then
        echo "Erro: You have uncommitted changes and asked for temporary packaging version changes."
        echo "      The selected packaging flags are going to temporarily change POM versions"
        echo "      and revert them at the end of build, and there's no way we can do partial"
        echo "      revert. Please commit your changes first or omit the temporary version flags."
        exit 1
    fi
fi

echo "Packaging CloudStack..."
packaging "$PACKAGEVAL" "$SIM" "$TARGETDISTRO" "$RELEASE" "$BRANDING" "$USE_TIMESTAMP" "$PACKAGE_VERSION_OVERRIDE" "$TIMESTAMP_VALUE" "$BUILD_SRPM" "$LOCAL_FAST"
