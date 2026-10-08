// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.response;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.storage.dataservice.StorageServiceTemplateUpgrade;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
@EntityReference(value=StorageServiceTemplateUpgrade.class)
public class StorageServiceTemplateUpgradeResponse extends BaseResponse {
    @SerializedName("id") @Param(description="Upgrade or SharedFS UUID") private String id;
    @SerializedName("result") @Param(description="Template compatibility, ROOT bindings, upgrade phases and verified rollback results") private String result;
    public void setId(String value){id=value;}
    public void setResult(String value){result=value;}
}
