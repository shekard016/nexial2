/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.ws;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.nexial.core.model.MockExecutionContext;
import org.nexial.core.model.StepResult;
import org.nexial.core.utils.JsonUtils;

import java.util.List;
import java.util.Map;

import static org.apache.http.HttpHeaders.AUTHORIZATION;
import static org.apache.http.HttpHeaders.CONTENT_TYPE;
import static org.junit.Assert.*;

public class WsCommandTest {
    private MockExecutionContext context;
    private String secret = "1234567890abcdefghijk";

    @Before
    public void init() {
        context = new MockExecutionContext();
    }

    @After
    public void tearDown() {
        if (context != null) { context.cleanProject(); }
    }

    @Test
    public void testApi_with_special_characters_queryString() {
        String url = "https://samples.openweathermap.org/data/2.5/forecast/hourly";
        String queryString = "q=Zuerich (Kreis 3) / Sihlfeld&APPID=50b270e6e8327679f7f34bf2c709e938";

        // context.setData(WS_PROXY_REQUIRED, "true");
        WsCommand subject = new WsCommand();
        subject.init(context);
        StepResult result = subject.get(url, queryString, "response");

        assertTrue(result.isSuccess());

        Response response = subject.resolveResponseObject("response");
        assertEquals(200, response.returnCode);
        assertTrue(response.getContentLength() > 1);

        String jsonContent = new String(response.getRawBody());
        JSONObject json = new JSONObject(jsonContent);
        assertNotNull(json);
        System.out.println("json = " + json);
    }

    @Test
    public void testApi_with_special_characters_url() {
        String url = "https://samples.openweathermap.org/data/2.5/forecast/hourly?" +
                     "q=Zuerich (Kreis 3) / Sihlfeld&APPID=50b270e6e8327679f7f34bf2c709e938";
        String queryString = "";

        // context.setData(WS_PROXY_REQUIRED, "true");
        WsCommand subject = new WsCommand();
        subject.init(context);
        // subject.header("Content-Type", "application/json; charset=utf-8");
        StepResult result = subject.get(url, queryString, "response");

        assertTrue(result.isSuccess());

        Response response = subject.resolveResponseObject("response");
        assertEquals(200, response.returnCode);
        assertTrue(response.getContentLength() > 1);

        String jsonContent = new String(response.getRawBody());
        JSONObject json = new JSONObject(jsonContent);
        assertNotNull(json);
        System.out.println("json = " + json);
    }

    @Test
    public void jwtSignHS256() {
        WsCommand subject = new WsCommand();
        subject.init(context);

        StepResult result = subject.jwtSignHS256(
            "fixture",
            "{ \"name\":\"John Doe\", \"citizenship\":\"Unknown\", \"Jimmy crack corn\":\"and I don't care!\" }",
            secret);
        assertNotNull(result);
        assertTrue(result.isSuccess());

        String token = context.getStringData("fixture");
        System.out.println("token = " + token);
        assertNotNull(token);
        assertTrue(StringUtils.isNotBlank(token));
    }

    @Test
    public void jwtParseHS256() {
        WsCommand subject = new WsCommand();
        subject.init(context);

        StepResult result = subject.jwtParse(
            "fixture",
            "eyJhbGciOiJIUzI1NiJ9.eyAibmFtZSI6IkpvaG4gRG9lIiwgImNpdGl6ZW5zaGlwIjoiVW5rbm93biIsICJKaW1teSBjcmFjayBjb3JuIjoiYW5kIEkgZG9uJ3QgY2FyZSEiIH0.45CGtWSayaPh3B-Ie49kpzMHcYswSBY3mA9lXTfVq7g",
            secret);
        // StepResult result = subject.jwtParse("fixture", "eyJhbGciOiJIUzI1NiJ9.eyAibmFtZSI6IkpvaG4gRG9lIiwgImNpdGl6ZW5zaGlwIjoiVW5rbm93biIsICJKaW1teSBjcmFjayBjb3JuIjoiYW5kIEkgZG9uJ3QgY2FyZSEiIH0.45CGtWSayaPh3B-Ie49kpzMHcYswSBY3mA9lXTfVq7g", secret);
        assertNotNull(result);
        assertTrue(result.isSuccess());

        String payload = context.getStringData("fixture");
        System.out.println("payload = " + payload);
        assertNotNull(payload);
        assertTrue(StringUtils.isNotBlank(payload));

        JSONObject json = JsonUtils.toJSONObject(payload);
        System.out.println("json = " + json);
        assertNotNull(json);
        assertEquals("John Doe", json.get("name"));
        assertEquals("Unknown", json.get("citizenship"));
    }

    @Test
    public void jwtParseHS256_badkey() {
        WsCommand subject = new WsCommand();
        subject.init(context);

        StepResult result = subject.jwtParse(
            "fixture",
            "eyJhbGciOiJIUzI1NiJ9.eyAibmFtZSI6IkpvaG4gRG9lIiwgImNpdGl6ZW5zaGlwIjoiVW5rbm93biIsICJKaW1teSBjcmFjayBjb3JuIjoiYW5kIEkgZG9uJ3QgY2FyZSEiIH0.45CGtWSayaPh3B-Ie49kpzMHcYswSBY3mA9lXTfVq7g",
            null);
        assertNotNull(result);
        System.out.println("result = " + result);
        assertTrue(result.isSuccess());

        String payload = context.getStringData("fixture");
        System.out.println("payload = " + payload);
        assertNotNull(payload);
        assertTrue(StringUtils.isNotBlank(payload));

        JSONObject json = JsonUtils.toJSONObject(payload);
        System.out.println("json = " + json);
        assertNotNull(json);
        assertEquals("John Doe", json.get("name"));
        assertEquals("Unknown", json.get("citizenship"));
    }

    @Test
    public void jwtParseRS256_badkey() {
        WsCommand subject = new WsCommand();
        subject.init(context);

        StepResult result = subject.jwtParse(
            "fixture",
            "eyJhbGciOiJSUzI1NiIsImtpZCI6ImJwODExIn0.eyJzdWIiOiJic2FuZGVyc0BlcC5jb20iLCJyb2xlIjoiQ2VudHJhbENhc3RpbmdfQW55X1N5c3RlbUFkbWluIiwic2NvcGVzIjoiQ2FzdGluZ0FQSUluZGV4ZXJTY29wZSBDYXN0aW5nQVBJT2NjdXJyZW5jZVN1c3BlbnNpb25zU2NvcGUgQ2FzdGluZ0FQSVNlYXJjaFNjb3BlIENhc3RpbmdBUElMb29rdXBTY29wZSBDYXN0aW5nQVBJVGFsZW50U2NvcGUgQ2FzdGluZ0FQSVRhbGVudE5pY2tTdGF0dXNTY29wZSBDYXN0aW5nQVBJVGFsZW50Q2FzdGluZ1Njb3BlIENhc3RpbmdBUElUYWdTY29wZSBDYXN0aW5nQVBJVGFsZW50RGVsZXRlUmVxdWVzdFNjb3BlIENhc3RpbmdBUElQU0dTY29wZSBDYXN0aW5nQVBJUmVhZFdyaXRlVGFnU2NvcGUgQ2FzdGluZ0FQSUFkbWluU2NvcGUiLCJlbWFpbCI6ImJzYW5kZXJzQGVwLmNvbSIsImF1ZCI6IkNlbnRyYWxDYXN0aW5nIiwianRpIjoidTNXYmlwdEN5YllZNXdQR2lZSU0zViIsImlzcyI6Imh0dHBzOlwvXC9pZC1kZXYuZXAuY29tIiwiaWF0IjoxNDc5MjIzMDU3LCJleHAiOjE0NzkyMjMzNTcsInBpLnNyaSI6Ikh5cWlJbTNZRDg5cTF0aVdpb0E5ZUtOZWlFZyIsImF1dGhfdGltZSI6MTQ3OTIyMzA1N30.lYTkVVd1ub1Tcn2HUrt9RGJE2XlDtF7EvuMUB452JNQ3GsuKxjTjFyxnPk5w-Bt-gCvaTLv6cCH-WUgyFcY-jJzyRmWBdVdjiqQ_-RpvNibyfyq2-ME5yvuullHKiBxXbyXZsq4pxjRHb5OxgkN-LaUsy-wB8uvjN-vC4XpxaiXWnWrgy1T_ZuuiTY_J2UTFUDz_gsaELVVQiHT7E2ISRoP0jXWFtk_mzUxKxBseuZrLFvgoo2epnnvlbBMcM5_IPMNj4D-OzPCSs6VYP63ePzkSJlCbzyRco4-WNdZ-aHnc9SaAvtqbx_YksJtP4YM9FwA8ULggjLcsDhte-Db8aQ",
            null);
        assertNotNull(result);
        System.out.println("result = " + result);
        assertTrue(result.isSuccess());

        String payload = context.getStringData("fixture");
        System.out.println("payload = " + payload);
        assertNotNull(payload);
        assertTrue(StringUtils.isNotBlank(payload));

        JSONObject json = JsonUtils.toJSONObject(payload);
        System.out.println("json = " + json);
        assertNotNull(json);
        assertEquals("bsanders@ep.com", json.get("sub"));
        assertEquals("CentralCasting", json.get("aud"));
        assertEquals("CastingAPIIndexerScope " +
                     "CastingAPIOccurrenceSuspensionsScope " +
                     "CastingAPISearchScope " +
                     "CastingAPILookupScope " +
                     "CastingAPITalentScope " +
                     "CastingAPITalentNickStatusScope " +
                     "CastingAPITalentCastingScope " +
                     "CastingAPITagScope " +
                     "CastingAPITalentDeleteRequestScope " +
                     "CastingAPIPSGScope " +
                     "CastingAPIReadWriteTagScope " +
                     "CastingAPIAdminScope", json.get("scopes"));
    }

    // @Test
    // can't run this now... blocked by ingress network rule
    public void oauth() {
        WsCommand subject = new WsCommand();
        subject.init(context);

        StepResult result = subject.oauth("dummy", "https://oidc-dev.api.ep.com/oauth/token",
                                          "client_id=5MrGaUmuplzAL08ZN87kMm89CdAlM3dz\n" +
                                          "client_secret=SOmlVfTpvfaWbZLa\n" +
                                          "scope=NotificationAPINotificationScope\n" +
                                          "grant_type=client_credentials");
        System.out.println("result = " + result);
        assertNotNull(result);
        assertTrue(result.isSuccess());

        Map oauthVar = context.getMapData("dummy");
        System.out.println("context.getObjectData(dummy) = \n" + oauthVar);
        // System.out.println(context.replaceTokens("${dummy}.[access_token]"));
        // System.out.println(context.replaceTokens("${dummy}.access_token"));
        assertEquals(oauthVar.get("access_token"), context.replaceTokens("${dummy}.[access_token]"));
        assertEquals(oauthVar.get("organization_name"), context.replaceTokens("${dummy}.organization_name"));
    }

    public void _old_oauth() {
        String tokenLocation = "https://oidc-dev.api.ep.com/oauth/token";
        String clientId = "5MrGaUmuplzAL08ZN87kMm89CdAlM3dz";
        String clientSecret = "SOmlVfTpvfaWbZLa";
        String scope = "NotificationAPINotificationScope";
        String grantType = "client_credentials";
        String basicHeader = "Basic " + new String(Base64.encodeBase64((clientId + ":" + clientSecret).getBytes()));

        WsCommand subject = new WsCommand();
        subject.init(context);

        subject.header(AUTHORIZATION, basicHeader);
        subject.header(CONTENT_TYPE, "application/x-www-form-urlencoded");
        subject.post(tokenLocation, "grant_type=" + grantType + "&" + "scope=" + scope, "dummy");
        Response response = (Response) context.getObjectData("dummy");
        System.out.println("response = " + response);
        System.out.println("response body = " + response.getBody());

		/*
		{
		  "refresh_token_expires_in" : "0",
		  "api_product_list" : "[CentralCastingConnectProduct, NotificationProduct]",
		  "organization_name" : "ep",
		  "developer.email" : "jhart@ep.com",
		  "token_type" : "BearerToken",
		  "issued_at" : "1490326890373",
		  "client_id" : "5MrGaUmuplzAL08ZN87kMm89CdAlM3dz",
		  "access_token" : "KZd2gkOLqJw8RfFcDA6Re2TcvzoY",
		  "application_name" : "3e6a09ef-34bd-4caa-9c78-6e67869ecd53",
		  "scope" : "NotificationAPINotificationScope",
		  "expires_in" : "1799",
		  "refresh_count" : "0",
		  "status" : "approved"
		}
		*/
        Gson GSON = new GsonBuilder().setPrettyPrinting()
                                     .disableHtmlEscaping()
                                     .disableInnerClassSerialization()
                                     .setLenient()
                                     .create();
        JsonObject json = GSON.fromJson(response.getBody(), JsonObject.class);
        System.out.println(StringUtils.rightPad("API Products", 20) + json.get("api_product_list").getAsString());
        System.out.println(StringUtils.rightPad("organization", 20) + json.get("organization_name").getAsString());
        System.out.println(StringUtils.rightPad("Developer Email", 20) + json.get("developer.email").getAsString());
        System.out.println(StringUtils.rightPad("Issued", 20) + new java.util.Date(json.get("issued_at").getAsLong()));
        System.out.println(StringUtils.rightPad("Client ID", 20) + json.get("client_id").getAsString());
        System.out.println(StringUtils.rightPad("Access Token", 20) + json.get("access_token").getAsString());
        System.out.println(StringUtils.rightPad("Application", 20) + json.get("application_name").getAsString());
        System.out.println(StringUtils.rightPad("Scope", 20) + json.get("scope").getAsString());
        System.out.println(StringUtils.rightPad("Expires In", 20) + json.get("expires_in").getAsLong());
        System.out.println(StringUtils.rightPad("Refresh Count", 20) + json.get("refresh_count").getAsString());
        System.out.println(StringUtils.rightPad("Status", 20) + json.get("status").getAsString());
    }

    @Test
    public void test_expandReturnCodes_simple() throws Exception {
        WsCommand subject = new WsCommand();
        subject.init(context);

        List<Integer> returnCodes;

        returnCodes = subject.expandReturnCodes("");
        assertTrue(CollectionUtils.isEmpty(returnCodes));

        returnCodes = subject.expandReturnCodes("200");
        assertNotNull(returnCodes);
        assertEquals(1, returnCodes.size());
        assertTrue(returnCodes.contains(200));

        returnCodes = subject.expandReturnCodes("200,201");
        assertNotNull(returnCodes);
        assertEquals(2, returnCodes.size());
        assertTrue(returnCodes.contains(200));
        assertTrue(returnCodes.contains(201));

        returnCodes = subject.expandReturnCodes("200,204,201,200");
        assertNotNull(returnCodes);
        assertEquals(4, returnCodes.size());
        assertTrue(returnCodes.contains(200));
        assertTrue(returnCodes.contains(204));
        assertTrue(returnCodes.contains(201));
    }

    @Test
    public void test_expandReturnCodes_range() throws Exception {
        WsCommand subject = new WsCommand();
        subject.init(context);

        List<Integer> returnCodes;

        returnCodes = subject.expandReturnCodes("200-204");
        assertTrue(CollectionUtils.isNotEmpty(returnCodes));
        assertEquals(5, returnCodes.size());
        assertTrue(returnCodes.contains(200));
        assertTrue(returnCodes.contains(201));
        assertTrue(returnCodes.contains(202));
        assertTrue(returnCodes.contains(203));
        assertTrue(returnCodes.contains(204));

        returnCodes = subject.expandReturnCodes("200 - 204, 301, \t \n 304");
        assertTrue(CollectionUtils.isNotEmpty(returnCodes));
        assertEquals(7, returnCodes.size());
        assertTrue(returnCodes.contains(200));
        assertTrue(returnCodes.contains(201));
        assertTrue(returnCodes.contains(202));
        assertTrue(returnCodes.contains(203));
        assertTrue(returnCodes.contains(204));
        assertTrue(returnCodes.contains(301));
        assertTrue(returnCodes.contains(304));
    }

    @Test
    public void test_expandReturnCodes_errors() throws Exception {
        WsCommand subject = new WsCommand();
        subject.init(context);

        List<Integer> returnCodes;

        returnCodes = subject.expandReturnCodes("45-96");
        assertTrue(CollectionUtils.isEmpty(returnCodes));

        returnCodes = subject.expandReturnCodes("-6-200");
        assertTrue(CollectionUtils.isEmpty(returnCodes));

        returnCodes = subject.expandReturnCodes("200-198");
        assertTrue(CollectionUtils.isEmpty(returnCodes));

        returnCodes = subject.expandReturnCodes("three hundred,205a");
        assertTrue(CollectionUtils.isEmpty(returnCodes));
    }

}