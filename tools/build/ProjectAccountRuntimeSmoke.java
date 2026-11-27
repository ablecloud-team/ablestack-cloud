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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Verifies that project account queries can link against the packaged API. */
public class ProjectAccountRuntimeSmoke {
    public static void main(String[] args) throws ReflectiveOperationException {
        ClassLoader loader = ProjectAccountRuntimeSmoke.class.getClassLoader();
        Class<?> command = Class.forName("org.apache.cloudstack.api.command.user.account.ListProjectAccountsCmd", false, loader);
        Class<?> query = Class.forName("com.cloud.api.query.QueryManagerImpl", false, loader);
        Class<?> response = Class.forName("org.apache.cloudstack.api.response.ListResponse", false, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        lookup.findVirtual(command, "getUsername", MethodType.methodType(String.class));
        lookup.findVirtual(query, "listProjectAccounts", MethodType.methodType(response, command));
        System.out.println("PASS packaged listProjectAccounts API and query linkage");
    }
}
