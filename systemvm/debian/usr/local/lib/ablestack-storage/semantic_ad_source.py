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

"""Read-only authentication of manager-vault AD semantic source material.

The manager binds the exact descriptor to the COMPLETE original BACKUP row,
archive SHA and hidden vault references before protected stdin delivery.
This consumer verifies that those delivered descriptor/cipher/key bytes agree;
a public caller dictionary alone is never original identity authority.
"""
import base64
import hashlib
import hmac
import json
import re
import uuid
from identity_capsule import decrypt,validate_payload,MAX_CAPSULE_BYTES
import ipaddress

SEMANTIC_AD_KIND="STORAGE_AD_SEMANTIC_SOURCE"
SEMANTIC_AD_FIELDS={"schemaVersion","kind","ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid","sourceConfigurationSha256","ciphertextSha256","issuerMac"}
SEMANTIC_CAPSULE_FIELDS={"schemaVersion","scope","wrappedKey","nonce","ciphertext","sha256"}


def semantic_original_source(request):
    # No files, processes or directory mutations occur in this parser.
    if not isinstance(request,dict) or "sourceIdentity" in request:
        raise ValueError("Caller plain source identity has no semantic authority")
    descriptor=request.get("originalSourceAuthority");capsule=request.get("originalSourceCapsule");pem=request.get("originalSourceCredentialPrivateKey")
    if (not isinstance(descriptor,dict) or set(descriptor)!=SEMANTIC_AD_FIELDS
            or type(descriptor.get("schemaVersion")) is not int or descriptor["schemaVersion"]!=1
            or descriptor.get("kind")!=SEMANTIC_AD_KIND):
        raise ValueError("AD semantic descriptor has an invalid closed shape")
    for field in ("ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid"):
        value=descriptor[field]
        if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError("AD semantic descriptor UUID is invalid")
    for field in ("sourceConfigurationSha256","ciphertextSha256","issuerMac"):
        if not isinstance(descriptor[field],str) or not re.fullmatch("[0-9a-f]{64}",descriptor[field]):
            raise ValueError("AD semantic descriptor digest/MAC is invalid")
    if (not isinstance(capsule,dict) or set(capsule)!=SEMANTIC_CAPSULE_FIELDS
            or type(capsule.get("schemaVersion")) is not int or capsule["schemaVersion"]!=1):
        raise ValueError("AD semantic source capsule has an invalid closed shape")
    expected_scope=descriptor["sourceInstanceUuid"]+":"+descriptor["sourceOperationUuid"]
    if capsule["scope"]!=expected_scope:raise ValueError("AD semantic original scope differs")
    for field,limit in (("ciphertext",(MAX_CAPSULE_BYTES+16)*2),("wrappedKey",4096),("nonce",64)):
        if not isinstance(capsule[field],str) or len(capsule[field])>limit:raise ValueError("AD semantic cipher encoding is unbounded")
    ciphertext=base64.b64decode(capsule["ciphertext"],validate=True)
    digest=hashlib.sha256(ciphertext).hexdigest()
    if not ciphertext or len(ciphertext)>MAX_CAPSULE_BYTES+16 or digest!=descriptor["ciphertextSha256"] or digest!=capsule["sha256"]:
        raise ValueError("AD semantic ciphertext digest differs")
    if not isinstance(pem,str) or len(pem)>65536:raise ValueError("AD semantic private unwrap key is unavailable")
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    private=serialization.load_pem_private_key(pem.encode(),password=None)
    if not isinstance(private,rsa.RSAPrivateKey) or private.key_size<2048:raise ValueError("AD semantic unwrap key is not supported RSA")
    der=private.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
    authenticated={key:value for key,value in descriptor.items() if key!="issuerMac"}
    canonical=json.dumps(authenticated,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()
    mac=hmac.new(hashlib.sha256(der).digest(),canonical,hashlib.sha256).hexdigest()
    if not hmac.compare_digest(mac,descriptor["issuerMac"]):raise ValueError("AD semantic issuer MAC differs")
    # Existing codec checks RSA OAEP, GCM authentication and exact original AAD.
    payload=decrypt(capsule,pem,expected_scope);validate_payload(payload)
    if payload.get("sourceConfigurationSha256")!=descriptor["sourceConfigurationSha256"]:
        raise ValueError("AD semantic decoded source configuration differs")
    identity=payload.get("adIdentity")
    required={"machineAccountSid","dnsAliases","idmapPolicy","machineConfigurationSha256"}
    if not isinstance(identity,dict) or not required<=set(identity):raise ValueError("AD semantic source lacks full joined authority")
    aliases=identity["dnsAliases"]
    if aliases!=sorted(aliases,key=lambda row:row["hostname"]) or len({row["hostname"] for row in aliases})!=len(aliases):
        raise ValueError("AD semantic source aliases are unnormalized")
    for alias in aliases:
        if alias["hostname"]!=alias["hostname"].lower() or alias["addresses"]!=sorted(set(alias["addresses"])):
            raise ValueError("AD semantic source alias addresses are unnormalized")
        for address in alias["addresses"]:
            if str(ipaddress.IPv4Address(address))!=address:raise ValueError("AD semantic source address is unnormalized")
    expected=sorted(service+"/"+row["hostname"] for row in aliases for service in ("host","cifs"))
    if sorted(identity["servicePrincipals"])!=expected or len(identity["servicePrincipals"])!=len(expected):
        raise ValueError("AD semantic source SPNs are not exact owned aliases")
    for field in ("machineSid","domainSid"):
        if any(int(part)>4294967295 for part in identity[field].split("-")[4:]):raise ValueError("AD semantic source SID subauthority is out of range")
    account=identity["machineAccountSid"]
    if int(account.rsplit("-",1)[1])>4294967295:raise ValueError("AD semantic computer SID RID is out of range")
    return {"descriptor":dict(descriptor),"identity":dict(identity),"payload":payload}


def semantic_new_target(original,public,source,instance_uuid):
    descriptor=original["descriptor"];identity=original["identity"]
    if instance_uuid==descriptor["sourceInstanceUuid"]:raise ValueError("NEW_INSTANCE cannot reuse the source native instance")
    if source.get("adIdentity") is not None:raise ValueError("NEW_INSTANCE target is already joined")
    if source.get("publicLocalMachineSid")==identity["machineSid"]:raise ValueError("NEW_INSTANCE target SAM equals the original source")
    if public["netbiosName"]==identity["netbiosName"]:raise ValueError("NEW_INSTANCE reuses the original computer name")
    if public["domain"]!=identity["domain"] or public["realm"]!=identity["realm"] or public["idmapPolicy"]!=identity["idmapPolicy"]:
        raise ValueError("NEW_INSTANCE domain or SID mapping differs from authenticated original")
    original_aliases={row["hostname"] for row in identity["dnsAliases"]}
    if any(row["hostname"] in original_aliases for row in public["dnsAliases"]) or set(public["servicePrincipals"])&set(identity["servicePrincipals"]):
        raise ValueError("NEW_INSTANCE cannot reuse original aliases/SPNs")
    return identity


def semantic_same_target(original,public,source,instance_uuid):
    identity=original["identity"]
    if instance_uuid!=original["descriptor"]["sourceInstanceUuid"]:raise ValueError("SAME_VM semantic source belongs to another instance")
    current=source.get("adIdentity")
    fields=("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","dnsAliases","idmapPolicy","machineConfigurationSha256")
    if (not isinstance(current,dict) or source.get("publicLocalMachineSid")!=identity["machineSid"]
            or any(current.get(key)!=identity.get(key) for key in fields)):
        raise ValueError("SAME_VM semantic source differs from its freshly captured joined authority")
    if any(public.get(key)!=identity.get(key) for key in ("domain","realm","workgroup","netbiosName","servicePrincipals","dnsAliases","idmapPolicy")):
        raise ValueError("SAME_VM semantic request replaces original domain identity")
    return identity
