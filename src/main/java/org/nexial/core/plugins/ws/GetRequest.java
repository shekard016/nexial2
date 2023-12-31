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

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.nexial.core.model.ExecutionContext;

public class GetRequest extends Request implements Serializable {
    private String queryString;
    private String payloadLocation;

    GetRequest(ExecutionContext context) {
        super(context);
        method = "GET";
    }

    GetRequest(ExecutionContext context, String url, String queryString) {
        super(context);
        setUrl(url);
        setQueryString(queryString);
        method = "GET";
    }

    public String getQueryString() { return queryString; }

    public void setQueryString(String queryString) { this.queryString = queryString; }

    public String getPayloadLocation() { return payloadLocation; }

    public void setPayloadLocation(String payloadLocation) { this.payloadLocation = payloadLocation; }

    @Override
    public String toString() {
        return super.toString() + "; GetRequest{queryString='" + queryString + "'}";
    }

    @Override
    protected HttpUriRequest prepRequest(RequestConfig requestConfig) {
        if (StringUtils.isNotBlank(getQueryString())) { url += "?" + getQueryString(); }

        HttpGet httpget = new HttpGet(url);
        httpget.setConfig(requestConfig);

        setRequestHeaders(httpget);

        return httpget;
    }
}
