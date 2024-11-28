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

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;

import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserAuthenticator.ActionOnFailedAuthentication;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

public class PasswordPolicyImpl implements PasswordPolicy, Configurable {

    private Logger logger = LogManager.getLogger(PasswordPolicyImpl.class);

    @Inject
    private UserDao userDao;
    @Inject
    private AccountDao accountDao;

    protected List<UserAuthenticator> _userPasswordEncoders;

    public List<UserAuthenticator> getUserPasswordEncoders() {
        return _userPasswordEncoders;
    }

    public void setUserPasswordEncoders(List<UserAuthenticator> encoders) {
        _userPasswordEncoders = encoders;
    }

    public void verifyIfPasswordCompliesWithPasswordPolicies(String password, String username, Long domainId) {
        int numberOfSpecialCharactersInPassword = 0;
        int numberOfUppercaseLettersInPassword = 0;
        int numberOfLowercaseLettersInPassword = 0;
        int numberOfDigitsInPassword = 0;

        char[] splitPassword = password.toCharArray();


        for (char character: splitPassword) {
            if (!Character.isLetterOrDigit(character)) {
                numberOfSpecialCharactersInPassword++;
            } else if (Character.isUpperCase(character)) {
                numberOfUppercaseLettersInPassword++;
            } else if (Character.isLowerCase(character)) {
                numberOfLowercaseLettersInPassword++;
            } else if (Character.isDigit(character)) {
                numberOfDigitsInPassword++;
            }
        }

        verifyUserNameLabel(username, true); //사용자이름

        validateIfPasswordContainsTheMinimumNumberOfSpecialCharacters(numberOfSpecialCharactersInPassword, username, domainId); //최소 특수문자 포함 수(1개이상)
        validateIfPasswordContainsTheMinimumNumberOfUpperCaseLetters(numberOfUppercaseLettersInPassword, username, domainId);   //최소 대문자 포함 수(1개이상)
        validateIfPasswordContainsTheMinimumNumberOfLowerCaseLetters(numberOfLowercaseLettersInPassword, username, domainId);   //최소 소문자 포함 수(1개이상)
        validateIfPasswordContainsTheMinimumNumberOfDigits(numberOfDigitsInPassword, username, domainId);
        validateIfPasswordContainsTheMinimumLength(password, username, domainId);   //9자 이상
        validateIfPasswordContainsTheMaximumLength(password, username, domainId);   //15자 미만
        validateIfPasswordContainsTheUsername(password, username, domainId);
        validateIfPasswordMatchesRegex(password, username, domainId);
        validateIfPasswordContainsThePreviousPassword(password, username, domainId);
        validateIfPasswordContainsConsecutiveRepetitionOfTheSameLetterAndNumber(password, username, domainId);//동일한 문자/숫자 4개이상 사용못함
        validateIfPasswordContainsContinuousLettersAndNumbersInputOnTheKeyboard(password, username, domainId);
    }

    public static boolean verifyUserNameLabel(final String username, final boolean isUserName) {
        if (username.length() > 15 || username.length() < 9) {
            throw new InvalidParameterValueException("사용자 이름은 9~14자 사이여야 합니다.");
        } else if (!username.toLowerCase().matches("[a-z0-9-]*")) {
            throw new InvalidParameterValueException("사용자 이름에는 ASCII 문자 'a'부터 'z'까지만 포함될 수 있습니다(대소문자를 구분하지 않음).");
        } else if (username.startsWith("-") || username.endsWith("-")) {
            throw new InvalidParameterValueException("사용자 이름은 하이픈과 숫자로 시작할 수 없으며 하이픈으로 끝나서는 안 됩니다.");
        } else if (isUserName && username.matches("^[0-9-].*")) {
            throw new InvalidParameterValueException("사용자 이름은 숫자로 시작할 수 없습니다.");
        }

        return true;
    }

    protected void validateIfPasswordContainsTheMinimumNumberOfSpecialCharacters(int numberOfSpecialCharactersInPassword, String username, Long domainId) {
        Integer passwordPolicyMinimumSpecialCharacters = getPasswordPolicyMinimumSpecialCharacters(domainId);

        logger.trace(String.format("사용자 [%s]의 새 암호에 구성 [%s]에 정의된 최소 특수 문자 수 [%s]가 포함되어 있는지 확인하는 중입니다.",
                username, passwordPolicyMinimumSpecialCharacters, PasswordPolicyMinimumSpecialCharacters.key()));

        if (passwordPolicyMinimumSpecialCharacters == 0) {
            logger.trace(String.format("사용자 비밀번호의 최소 특수 문자 수는 0입니다. 따라서 사용자 [%s]의 새 암호에 대한 특수 문자 수를 확인하지 않습니다.", username));
            return;
        }

        if (numberOfSpecialCharactersInPassword < passwordPolicyMinimumSpecialCharacters) {
            logger.error(String.format("User [%s] informed [%d] special characters for their new password; however, the minimum number of special characters is [%d]. "
                            + "Refusing the user's new password.", username, numberOfSpecialCharactersInPassword, passwordPolicyMinimumSpecialCharacters));
            throw new InvalidParameterValueException(String.format("사용자 비밀번호에는 최소한 [%d]개의 특수 문자가 포함되어야 합니다.", passwordPolicyMinimumSpecialCharacters));
        }

        logger.trace(String.format("사용자 [%s]의 새 암호는 최소 특수 문자 [%s] 정책을 준수합니다.", username,
                PasswordPolicyMinimumSpecialCharacters.key()));
    }

    protected void validateIfPasswordContainsTheMinimumNumberOfUpperCaseLetters(int numberOfUppercaseLettersInPassword, String username, Long domainId) {
        Integer passwordPolicyMinimumUpperCaseLetters = getPasswordPolicyMinimumUpperCaseLetters(domainId);

        logger.trace(String.format("사용자 [%s]의 새 암호에 구성 [%s]에 정의된 최소 대문자 수 [%s]가 포함되어 있는지 확인하는 중입니다.",
                username, passwordPolicyMinimumUpperCaseLetters, PasswordPolicyMinimumUppercaseLetters.key()));

        if (passwordPolicyMinimumUpperCaseLetters == 0) {
            logger.trace(String.format("사용자 비밀번호의 최소 대문자 수는 0입니다. 따라서 사용자 [%s]의 새 암호에 대한 대문자 수를 확인하지 않습니다.", username));
            return;
        }

        if (numberOfUppercaseLettersInPassword < passwordPolicyMinimumUpperCaseLetters) {
            logger.error(String.format("사용자 [%s]이(가) [%d]개의 대문자로 새 비밀번호를 알렸습니다.; 단, 최소 대문자 수는 [%d]개입니다. "
                            + "사용자의 새 비밀번호를 거부합니다.", username, numberOfUppercaseLettersInPassword, passwordPolicyMinimumUpperCaseLetters));
            throw new InvalidParameterValueException(String.format("사용자 비밀번호에는 최소 [%d]개의 대문자가 포함되어야 합니다.", passwordPolicyMinimumUpperCaseLetters));
        }

        logger.trace(String.format("사용자 [%s]의 새 암호는 최소 대문자 [%s] 정책을 준수합니다.", username,
                PasswordPolicyMinimumUppercaseLetters.key()));
    }

    protected void validateIfPasswordContainsTheMinimumNumberOfLowerCaseLetters(int numberOfLowercaseLettersInPassword, String username, Long domainId) {
        Integer passwordPolicyMinimumLowerCaseLetters = getPasswordPolicyMinimumLowerCaseLetters(domainId);

        logger.trace(String.format("사용자 [%s]의 새 암호에 구성 [%s]에 정의된 최소 소문자 수 [%s]가 포함되어 있는지 확인하는 중입니다.",
                username, passwordPolicyMinimumLowerCaseLetters, PasswordPolicyMinimumLowercaseLetters.key()));

        if (passwordPolicyMinimumLowerCaseLetters == 0) {
            logger.trace(String.format("사용자 비밀번호의 최소 소문자 수는 0입니다. 따라서 사용자 [%s]의 새 암호에 대한 소문자 수를 확인하지 않습니다", username));
            return;
        }

        if (numberOfLowercaseLettersInPassword < passwordPolicyMinimumLowerCaseLetters) {
            logger.error(String.format("User [%s] informed [%d] lower case letters for their new password; however, the minimum number of lower case letters is [%d]. "
                            + "Refusing the user's new password.", username, numberOfLowercaseLettersInPassword, passwordPolicyMinimumLowerCaseLetters));
            throw new InvalidParameterValueException(String.format("사용자 비밀번호에는 최소 [%d]개의 소문자가 포함되어야 합니다", passwordPolicyMinimumLowerCaseLetters));
        }

        logger.trace(String.format("사용자 [%s]의 새 암호는 최소 소문자 [%s] 정책을 준수합니다.", username,
                PasswordPolicyMinimumLowercaseLetters.key()));
    }

    protected void validateIfPasswordContainsTheMinimumNumberOfDigits(int numberOfDigitsInPassword, String username, Long domainId) {
        Integer passwordPolicyMinimumDigits = getPasswordPolicyMinimumDigits(domainId);

        logger.trace(String.format("Validating if the new password for user [%s] contains the minimum number of digits [%s] defined in the configuration [%s].",
                username, passwordPolicyMinimumDigits, PasswordPolicyMinimumDigits.key()));

        if (passwordPolicyMinimumDigits == 0) {
            logger.trace(String.format("The minimum number of digits for a user's password is 0; therefore, we will not validate the number of digits for the new password of"
                    + " user [%s].", username));
            return;
        }

        if (numberOfDigitsInPassword < passwordPolicyMinimumDigits) {
            logger.error(String.format("User [%s] informed [%d] digits for their new password; however, the minimum number of digits is [%d]. "
                    + "Refusing the user's new password.", username, numberOfDigitsInPassword, passwordPolicyMinimumDigits));
            throw new InvalidParameterValueException(String.format("User password should contain at least [%d] digits.", passwordPolicyMinimumDigits));
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of minimum digits [%s].", username, PasswordPolicyMinimumDigits.key()));
    }

    protected void validateIfPasswordContainsTheMinimumLength(String password, String username, Long domainId) {
        Integer passwordPolicyMinimumLength = getPasswordPolicyMinimumLength(domainId);

        logger.trace(String.format("Validating if the new password for user [%s] contains the minimum length [%s] defined in the configuration [%s].", username,
                passwordPolicyMinimumLength, PasswordPolicyMinimumLength.key()));

        if (passwordPolicyMinimumLength == 0) {
            logger.trace(String.format("The minimum length of a user's password is 0; therefore, we will not validate the length of the new password of user [%s].", username));
            return;
        }

        Integer passwordLength = password.length();
        if (passwordLength < passwordPolicyMinimumLength) {
            logger.error(String.format("User [%s] informed [%d] characters for their new password; however, the minimum password length is [%d]. Refusing the user's new password.",
                    username, passwordLength, passwordPolicyMinimumLength));
                    throw new InvalidParameterValueException(String.format("사용자 비밀번호는 최소한 [%d]자를 포함해야 합니다.", passwordPolicyMinimumLength));
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of minimum length [%s].", username, PasswordPolicyMinimumLength.key()));
    }

    protected void validateIfPasswordContainsTheMaximumLength(String password, String username, Long domainId) {
        Integer passwordPolicyMaximumLength = getPasswordPolicyMaximumLength(domainId);

        logger.trace(String.format("Validating if the new password for user [%s] contains the maximum length [%s] defined in the configuration [%s].", username,
                passwordPolicyMaximumLength, PasswordPolicyMaximumLength.key()));

        Integer passwordLength = password.length();
        if (passwordLength > passwordPolicyMaximumLength) {
            logger.error(String.format("User [%s] informed [%d] characters for their new password; however, the maximum password length is [%d]. Refusing the user's new password.",
                    username, passwordLength, passwordPolicyMaximumLength));
                    throw new InvalidParameterValueException(String.format("사용자 암호는 [%d]자 미만이어야 합니다.", passwordPolicyMaximumLength));
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of maximum length [%s].", username, PasswordPolicyMaximumLength.key()));
    }

    protected void validateIfPasswordContainsTheUsername(String password, String username, Long domainId) {
        logger.trace(String.format("Validating if the new password for user [%s] contains their username.", username));

        if (getPasswordPolicyAllowPasswordToContainUsername(domainId)) {
            logger.trace(String.format("Allow password to contain username is true; therefore, we will not validate if the password contains the username of user [%s].", username));
            return;
        }

        if (StringUtils.containsIgnoreCase(password, username)) {
            logger.error(String.format("User [%s] informed a new password that contains their username; however, the this is not allowed as configured in [%s]. "
                    + "Refusing the user's new password.", username, PasswordPolicyAllowPasswordToContainUsername.key()));
            throw new InvalidParameterValueException("사용자 비밀번호에는 사용자 이름이 포함되어서는 안됩니다.");
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of allowing passwords to contain username [%s].", username,
                PasswordPolicyAllowPasswordToContainUsername.key()));
    }

    protected void validateIfPasswordMatchesRegex(String password, String username, Long domainId) {
        String passwordPolicyRegex = getPasswordPolicyRegex(domainId);

        logger.trace(String.format("Validating if the new password for user [%s] matches regex [%s] defined in the configuration [%s].",
                username, passwordPolicyRegex, PasswordPolicyRegex.key()));

        if (passwordPolicyRegex == null){
            logger.trace(String.format("Regex is null; therefore, we will not validate if the new password matches with regex for user [%s].", username));
            return;
        }

        if (!password.matches(passwordPolicyRegex)){
            logger.error(String.format("User [%s] informed a new password that does not match with regex [%s]. Refusing the user's new password.", username, passwordPolicyRegex));
            throw new InvalidParameterValueException("사용자 비밀번호가 비밀번호 정책 정규식과 일치하지 않습니다.");
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of matching regex [%s].", username,
                PasswordPolicyRegex.key()));
    }

    protected void validateIfPasswordContainsThePreviousPassword(String password, String username, Long domainId) {
        logger.trace(String.format("Validating if the new password for user [%s] is the last used password.", username));

        if (getPasswordPolicyAllowUseOfLastUsedPassword(domainId)) {
            logger.trace(String.format("Allow password to contain of last used password is true; therefore, we will not validate if the password contains of last used password of user [%s].", username));
            return;
        }

        User user = userDao.getUserByName(username, domainId);
        if (user != null)  {
            if (validateCurrentPassword(user, password)) {
            logger.error(String.format("User [%s] informed a new password that contains of the last used password; however, the this is not allowed as configured in [%s]. "
                    + "Refusing the user's new password.", username, PasswordPolicyAllowUseOfLastUsedPassword.key()));
            throw new InvalidParameterValueException("사용자 비밀번호에는 마지막으로 사용한 비밀번호가 포함되어서는 안 됩니다.");
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of allowing passwords to contain of last used password.", username,
                PasswordPolicyAllowUseOfLastUsedPassword.key()));
        }
    }

    protected void validateIfPasswordContainsConsecutiveRepetitionOfTheSameLetterAndNumber(String password, String username, Long domainId) {
        logger.trace(String.format("Validating if the new password for user [%s] is the contains more than 3 consecutive repetition of the same letter and number, special character.", username));

        if (getPasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers(domainId)) {
            logger.trace(String.format("Allow password to contain more than 3 consecutive repetition of the same letter and number is true; therefore, we will not validate if the password contains of consecutive repetition of the same letter and number, special character."));
            return;
        }

        if (samePassword(password)) {
            logger.error(String.format("User [%s] informed a new password that contains more than 3 consecutive repetition of the same letter and number, special character.; however, the this is not allowed as configured in [%s]. "
                    + "Refusing the user's new password.", username, PasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers.key()));
            throw new InvalidParameterValueException("사용자 비밀번호에는 동일한 문자, 숫자, 특수문자가 연속 3자리 이상 포함될 수 없습니다.");
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of allowing passwords to contain more than 3 consecutive repetition of the same letter and number, special character.", username,
                PasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers.key()));
    }

    protected void validateIfPasswordContainsContinuousLettersAndNumbersInputOnTheKeyboard(String password, String username, Long domainId) {
        logger.trace(String.format("Validating if the new password for user [%s] is the contain more than 4 consecutive keyboard letters and numbers.", username));

        if (getPasswordPolicyAllowContinuousStringInputOnKeyboard(domainId)) {
            logger.trace(String.format("Allow password to more than 4 consecutive keyboard letters and numbers is true; therefore, we will not validate if the password contains of continuous string of characters on the keyboard."));
            return;
        }

        if (continuousPassword(password)) {
            logger.error(String.format("User [%s] informed a new password that contains more than 4 consecutive keyboard letters and numbers.; however, the this is not allowed as configured in [%s]. "
                    + "Refusing the user's new password.", username, PasswordPolicyAllowContinuousLettersAndNumbersInputOnKeyboard.key()));
            throw new InvalidParameterValueException("사용자 비밀번호는 연속된 키보드 문자와 숫자를 4개 이상 포함할 수 없습니다.");
        }

        logger.trace(String.format("The new password for user [%s] complies with the policy of allowing passwords to contain more than 4 consecutive keyboard letters and numbers.", username,
                PasswordPolicyAllowContinuousLettersAndNumbersInputOnKeyboard.key()));
    }

    @Override
    public String getConfigComponentName() {
        return PasswordPolicyImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{PasswordPolicyMinimumLength, PasswordPolicyMaximumLength, PasswordPolicyMinimumSpecialCharacters, PasswordPolicyMinimumUppercaseLetters, PasswordPolicyMinimumLowercaseLetters,
                PasswordPolicyMinimumDigits, PasswordPolicyAllowPasswordToContainUsername, PasswordPolicyRegex, PasswordPolicyAllowUseOfLastUsedPassword, PasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers,
                PasswordPolicyAllowContinuousLettersAndNumbersInputOnKeyboard
        };
    }

    public Integer getPasswordPolicyMinimumLength(Long domainId) {
        return PasswordPolicyMinimumLength.valueIn(domainId);
    }

    public Integer getPasswordPolicyMaximumLength(Long domainId) {
        return PasswordPolicyMaximumLength.valueIn(domainId);
    }

    public Integer getPasswordPolicyMinimumSpecialCharacters(Long domainId) {
        return PasswordPolicyMinimumSpecialCharacters.valueIn(domainId);
    }

    public Integer getPasswordPolicyMinimumUpperCaseLetters(Long domainId) {
        return PasswordPolicyMinimumUppercaseLetters.valueIn(domainId);
    }

    public Integer getPasswordPolicyMinimumLowerCaseLetters(Long domainId) {
        return PasswordPolicyMinimumLowercaseLetters.valueIn(domainId);
    }

    public Integer getPasswordPolicyMinimumDigits(Long domainId) {
        return PasswordPolicyMinimumDigits.valueIn(domainId);
    }

    public Boolean getPasswordPolicyAllowPasswordToContainUsername(Long domainId) {
        return PasswordPolicyAllowPasswordToContainUsername.valueIn(domainId);
    }

    public String getPasswordPolicyRegex(Long domainId) {
        return PasswordPolicyRegex.valueIn(domainId);
    }

    public Boolean getPasswordPolicyAllowUseOfLastUsedPassword(Long domainId) {
        return PasswordPolicyAllowUseOfLastUsedPassword.valueIn(domainId);
    }

    public Boolean getPasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers(Long domainId) {
        return PasswordPolicyAllowConsecutiveRepetitionsOfSameLettersAndNumbers.valueIn(domainId);
    }

    public Boolean getPasswordPolicyAllowContinuousStringInputOnKeyboard(Long domainId) {
        return PasswordPolicyAllowContinuousLettersAndNumbersInputOnKeyboard.valueIn(domainId);
    }

    public Boolean samePassword(String password) {
        Pattern pattern = Pattern.compile("(\\w)\\1\\1");
        Matcher matcher = pattern.matcher(password);
        if(matcher.find()) {
            return true;
        }
        Pattern pattern_sign = Pattern.compile("[^a-zA-Z0-9\\s]{3,}");
        Matcher matcher_sign = pattern_sign.matcher(password);
        if(matcher_sign.find()) {
            return true;
        }
        return false;
    }

    public Boolean continuousPassword(String password) {
        ArrayList<String> keyboard = new ArrayList<>(Arrays.asList("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890", "poiuytrewq", "lkjhgfdsa", "mnbvcxz", "0987654321"));
        for (int i = 0; i < password.length()-3; i++) {
            String checkItem = password.charAt(i) + "" + password.charAt(i+1) + password.charAt(i+2) + password.charAt(i+3) + "";
            for (int j = 0; j < keyboard.size(); j++) {
                if (keyboard.get(j).indexOf(checkItem.toLowerCase()) != -1) {
                    return true;
                }
            }
        }
        return false;
    }

    protected Boolean validateCurrentPassword(User user, String password) {
        AccountVO userAccount = accountDao.findById(user.getAccountId());
        boolean currentPasswordMatchesDataBasePassword = false;
        for (UserAuthenticator userAuthenticator : _userPasswordEncoders) {
            Pair<Boolean, ActionOnFailedAuthentication> authenticationResult = userAuthenticator.authenticate(user.getUsername(), password, userAccount.getDomainId(), null);
            if (authenticationResult == null) {
                logger.trace(String.format("Authenticator [%s] is returning null for the authenticate mehtod.", userAuthenticator.getClass()));
                continue;
            }
            if (BooleanUtils.toBoolean(authenticationResult.first())) {
                logger.debug(String.format("User [id=%s] re-authenticated [authenticator=%s] during password update.", user.getUuid(), userAuthenticator.getName()));
                currentPasswordMatchesDataBasePassword = true;
                break;
            }
        }
        return currentPasswordMatchesDataBasePassword;
    }

}