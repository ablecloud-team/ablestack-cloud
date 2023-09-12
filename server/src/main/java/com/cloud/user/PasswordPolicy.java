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
package com.cloud.user;

import org.apache.cloudstack.framework.config.ConfigKey;

public interface PasswordPolicy {

    ConfigKey<Integer> PasswordPolicyMinimumSpecialCharacters = new ConfigKey<>(
            "Advanced",
            Integer.class,
            "password.policy.minimum.special.characters",
            "1",
            "Minimum number of special characters that the user's password must have. The value 0 means the user's password does not require any special characters.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Integer> PasswordPolicyMinimumLength = new ConfigKey<>(
            "Advanced",
            Integer.class,
            "password.policy.minimum.length",
            "9",
            "Minimum length that the user's password must have. The value 0 means the user's password can have any length.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Integer> PasswordPolicyMaximumLength = new ConfigKey<>(
            "Advanced",
            Integer.class,
            "password.policy.maximum.length",
            "15",
            "Maximum length that the user's password must have. Password can be set to 15 digits or less",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Integer> PasswordPolicyMinimumUppercaseLetters = new ConfigKey<>(
            "Advanced",
            Integer.class,
            "password.policy.minimum.uppercase.letters",
            "1",
            "Minimum number of uppercase letters that the user's password must have. The value 0 means the user's password does not require any uppercase letters.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Integer> PasswordPolicyMinimumLowercaseLetters = new ConfigKey<>(
            "Advanced",
            Integer.class,
            "password.policy.minimum.lowercase.letters",
            "1",
            "Minimum number of lowercase letters that the user's password must have. The value 0 means the user's password does not require any lowercase letters.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Integer> PasswordPolicyMinimumDigits = new ConfigKey<>(
            "Advanced",
            Integer.class,
            "password.policy.minimum.digits",
            "1",
            "Minimum number of digits that the user's password must have. The value 0 means the user's password does not require any digits.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Boolean> PasswordPolicyAllowPasswordToContainUsername = new ConfigKey<>(
            "Advanced",
            Boolean.class,
            "password.policy.allowPasswordToContainUsername",
            "false",
            "Indicates if the user's password may contain their username. Set 'true' (default) if it is allowed, otherwise set 'false'.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<String> PasswordPolicyRegex = new ConfigKey<>(
            "Advanced",
            String.class,
            "password.policy.regex",
            ".+",
            "A regular expression that the user's password must match. The default expression '.+' will match with any password.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Boolean> PasswordPolicyAllowUseOfLastUsedPassword = new ConfigKey<>(
            "Advanced",
            Boolean.class,
            "password.policy.allowUseOfLastUsedPassword",
            "false",
            "Indicates whether the password used immediately before can be used for the user password. Set 'true' (default) if it is allowed, otherwise set 'false'.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Boolean> PasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers = new ConfigKey<>(
            "Advanced",
            Boolean.class,
            "password.policy.allowConsecutiveRepetitionsOfSameLettersAndNumbers",
            "false",
            "Indicates whether consecutive repetition of the same letter and number can be used in the user password. Set 'true' (default) if it is allowed, otherwise set 'false'.",
            true,
            ConfigKey.Scope.Domain);

    ConfigKey<Boolean> PasswordPolicyAllowContinuousLettersAndNumbersInputOnKeyboard = new ConfigKey<>(
            "Advanced",
            Boolean.class,
            "password.policy.allowContinuousLettersAndNumbersInputOnKeyboard",
            "false",
            "Indicates whether or not the user's password can contain consecutive letters and numbers on the keypad. Set 'true' (default) if it is allowed, otherwise set 'false'.",
            true,
            ConfigKey.Scope.Domain);

    /**
     * Checks if a given user's password complies with the configured password policies.
     * If it does not comply, a {@link com.cloud.exception.InvalidParameterValueException} will be thrown.
     * */
    void verifyIfPasswordCompliesWithPasswordPolicies(String password, String username, Long domainID);
}
