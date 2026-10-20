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

package org.apache.cloudstack.storage.dataservice;

import java.util.regex.Pattern;
import com.cloud.exception.InvalidParameterValueException;

/** One public IQN grammar; a naming suffix never grants ownership of a kernel target. */
public final class StorageIscsiNamePolicy {
    private static final Pattern IQN=Pattern.compile("iqn\\.[0-9]{4}-[0-9]{2}\\.[A-Za-z0-9.-]+:[A-Za-z0-9_.:-]+");
    private StorageIscsiNamePolicy() { }
    public static boolean valid(String value) {return value!=null&&value.length()<=223&&IQN.matcher(value).matches();}
    public static String target(String value) {
        if(!valid(value))throw new InvalidParameterValueException("ISCSI_TARGET_IQN_INVALID");
        return value;
    }
    public static String initiator(String value) {
        if(!valid(value))throw new InvalidParameterValueException("ISCSI_INITIATOR_IQN_INVALID");
        return value;
    }
    public static String targetLun(String value) {return lun(value,false);}
    public static String mappedLun(String value) {return lun(value,true);}
    private static String lun(String value,boolean mapped) {
        String raw=value==null?"0":value;
        String error=mapped?"ISCSI_MAPPED_LUN_UNSUPPORTED":"ISCSI_TARGET_LUN_INVALID";
        if(!raw.matches("[0-9]+"))throw new InvalidParameterValueException(error);
        String canonical=raw.replaceFirst("^0+(?!$)","");
        int max=mapped?255:65535;
        if(canonical.length()>String.valueOf(max).length()||Integer.parseInt(canonical)>max)
            throw new InvalidParameterValueException(error);
        return canonical;
    }
}
