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

package com.cloud.kubernetes.cluster;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com.cloud.network.Network;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.dao.ASNumberDao;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import org.apache.cloudstack.api.response.CniConfigurationPreviewResponse;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;

public class CniConfigurationRendererTest {
    private String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private String text(CniConfigurationPreviewResponse result) { return new String(Base64.getDecoder().decode(result.userdata), StandardCharsets.UTF_8); }
    @Test public void literalsAdjacentRepeatedRegexAndUnicodeAreNotReinterpreted() {
        String value = "$1\\path, eq=a\n한글 😀";
        CniConfigurationPreviewResponse result = CniConfigurationRenderer.requireValues(encoded("\ufeff#cloud-config\r\n{{ VAR }}{{VAR}}/{{ VAR }}\r\n"), "VAR", Map.of("VAR", value), null);
        assertEquals("\ufeff#cloud-config\r\n"+value+value+"/"+value+"\r\n", text(result));
        assertEquals(List.of("VAR"),result.candidates);assertTrue(result.complete);
    }
    @Test public void escapeAndUnknownTokensArePreservedWithoutRecursion() {
        CniConfigurationPreviewResponse result = CniConfigurationRenderer.preview(encoded("\\{{ VAR }}:{{ VAR }}:{{ UNKNOWN }}"),"VAR",Map.of("VAR","{{ UNKNOWN }}"),null);
        assertEquals("{{ VAR }}:{{ UNKNOWN }}:{{ UNKNOWN }}",text(result));assertEquals(List.of("UNKNOWN"),result.unresolved);assertFalse(result.complete);
    }
    @Test public void automaticValueAndEmptyCustomValue() {
        CniConfigurationPreviewResponse result = CniConfigurationRenderer.requireValues(encoded("{{AS_NUMBER}}{{ AS_NUMBER }}:{{EMPTY}}"),"AS_NUMBER,EMPTY",Map.of("EMPTY",""),64531L);
        assertEquals("6453164531:",text(result));assertTrue(result.complete);assertFalse(result.values.containsKey("AS_NUMBER"));
    }
    @Test public void automaticValueCanRemainUnallocatedAndIsReported() {
        CniConfigurationPreviewResponse result = CniConfigurationRenderer.preview(encoded("{{ AS_NUMBER }}"),"AS_NUMBER",Map.of(),null);
        assertEquals(List.of("AS_NUMBER"),result.unresolved);assertFalse(result.complete);assertTrue(result.missing.isEmpty());
    }
    @Test public void missingAndExtraNamesAreReportedAndRejectedForCreate() {
        CniConfigurationPreviewResponse result = CniConfigurationRenderer.preview(encoded("{{ VALUE }}"),"VALUE",Map.of("OTHER","x"),null);
        assertEquals(List.of("VALUE"),result.missing);assertEquals(List.of("OTHER"),result.extra);
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.requireValues(encoded("{{VALUE}}"),"VALUE",Map.of(),null));
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.requireValues(encoded("{{VALUE}}"),"VALUE",Map.of("VALUE","x","AS_NUMBER","2"),2L));
    }
    @Test public void jinjaUsesGuestMetadataRatherThanExecutingExpressions() {
        String source="## template: jinja\r\n#cloud-config\r\n# {{ ds.meta_data.VALUE }} {{ fn() }} {{ AS_NUMBER }}\r\n";
        CniConfigurationPreviewResponse result=CniConfigurationRenderer.preview(encoded(source),"VALUE",Map.of("VALUE","secret"),64531L);
        assertEquals(source.replace("{{ AS_NUMBER }}","64531"),text(result));assertEquals("jinja",result.format);assertEquals(List.of("VALUE","AS_NUMBER"),result.candidates);assertFalse(result.complete);
    }
    @Test public void candidateOrderInvalidReservedAndSimpleNames() {
        CniConfigurationPreviewResponse result=CniConfigurationRenderer.preview(encoded("{{A}} {{ A }} {{B-X}} {{v.dot}} {{bad key}} {{local-ipv4}}"),null,null,null);
        assertEquals(List.of("A","B-X","v.dot"),result.candidates);assertEquals(List.of("bad key","local-ipv4"),result.unsupported);
    }
    @Test public void variableNamesMatchRegistrationAndCannotOverrideMetadata() {
        for(String names:List.of("A,A","A,","local-ipv4","bad key","0key"," ")) {
            assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.parameters(names));
        }
        assertEquals(List.of("A","B-C","X.Y"),CniConfigurationRenderer.parameters(" A, B-C,X.Y "));
        assertEquals(List.of("X"),CniConfigurationRenderer.parameters("\u00a0\u0085\ufeffX\ufeff\u00a0"));
        assertEquals(List.of("X".repeat(512)),CniConfigurationRenderer.parameters("X".repeat(512)));
    }
    @Test public void jsonRoundTripPreservesDelimitersNewlinesAndUnicode() {
        Map<String,String> values=Map.of("VALUE","a,b=c\n한글\\$1", "EMPTY", "");
        assertEquals(values,CniConfigurationRenderer.deserialize(CniConfigurationRenderer.serialize(values)));
        assertEquals(Map.of("A","1","B","x=y"),CniConfigurationRenderer.deserialize("{A=1, B=x=y}"));
        assertTrue(CniConfigurationRenderer.deserialize("{}").isEmpty());
    }
    @Test public void assignedNetworkOrVpcAutomaticNumberWinsOverProposedValue() {
        Network network=mock(Network.class);ASNumberDao dao=mock(ASNumberDao.class);ASNumberVO assigned=mock(ASNumberVO.class);
        when(network.getVpcId()).thenReturn(null);when(network.getDataCenterId()).thenReturn(1L);when(network.getId()).thenReturn(2L);when(assigned.getAsNumber()).thenReturn(64531L);
        assertNull(CniConfigurationRenderer.resolveAsNumber(network,dao,null));
        assertEquals(Long.valueOf(64599),CniConfigurationRenderer.resolveAsNumber(network,dao,64599L));
        when(dao.findByZoneAndNetworkId(1L,2L)).thenReturn(assigned);assertEquals(Long.valueOf(64531),CniConfigurationRenderer.resolveAsNumber(network,dao,64599L));
        when(network.getVpcId()).thenReturn(3L);when(dao.findByZoneAndVpcId(1L,3L)).thenReturn(assigned);assertEquals(Long.valueOf(64531),CniConfigurationRenderer.resolveAsNumber(network,dao,null));
    }
    @Test public void renderedExpansionAndAutomaticNumberBoundsAreChecked() {
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.preview(encoded("{{X}}".repeat(500)),"X",Map.of("X","a".repeat(4000)),null));
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.preview(encoded("{{AS_NUMBER}}"),null,null,-1L));
        String json=CniConfigurationRenderer.serialize(Map.of("X","한글 😀"));assertTrue(json.chars().allMatch(c -> c < 128));
    }
    @Test public void invalidJsonValuesLimitsAndBinaryAreBounded() {
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.deserialize("{\"A\":1}"));
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.values(Map.of("A","x".repeat(4097))));
        assertThrows(InvalidParameterValueException.class,()->CniConfigurationRenderer.values(Map.of("A",1)));
        CniConfigurationPreviewResponse binary=CniConfigurationRenderer.preview("/wAB",null,null,null);
        assertEquals("binary",binary.format);assertEquals("/wAB",binary.userdata);assertFalse(binary.complete);
    }
}
