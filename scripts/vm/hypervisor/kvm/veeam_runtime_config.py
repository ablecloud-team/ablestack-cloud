#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Read the Host Zone's Veeam connection from Mold without persisting it."""

import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import sys
from types import SimpleNamespace
from urllib.parse import urlsplit

CONTEXT = b"ablestack-veeam-runtime-config-v1"


def fetch_runtime(api, hostname):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    response = api.call("getVeeamRuntimeConfig", hostname=hostname, publickey=base64.b64encode(public).decode("ascii"))
    envelope = response.get("veeamruntimeconfig", response)
    try:
        if type(envelope.get("version")) is not int or envelope["version"] != 1:
            raise ValueError()
        key = private.decrypt(base64.b64decode(envelope["wrappedkey"], validate=True),
                              padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        nonce = base64.b64decode(envelope["nonce"], validate=True)
        if len(key) != 32 or len(nonce) != 12:
            raise ValueError()
        plaintext = AESGCM(key).decrypt(nonce, base64.b64decode(envelope["ciphertext"], validate=True), CONTEXT)
        settings = json.loads(plaintext)
        if (set(settings) != {"url", "username", "password", "hostname", "zoneid"}
                or any(not isinstance(value, str) or not value for value in settings.values())
                or settings["hostname"] != hostname):
            raise ValueError()
        url = urlsplit(settings["url"])
        if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password:
            raise ValueError()
        settings["sshhost"] = url.hostname
        return settings
    except Exception:
        raise ValueError("Mold Veeam runtime response could not be authenticated/decrypted or did not match this Host") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hostname", required=True)
    args = parser.parse_args()
    try:
        # Reuse the configuration tool's signed POST client. Authentication values
        # arrive through the process environment, never through command arguments.
        spec = importlib.util.spec_from_file_location("veeam_host_config", Path(__file__).with_name("veeam-host-config.py"))
        config = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(config)
        api = config.MoldApi(SimpleNamespace(mold_url=os.environ["MOLD_API_URL"],
                    admin_apikey=os.environ["MOLD_API_KEY"], admin_secretkey=os.environ["MOLD_API_SECRET"]))
        print(json.dumps(fetch_runtime(api, args.hostname), ensure_ascii=True))
    except Exception:
        print("ERROR: Cannot read Veeam connection from Mold; verify the matching management API, Root Admin authentication and Host Zone configuration", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
