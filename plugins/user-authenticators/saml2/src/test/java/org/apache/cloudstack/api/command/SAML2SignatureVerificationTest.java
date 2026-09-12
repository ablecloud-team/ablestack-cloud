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

package org.apache.cloudstack.api.command;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.HashMap;

import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.saml.SAMLProviderMetadata;
import org.apache.cloudstack.utils.security.CertUtils;
import org.junit.Before;
import org.junit.Test;
import org.opensaml.Configuration;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.SignableSAMLObject;
import org.opensaml.saml2.core.impl.AssertionBuilder;
import org.opensaml.saml2.core.impl.ResponseBuilder;
import org.opensaml.xml.security.x509.BasicX509Credential;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.SignatureConstants;
import org.opensaml.xml.signature.Signer;
import org.opensaml.xml.signature.impl.SignatureBuilder;

import org.apache.cloudstack.api.ApiServerService;

public class SAML2SignatureVerificationTest {
    private SAML2LoginAPIAuthenticatorCmd command;
    private SAMLProviderMetadata metadata;
    private KeyPair pair;
    private X509Certificate certificate;

    @Before
    public void setUp() throws Exception {
        DefaultBootstrap.bootstrap();
        pair = CertUtils.generateRandomKeyPair(2048);
        certificate = CertUtils.generateV3Certificate(null, pair, pair.getPublic(), "CN=Europa fixture IdP", "SHA256withRSA", 365, null, null);
        metadata = new SAMLProviderMetadata();
        metadata.setEntityId("https://idp.example/europa");
        metadata.setSigningCertificate(certificate);
        command = new SAML2LoginAPIAuthenticatorCmd();
        java.lang.reflect.Field field = SAML2LoginAPIAuthenticatorCmd.class.getDeclaredField("apiServer");
        field.setAccessible(true);
        field.set(command, mock(ApiServerService.class));
    }

    private Signature sign(SignableSAMLObject object) throws Exception {
        BasicX509Credential credential = new BasicX509Credential();
        credential.setEntityCertificate(certificate);
        credential.setPrivateKey(pair.getPrivate());
        Signature signature = new SignatureBuilder().buildObject();
        signature.setSigningCredential(credential);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        object.setSignature(signature);
        Configuration.getMarshallerFactory().getMarshaller(object).marshall(object);
        Signer.signObject(signature);
        return signature;
    }

    private void validate(Signature signature) {
        command.validateSAMLSignature(signature, metadata, new HashMap<>(), "json");
    }

    @Test
    public void configuredIdpVerifiesSignedResponseAndAssertion() throws Exception {
        org.opensaml.saml2.core.Response response = new ResponseBuilder().buildObject();
        response.setID("_response");
        validate(sign(response));
        org.opensaml.saml2.core.Assertion assertion = new AssertionBuilder().buildObject();
        assertion.setID("_assertion");
        validate(sign(assertion));
    }

    @Test
    public void signatureWithoutRegisteredCertificateIsRejected() throws Exception {
        org.opensaml.saml2.core.Response response = new ResponseBuilder().buildObject();
        response.setID("_response");
        Signature signature = sign(response);
        metadata.setSigningCertificate(null);
        assertThrows(ServerApiException.class, () -> validate(signature));
    }

    @Test
    public void wrongRegisteredCertificateIsRejected() throws Exception {
        org.opensaml.saml2.core.Response response = new ResponseBuilder().buildObject();
        response.setID("_response");
        Signature signature = sign(response);
        KeyPair other = CertUtils.generateRandomKeyPair(2048);
        metadata.setSigningCertificate(CertUtils.generateV3Certificate(null, other, other.getPublic(), "CN=Other IdP", "SHA256withRSA", 365, null, null));
        assertThrows(ServerApiException.class, () -> validate(signature));
    }

    @Test
    public void modifiedSignedResponseIsRejected() throws Exception {
        org.opensaml.saml2.core.Response response = new ResponseBuilder().buildObject();
        response.setID("_response");
        Signature signature = sign(response);
        response.getDOM().setAttribute("Destination", "https://other.example/changed");
        assertThrows(ServerApiException.class, () -> validate(signature));
    }
}
