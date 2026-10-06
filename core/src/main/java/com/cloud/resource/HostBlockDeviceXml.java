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
package com.cloud.resource;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.function.BiPredicate;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Resolve a detach request to the exact current disk, never allocate a new target on detach. */
public final class HostBlockDeviceXml {
    private HostBlockDeviceXml() { }

    private static Document parse(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
    }

    public static String findLun(String domainXml, String requestedXml, BiPredicate<String, String> sameSource) throws Exception {
        Element request = parse(requestedXml).getDocumentElement();
        if (!"disk".equals(request.getTagName()) || !"block".equals(request.getAttribute("type"))) {
            throw new IllegalArgumentException("Invalid LUN detach request");
        }
        Element source = (Element) request.getElementsByTagName("source").item(0);
        if (source == null || source.getAttribute("dev").isEmpty()) { throw new IllegalArgumentException("Missing LUN source"); }
        String requested = source.getAttribute("dev");
        NodeList disks = parse(domainXml).getElementsByTagName("disk");
        Element match = null;
        for (int i = 0; i < disks.getLength(); i++) {
            Element disk = (Element) disks.item(i);
            Element candidate = (Element) disk.getElementsByTagName("source").item(0);
            if ("block".equals(disk.getAttribute("type")) && "lun".equals(disk.getAttribute("device"))
                    && candidate != null && sameSource.test(requested, candidate.getAttribute("dev"))) {
                if (match != null) { throw new IllegalArgumentException("Multiple matching LUNs; cannot verify exact detach target"); }
                match = disk;
            }
        }
        if (match == null) { return null; }
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        StringWriter output = new StringWriter();
        factory.newTransformer().transform(new DOMSource(match), new StreamResult(output));
        return output.toString();
    }
}
