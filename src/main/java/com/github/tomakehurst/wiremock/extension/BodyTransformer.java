package com.github.tomakehurst.wiremock.extension;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.BinaryFile;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import net.minidev.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BodyTransformer extends ResponseDefinitionTransformer {

    private static final String TRANSFORMER_NAME = "body-transformer";
    private static final boolean APPLY_GLOBALLY = true;

    private static final Pattern interpolationPattern = Pattern.compile("\\$\\(.*?\\)");
    private static final Pattern randomIntegerPattern = Pattern.compile("!RandomInteger");

    private static ObjectMapper jsonMapper = initJsonMapper();
    private static ObjectMapper xmlMapper = initXmlMapper();

    private static ObjectMapper initJsonMapper() {
        return new ObjectMapper();
    }

    private static ObjectMapper initXmlMapper() {
        JacksonXmlModule configuration = new JacksonXmlModule();
        configuration.setXMLTextElementName("value");
        return new XmlMapper(configuration);
    }

    @Override
    public String getName() {
        return TRANSFORMER_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return APPLY_GLOBALLY;
    }

    @Override
    public ResponseDefinition transform(Request request, ResponseDefinition responseDefinition, FileSource fileSource, Parameters parameters) {
        if (hasEmptyResponseBody(responseDefinition)) {
            return responseDefinition;
        }

        Map object = null;
        String requestBody = null;

        if ("GET".equals(request.getMethod().toString())) {
            requestBody = getParamToJsonString(request.getUrl());
        } else if ("POST".equals(request.getMethod().toString())) {
            requestBody = request.getBodyAsString();
        }

        // Trying to create map of request body or query string parameters
        try {
            object = jsonMapper.readValue(requestBody, Map.class);
        } catch (IOException e) {
            try {
                object = xmlMapper.readValue(requestBody, Map.class);
            } catch (IOException ex) {
                // Validate is a body has the 'name=value' parameters
                if (StringUtils.isNotEmpty(requestBody) && (requestBody.contains("&") || requestBody.contains("="))) {
                    object = new HashMap();
                    String[] pairedValues = requestBody.split("&");
                    for (String pair : pairedValues) {
                        String[] values = pair.split("=");
                        object.put(values[0], values.length > 1 ? decodeUTF8Value(values[1]) : "");
                    }
                } else if (request.getAbsoluteUrl().split("\\?").length == 2) { // Validate query string parameters
                    object = new HashMap();
                    String absoluteUrl = request.getAbsoluteUrl();
                    String[] pairedValues = absoluteUrl.split("\\?")[1].split("&");
                    for (String pair : pairedValues) {
                        String[] values = pair.split("=");
                        object.put(values[0], values.length > 1 ? decodeUTF8Value(values[1]) : "");
                    }
                } else {
                    System.err.println("[Body parse error] The body doesn't match any of 3 possible formats (JSON, XML, key=value).");
                }
            }
        }

        // Update the map with query parameters if any (if same names - replace)
        if (parameters != null && parameters.size() != 0) {
            String urlRegex = parameters.getString("urlRegex");

            if (urlRegex != null) {
                Pattern p = Pattern.compile(urlRegex);
                Matcher m = p.matcher(request.getUrl());

                // There may be more groups in the regex than the number of named capturing groups
                List<String> groups = getNamedGroupCandidates(urlRegex);

                if (m.matches() &&
                        groups.size() > 0 &&
                        groups.size() <= m.groupCount()) {

                    for (int i = 0; i < groups.size(); i++) {

                        if (object == null) {
                            object = new HashMap();
                        }

                        object.put(groups.get(i), m.group(i + 1));
                    }
                }
            }
        }

        String responseBody = getResponseBody(responseDefinition, fileSource);

        // Create response by matching request map and response body parametrized values
        return ResponseDefinitionBuilder
                .like(responseDefinition).but()
                .withBodyFile(null)
                .withBody(transformResponse(object, responseBody))
                .withHeaders(transformHeadersResponse(object, responseDefinition.getHeaders()))
                .build();
    }

    /**
     * GET方法取参数
     * @param url
     * @return
     */
    private String getParamToJsonString(String url) {
        if (url.contains("?")) {
            String urlParam = url.substring(url.lastIndexOf("?") + 1);
            JSONObject jsonObject = new JSONObject();
            if (urlParam.contains("&")) {
                String[] urlParamArray = urlParam.split("&");
                for (String param: urlParamArray) {
                    jsonObject.put(param.split("=")[0], param.split("=")[1]);
                }
            } else {
                jsonObject.put(urlParam.split("=")[0], urlParam.split("=")[1]);
            }
            return jsonObject.toString();
        } else {
            return "";
        }
    }

    private String transformResponse(Map requestObject, String response) {
        String modifiedResponse = response;

        Matcher matcher = interpolationPattern.matcher(response);
        while (matcher.find()) {
            String group = matcher.group();
            modifiedResponse = modifiedResponse.replace(group, getValue(group, requestObject));

        }

        return modifiedResponse;
    }

    /**
     * 对返回头的值进行 跟参数匹配转化
     * @param requestObject 参数键值对
     * @param response      请求头类型
     * @return
     */
    private HttpHeaders transformHeadersResponse(Map requestObject, HttpHeaders response) {
        List<HttpHeader> httpHeaders = new ArrayList<>();
        for (HttpHeader httpHeader : response.all()) {
            List<String> headers = new ArrayList<>();
            for (String header : httpHeader.values()) {
                headers.add(transformResponse(requestObject, header));
            }
            httpHeaders.add(new HttpHeader(httpHeader.key(), headers.toArray(new String[headers.size()])));
        }
        return new HttpHeaders(httpHeaders.toArray(new HttpHeader[httpHeaders.size()]));
    }

    private CharSequence getValue(String group, Map requestObject) {
        if (randomIntegerPattern.matcher(group).find()) {
            return String.valueOf(new Random().nextInt(2147483647));
        }

        return getValueFromRequestObject(group, requestObject);
    }

    private CharSequence getValueFromRequestObject(String group, Map requestObject) {
        String fieldName = group.substring(2, group.length() - 1);
        String[] fieldNames = fieldName.split("\\.");
        Object tempObject = requestObject;
        for (String field : fieldNames) {
            if (tempObject instanceof Map) {
                tempObject = ((Map) tempObject).get(field);
            }
        }
        return String.valueOf(tempObject);
    }

    private boolean hasEmptyResponseBody(ResponseDefinition responseDefinition) {
        return responseDefinition.getBody() == null && responseDefinition.getBodyFileName() == null;
    }

    private String getResponseBody(ResponseDefinition responseDefinition, FileSource fileSource) {
        String body;
        if (responseDefinition.getBody() != null) {
            body = responseDefinition.getBody();
        } else {
            BinaryFile binaryFile = fileSource.getBinaryFileNamed(responseDefinition.getBodyFileName());
            body = new String(binaryFile.readContents(), StandardCharsets.UTF_8);
        }
        return body;
    }

    private static List<String> getNamedGroupCandidates(String regex) {
        List<String> namedGroups = new ArrayList<>();

        Matcher m = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*?)>").matcher(regex);

        while (m.find()) {
            namedGroups.add(m.group(1));
        }

        return namedGroups;
    }

    private String decodeUTF8Value(String value) {

        String decodedValue = "";
        try {
            decodedValue = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            System.err.println("[Body parse error] Can't decode one of the request parameter. It should be UTF-8 charset.");
        }

        return decodedValue;
    }

}
