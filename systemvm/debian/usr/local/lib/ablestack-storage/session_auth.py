#!/usr/bin/env python3

"""Secret-free iSCSI session authentication classification helpers."""

AUTH_VERIFIED = "VERIFIED"
AUTH_NOT_REQUIRED = "NOT_REQUIRED"
AUTH_UNKNOWN = "UNKNOWN"
AUTH_FAILED = "FAILED"


def parse_targetcli_auth_observation(text):
    value = str(text or "").upper()
    if "NOT AUTHENTICATED" in value:
        return "NOT_AUTHENTICATED"
    if "AUTHENTICATED" in value:
        return "AUTHENTICATED"
    return "UNSPECIFIED"


def classify_iscsi_auth_session(connection_state, auth_required, chap_configured,
                                 explicit_failure=False):
    logged_in = str(connection_state or "").upper() == "LOGGED_IN"
    if logged_in:
        if auth_required is False:
            return AUTH_NOT_REQUIRED
        if auth_required is True and chap_configured is True:
            return AUTH_VERIFIED
        return AUTH_UNKNOWN
    if explicit_failure:
        return AUTH_FAILED
    return AUTH_UNKNOWN


def compatibility_authenticated(auth_verification):
    if auth_verification == AUTH_VERIFIED:
        return True
    if auth_verification == AUTH_FAILED:
        return False
    return None
