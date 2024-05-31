/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.storage.command.browser;

import com.cloud.agent.api.Answer;


public class ListRbdObjectsAnswer extends Answer {

    private boolean successMessage;

    private String message;

    private String names;

    public ListRbdObjectsAnswer(boolean successMessage, String names) {
        super();
        this.successMessage = successMessage;
        this.names = names;
    }
    public boolean successMessage() {
        return successMessage;
    }
    public String getMessage() {
        return message;
    }
    public String names() {
        return names;
    }
    public String getNames() {
        return names;
    }
}
