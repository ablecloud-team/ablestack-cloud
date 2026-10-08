# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Read-only absence evidence for a new appliance; never reads SID or password bytes."""
from pathlib import Path

SEED_PATHS = (
    "var/lib/samba/private/secrets.tdb",
    "var/lib/samba/private/passdb.tdb",
    "var/lib/samba/winbindd_idmap.tdb",
    "var/lib/samba/private/winbindd_idmap.tdb",
    "var/lib/samba/winbindd_cache.tdb",
    "etc/krb5.keytab",
    "etc/ablestack-storage/ad-machine.conf",
    "etc/ablestack-storage/smb-domain.json",
    "etc/ablestack-storage/smb-semantic-identity-aliases.json",
)


def identity_seed_absence(image_root):
    root = Path(image_root).absolute()
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Image root for identity absence is not a real directory")
    rows = []
    for relative in SEED_PATHS:
        path = root / relative
        parent = path.parent
        while parent != root:
            if parent.is_symlink():
                raise ValueError("Identity seed path has a linked parent")
            parent = parent.parent
        if path.exists() or path.is_symlink():
            raise ValueError("New template contains an identity seed: " + relative)
        rows.append({"path": relative, "absent": True})
    return {"schemaVersion": 1, "kind": "FRESH_TEMPLATE_IDENTITY_SEED_ABSENCE",
            "identitySeeds": rows, "uniqueMachineSidAlreadyClaimed": False}
