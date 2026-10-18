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

"""Run only the real CLI identity capability producer; no identity or generation main."""
from pathlib import Path
import ast
import contextlib
import importlib
import io
import json
import re
import sys

def capability(repository,request):
    if type(request)is not dict or set(request)!={"instanceUuid","operationUuid"}:
        raise ValueError("CAPABILITY_SCOPE_REQUIRED")
    scope=str(request["instanceUuid"])+":"+str(request["operationUuid"])
    if not re.fullmatch(r"[0-9a-fA-F-]{36}:[0-9a-fA-F-]{36}",scope):
        raise ValueError("CAPABILITY_SCOPE_INVALID")
    repository=Path(repository)
    library=repository/"systemvm/debian/usr/local/lib/ablestack-storage"
    if str(library)not in sys.path:sys.path.insert(0,str(library))
    actual=importlib.import_module("smb_source_checkpoint")
    source=(repository/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_text()
    start="<<'PYIDENTITY'\n";end="\nPYIDENTITY\n"
    if source.count(start)!=1 or source.count(end)!=1:raise ValueError("EXACT_IDENTITY_PRODUCER_SECTION")
    section=source.split(start,1)[1].split(end,1)[0]
    candidates=[]
    for node in ast.walk(ast.parse(section)):
        if not isinstance(node,ast.If):continue
        test=node.test
        if isinstance(test,ast.Compare)and isinstance(test.left,ast.Name)and test.left.id=="action" \
          and len(test.ops)==1 and isinstance(test.ops[0],ast.Eq)and len(test.comparators)==1 \
          and isinstance(test.comparators[0],ast.Constant)and test.comparators[0].value=="capabilities":
            candidates.append(node)
    if len(candidates)!=1:raise ValueError("ONE_REAL_IDENTITY_CAPABILITY_BRANCH")
    body=candidates[0].body
    if not all(isinstance(node,(ast.ImportFrom,ast.Expr))for node in body):
        raise ValueError("CAPABILITY_BRANCH_UNEXPECTED_EFFECT")
    out=io.StringIO()
    namespace={"json":json,"SmbSourceCheckpoint":actual.SmbSourceCheckpoint,"__name__":"actual_capability_branch_only"}
    with contextlib.redirect_stdout(out):
        exec(compile(ast.Module(body=body,type_ignores=[]),"actual_cli_identity_capability_branch","exec"),namespace)
    value=json.loads(out.getvalue())
    # protectedStdinTransport is added by the actual host wrapper / host transport
    # fixture, never forged here as a Native identity or signature verification.
    return value
