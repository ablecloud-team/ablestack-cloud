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

"""ROOT codec-only TARGET publication; uses the same closed cipher validation."""
from service_target_cipher import ServiceTargetCipher
from root_identity_reference import RootIdentityReference

class RootTargetCipher(ServiceTargetCipher):
    prefix="root-identity-target"
    capture_kind="ROOT_IDENTITY_TARGET"
    cipher_kind="ROOT_TARGET_IDENTITY_CHECKPOINT"
    stopped_flag="rootTargetStoppedVerified"
    def scope(self,request):
        if "maintenanceUuid" in request:raise ValueError("ROOT TARGET cannot borrow SERVICE scope")
        return RootIdentityReference().scope(request)
