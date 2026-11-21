/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloud.network.vpn;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.VpnUserVO;
import com.cloud.network.dao.VpnUserDao;
import com.cloud.network.dao.VpnUserDaoImpl;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import junit.framework.TestCase;
import org.apache.cloudstack.api.command.user.vpn.ListVpnUsersCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import javax.naming.ConfigurationException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;

@RunWith(MockitoJUnitRunner.class)
public class RemoteAccessVpnManagerImplTest extends TestCase {

    Class<InvalidParameterValueException> expectedException = InvalidParameterValueException.class;
    Class<CloudRuntimeException> cloudRuntimeException = CloudRuntimeException.class;

    @Test
    public void vpnUserKeywordMatchesStayWithinOwnerAndStateConditions() {
        RemoteAccessVpnManagerImpl manager = new RemoteAccessVpnManagerImpl();
        manager._accountMgr = Mockito.mock(AccountManager.class);
        manager._vpnUsersDao = Mockito.mock(VpnUserDao.class);
        ListVpnUsersCmd command = new ListVpnUsersCmd();
        ReflectionTestUtils.setField(command, "keyword", "vpn-client");
        ReflectionTestUtils.setField(command, "userName", "exact-user");
        SearchBuilder<VpnUserVO> builder = new VpnUserDaoImpl() { }.createSearchBuilder();
        Mockito.when(manager._vpnUsersDao.createSearchBuilder()).thenReturn(builder);
        Mockito.doAnswer(invocation -> {
            builder.and("allowedAccount", builder.entity().getAccountId(), SearchCriteria.Op.EQ);
            return null;
        }).when(manager._accountMgr).buildACLSearchBuilder(Mockito.eq(builder), Mockito.isNull(), Mockito.eq(false),
                Mockito.anyList(), Mockito.isNull());
        Mockito.doAnswer(invocation -> {
            SearchCriteria<VpnUserVO> criteria = invocation.getArgument(0);
            criteria.setParameters("allowedAccount", 9L);
            return null;
        }).when(manager._accountMgr).buildACLSearchCriteria(Mockito.any(), Mockito.isNull(), Mockito.eq(false),
                Mockito.anyList(), Mockito.isNull());
        Mockito.when(manager._vpnUsersDao.searchAndCount(Mockito.any(), Mockito.any())).thenReturn(new Pair<>(Collections.emptyList(), 0));
        assertEquals("vpn-client", command.getKeyword());
        try (MockedStatic<CallContext> contexts = Mockito.mockStatic(CallContext.class)) {
            contexts.when(CallContext::current).thenReturn(Mockito.mock(CallContext.class));
            manager.searchForVpnUsers(command);
        }
        ArgumentCaptor<SearchCriteria<VpnUserVO>> criteriaCaptor = ArgumentCaptor.forClass(SearchCriteria.class);
        Mockito.verify(manager._vpnUsersDao).searchAndCount(criteriaCaptor.capture(), Mockito.any());
        SearchCriteria<VpnUserVO> criteria = criteriaCaptor.getValue();
        String sql = criteria.getWhereClause().replace("`", "").replaceAll("[ ]+", " ");
        int keywordGroup = sql.indexOf(" AND (");
        assertTrue(sql, keywordGroup >= 0);
        String scopeConditions = sql.substring(0, keywordGroup);
        assertTrue(scopeConditions, scopeConditions.contains("owner_id") && scopeConditions.contains("username") && scopeConditions.contains("state"));
        String keywordConditions = sql.substring(keywordGroup);
        assertTrue(keywordConditions, keywordConditions.contains("username LIKE") && keywordConditions.contains("uuid LIKE"));
        assertTrue(keywordConditions, keywordConditions.indexOf(" OR ") > 0 && keywordConditions.indexOf(" OR ") < keywordConditions.lastIndexOf(')'));
        assertEquals(2, criteria.getValues().stream().filter(value -> "%vpn-client%".equals(value.second())).count());
    }

    @Test
    public void validateValidateIpRangeRangeLengthLessThan2MustThrowException(){
        String ipRange = "192.168.0.1";
        String expectedMessage = String.format("IP range [%s] is an invalid IP range.", ipRange);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, expectedException, () -> {
            new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
        });

        assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateValidateIpRangeRangeLengthHigherThan2MustThrowException(){
        String ipRange = "192.168.0.1-192.168.0.31-192.168.0.63";
        String expectedMessage = String.format("IP range [%s] is an invalid IP range.", ipRange);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, expectedException, () -> {
            new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
        });

        assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateValidateIpRangeFirstElementInvalidMustThrowException(){
        String ipRange = "192.168.0.400-192.168.0.255";
        String[] range = ipRange.split("-");
        String expectedMessage = String.format("One or both IPs sets in the range [%s] are invalid IPs.", ipRange);

        try (MockedStatic<NetUtils> ignored = Mockito.mockStatic(NetUtils.class)) {
            Mockito.when(NetUtils.isValidIp4(range[0])).thenReturn(Boolean.FALSE);
            Mockito.when(NetUtils.isValidIp4(range[1])).thenReturn(Boolean.TRUE);

            InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, expectedException, () -> {
                new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
            });

            assertEquals(expectedMessage, assertThrows.getMessage());
        }
    }

    @Test
    public void validateValidateIpRangeSecondElementInvalidMustThrowException(){
        String ipRange = "192.168.0.1-192.168.0.400";
        String[] range = ipRange.split("-");
        String expectedMessage = String.format("One or both IPs sets in the range [%s] are invalid IPs.", ipRange);

        try (MockedStatic<NetUtils> ignored = Mockito.mockStatic(NetUtils.class)) {

            Mockito.when(NetUtils.isValidIp4(range[0])).thenReturn(Boolean.TRUE);
            Mockito.when(NetUtils.isValidIp4(range[1])).thenReturn(Boolean.FALSE);

            InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, expectedException, () -> {
                new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
            });

            assertEquals(expectedMessage, assertThrows.getMessage());
        }
    }

    @Test
    public void validateValidateIpRangeBothElementsInvalidMustThrowException(){
        String ipRange = "192.168.0.256-192.168.0.300";
        String[] range = ipRange.split("-");
        String expectedMessage = String.format("One or both IPs sets in the range [%s] are invalid IPs.", ipRange);

        try (MockedStatic<NetUtils> ignored = Mockito.mockStatic(NetUtils.class)) {

            Mockito.when(NetUtils.isValidIp4(range[0])).thenReturn(Boolean.FALSE);
            Mockito.when(NetUtils.isValidIp4(range[1])).thenReturn(Boolean.FALSE);

            InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, expectedException, () -> {
                new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
            });

            assertEquals(expectedMessage, assertThrows.getMessage());
        }
    }

    @Test
    public void validateValidateIpRangeInvalidIpRangeMustThrowException(){
        String ipRange = "192.168.0.255-192.168.0.1";
        String[] range = ipRange.split("-");
        String expectedMessage = String.format("Range of IPs [%s] is invalid.", ipRange);

        try (MockedStatic<NetUtils> ignored = Mockito.mockStatic(NetUtils.class)) {

            Mockito.when(NetUtils.isValidIp4(range[0])).thenReturn(Boolean.TRUE);
            Mockito.when(NetUtils.isValidIp4(range[1])).thenReturn(Boolean.TRUE);
            Mockito.when(NetUtils.validIpRange(range[0], range[1])).thenReturn(Boolean.FALSE);

            InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, expectedException, () -> {
                new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
            });

            assertEquals(expectedMessage, assertThrows.getMessage());
        }
    }

    @Test
    public void validateValidateIpRangeValidIpRangeMustValidate(){
        String ipRange = "192.168.0.1-192.168.0.255";
        String[] range = ipRange.split("-");

        try (MockedStatic<NetUtils> ignored = Mockito.mockStatic(NetUtils.class)) {
            Mockito.when(NetUtils.isValidIp4(range[0])).thenReturn(Boolean.TRUE);
            Mockito.when(NetUtils.isValidIp4(range[1])).thenReturn(Boolean.TRUE);
            Mockito.when(NetUtils.validIpRange(range[0], range[1])).thenReturn(Boolean.TRUE);

            new RemoteAccessVpnManagerImpl().validateIpRange(ipRange, expectedException);
        }
    }

    private <T extends Throwable> void handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(Class<T> exceptionToCatch){
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exceptionToCatch, "Test");
    }

    private <T extends Throwable> void handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(Class<T> exceptionToCatch, String exceptionMessage){
        String errorMessage = "Test";
        String expectedMessage = String.format("Unexpected exception [%s] while throwing error [%s] on validateIpRange.", exceptionMessage, errorMessage);

        CloudRuntimeException assertThrows = Assert.assertThrows(expectedMessage, cloudRuntimeException, () -> {
            new RemoteAccessVpnManagerImpl().handleExceptionOnValidateIpRangeError(exceptionToCatch, errorMessage);
        });

        assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenNoSuchMethodExceptionThrowCloudRuntimeException(){
        Class<NoSuchMethodException> exception = NoSuchMethodException.class;
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exception);
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenSecurityExceptionThrowCloudRuntimeException(){
        Class<SecurityException> exception = SecurityException.class;
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exception);
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenInstantiationExceptionThrowCloudRuntimeException(){
        Class<InstantiationException> exception = InstantiationException.class;
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exception);
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenIllegalAccessExceptionThrowCloudRuntimeException(){
        Class<IllegalAccessException> exception = IllegalAccessException.class;
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exception);
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenIllegalArgumentExceptionThrowCloudRuntimeException(){
        Class<IllegalArgumentException> exception = IllegalArgumentException.class;
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exception);
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenInvocationTargetExceptionThrowCloudRuntimeException(){
        Class<InvocationTargetException> exception = InvocationTargetException.class;
        handleExceptionOnValidateIpRangeErrorMustThrowCloudRuntimeException(exception, "java.lang.reflect.InvocationTargetException.<init>(java.lang.String)");
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenConfigurationExceptionThrowConfigurationException(){
        Class<ConfigurationException> exception = ConfigurationException.class;
        String expectedMessage = "Test";

        ConfigurationException assertThrows = Assert.assertThrows(expectedMessage, exception, () -> {
            new RemoteAccessVpnManagerImpl().handleExceptionOnValidateIpRangeError(exception, expectedMessage);
        });

        assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateHandleExceptionOnValidateIpRangeErrorWhenInvalidParameterValueExceptionThrowInvalidParameterValueException(){
        Class<InvalidParameterValueException> exception = InvalidParameterValueException.class;
        String expectedMessage = "Test";

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedMessage, exception, () -> {
            new RemoteAccessVpnManagerImpl().handleExceptionOnValidateIpRangeError(exception, expectedMessage);
        });

        assertEquals(expectedMessage, assertThrows.getMessage());
    }
}
