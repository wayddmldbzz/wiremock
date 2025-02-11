package com.github.tomakehurst.wiremock.extension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.velocity.Template;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.RuntimeSingleton;
import org.apache.velocity.runtime.parser.ParseException;
import org.apache.velocity.runtime.parser.node.SimpleNode;
import org.apache.velocity.tools.ToolManager;
import org.apache.velocity.tools.config.ConfigurationUtils;
import org.jooq.lambda.Unchecked;
import org.jooq.lambda.tuple.Tuple;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Class is used in conjunction with wiremock either standalone or
 * library. Used for transforming the response body of a parameterized
 * velocity template.
 *
 * @author yorka012
 */
public class VelocityResponseTransformer extends ResponseDefinitionTransformer {

    private static final String NAME = "velocity-transformer";
    private static final boolean APPLY_GLOBALLY = true;

    /**
     * The Velocity context that will hold our request header
     * data.
     */
    private Context context;

    /**
     * The template
     */
    private FileSource fileSource;

    @Override
    public ResponseDefinition transform(final Request request,
                                        final ResponseDefinition responseDefinition,
                                        final FileSource files,
                                        final Parameters parameters) {
        return Optional.of(templateDeclaredAndSpecifiesBodyFile(responseDefinition))
                // 文件存在或者body不为空
                .filter(bool -> bool || responseDefinition.getBody() != null)
                .map(bool -> {
                    this.fileSource = files;
                    final VelocityEngine velocityEngine = new VelocityEngine();
                    velocityEngine.init();
                    final ToolManager toolManager = new ToolManager();
                    toolManager.configure(ConfigurationUtils.GENERIC_DEFAULTS_PATH);
                    toolManager.setVelocityEngine(velocityEngine);
                    context = toolManager.createContext();
                    final Context contextWithBody = addBodyToContext(request.getBodyAsString(), context);
                    final Context contextWithBodyAndHeaders = addHeadersToContext(request.getHeaders(), contextWithBody);
                    final Context contextWithHeadersBodyAndParams = addParametersVariablesToContext(request.getUrl(),
                            contextWithBodyAndHeaders);
                    contextWithHeadersBodyAndParams.put("requestAbsoluteUrl", request.getAbsoluteUrl());
                    contextWithHeadersBodyAndParams.put("requestUrl", request.getUrl());
                    contextWithHeadersBodyAndParams.put("requestMethod", request.getMethod());
                    // 文件存在优先使用文件，文件不存在直接找body
                    final String body = bool ? getRenderedFile(responseDefinition, contextWithHeadersBodyAndParams)
                                             : getRenderedBody(responseDefinition, contextWithHeadersBodyAndParams);
                    return ResponseDefinitionBuilder.like(responseDefinition).but()
                            .withBody(body)
                            .build();
                })
                .orElse(responseDefinition);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean applyGlobally() {
        return APPLY_GLOBALLY;
    }

    private Context addParametersVariablesToContext(final String url, final Context context) {
        return Unchecked.supplier(() -> {
            final List<NameValuePair> urlParamsAndValues = URLEncodedUtils.parse(new URI(url), Charset.defaultCharset());
            final Map<String, List<NameValuePair>> paramAndValueGroups = urlParamsAndValues.stream()
                    .collect(Collectors.groupingBy(NameValuePair::getName));
            paramAndValueGroups.values().stream()
                    .flatMap(group -> IntStream.range(0, group.size())
                            .mapToObj(i -> {
                                final String keyPostFix = Optional.of(group.size() > 1)
                                        .filter(bool -> bool)
                                        .map(bool -> Integer.toString(i))
                                        .orElse("");
                                final String key = group.get(i).getName()
                                        .concat(keyPostFix)
                                        .replace("-", "");
                                final String value = group.get(i).getValue();
                                return Tuple.tuple(key, value);
                            }))
                    .collect(Collectors.toList())
                    .forEach(tuple -> context.put(tuple.v1, tuple.v2));
            return context;
        }).get();
    }

    private Boolean templateDeclaredAndSpecifiesBodyFile(final ResponseDefinition response) {
        return response.getBodyFileName() != null && (templateDeclared(response) && response.specifiesBodyFile());
    }

    private Boolean templateDeclared(final ResponseDefinition response) {
        final Pattern extension = Pattern.compile(".vm$");
        final Matcher matcher = extension.matcher(response.getBodyFileName());
        return matcher.find();
    }

    private Context addHeadersToContext(final HttpHeaders headers, final Context context) {
        headers.all().forEach(httpHeader -> {
            final String rawKey = httpHeader.key();
            final String transformedKey = rawKey.replaceAll("-", "");
            context.put("requestHeader".concat(transformedKey), httpHeader.values().toString());
        });
        return context;
    }

    private Context addBodyToContext(final String body, final Context context) {
        return Optional.of(body.isEmpty())
                .filter(bool -> bool)
                .map(bool -> context)
                .orElseGet(() -> {
                    final JSONParser parser = new JSONParser(JSONParser.MODE_JSON_SIMPLE);
                    return Unchecked.supplier(() -> {
                        final JSONObject json = (JSONObject) parser.parse(body);
                        context.put("requestBody", json);
                        return context;
                    }).get();
                });
    }

    private String getRenderedBody(final ResponseDefinition response, final Context context) {
        Template template = new Template();
        RuntimeServices runtimeServices = RuntimeSingleton.getRuntimeServices();
        StringReader reader = new StringReader(response.getBody());
        SimpleNode node = null;
        try {
            node = runtimeServices.parse(reader, template);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        template.setRuntimeServices(runtimeServices);
        template.setData(node);
        template.initDocument();
        StringWriter writer = new StringWriter();
        template.merge(context, writer);
        return String.valueOf(writer.getBuffer());
    }

    private String getRenderedFile(final ResponseDefinition response, final Context context) {
        final String templatePath = fileSource.getPath().concat("/" + response.getBodyFileName());
        final Template template = Velocity.getTemplate(templatePath);
        final StringWriter writer = new StringWriter();
        template.merge(context, writer);
        return String.valueOf(writer.getBuffer());
    }
}
