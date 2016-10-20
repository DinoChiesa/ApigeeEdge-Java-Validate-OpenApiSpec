package com.dinochiesa.edgecallouts.openapispec;

import java.io.InputStream;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.nio.charset.StandardCharsets;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.IOIntensive;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;
import com.apigee.flow.message.Message;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.CacheLoader;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.parser.SwaggerParser;
import io.swagger.models.Swagger;
import io.swagger.models.HttpMethod;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.io.IOUtils;

public class ValidatorCallout implements Execution {

    private static String _varPrefix = "oas_";
    private static final String varName(String s) { return _varPrefix + s; }
    private static ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally
    private static SwaggerParser swaggerParser = new SwaggerParser();

    private Map properties; // read-only

    // It is expensive to initialize a Swagger. Also, instances of
    // Swagger are thread safe, once initialized. Therefore we want
    // to use a cache of these things.

    private final static int OAS_CACHE_MAX_EXTRIES = 1024;
    private final static int OAS_CACHE_WRITE_CONCURRENCY = 6;
    private static LoadingCache<String,Swagger> oasCache;

    static {
        oasCache =
            CacheBuilder.newBuilder()
            .concurrencyLevel(OAS_CACHE_WRITE_CONCURRENCY)
            .maximumSize(OAS_CACHE_MAX_EXTRIES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String,Swagger>() {
                    public Swagger load(String source) throws IOException {
                        if (source.startsWith("http://") || source.startsWith("https://")) {
                            // read from a URL
                            return swaggerParser.read(source);
                        }
                        if (source.startsWith("{") && source.endsWith("}")) {
                            // read from JSON directly
                            return swaggerParser.parse(source);
                        }
                        if (source.startsWith("---")) {
                            // read from YAML directly
                            return swaggerParser.parse(source);
                        }

                        // assume this is a name of a resource embedded into the JAR
                        InputStream in = getResourceAsStream(source);
                        byte[] bytes = IOUtils.toByteArray(in);
                        String stringContent = new String(bytes, StandardCharsets.UTF_8);
                        return swaggerParser.parse(stringContent);
                    }
                });
    }

    public ValidatorCallout (Map properties) {
        this.properties = properties;
    }

    private boolean getSuppressFault(MessageContext msgCtxt) {
        String suppressFault = (String) this.properties.get("suppress-fault");
        if (StringUtils.isBlank(suppressFault)) { return false; }
        suppressFault = resolvePropertyValue(suppressFault, msgCtxt);
        if (StringUtils.isBlank(suppressFault)) { return false; }
        return suppressFault.toLowerCase().equals("true");
    }

    private String getSpec(MessageContext msgCtxt) throws Exception {
        String spec = (String) this.properties.get("spec");
        if (spec == null) {
            throw new IllegalStateException("spec is not specified");
        }
        spec = spec.trim();
        if (spec.equals("")) {
            throw new IllegalStateException("spec is empty");
        }

        spec = resolvePropertyValue(spec, msgCtxt);
        if (spec == null || spec.equals("")) {
            throw new IllegalStateException("spec resolves to an empty string");
        }
        spec = spec.trim();
        if (spec.endsWith(".json")) {
            if (getDebug())
                msgCtxt.setVariable(varName("specName"), spec);
        }

        return spec;
    }


    // private String getJson(MessageContext msgCtxt) throws Exception {
    //     String jsonref = (String) this.properties.get("jsonref");
    //     String json = null;
    //     // jsonref is the name of the variable that holds the json
    //     if (jsonref == null || jsonref.equals("")) {
    //         throw new IllegalStateException("jsonref is not specified or is empty.");
    //     }
    //     else {
    //         json = msgCtxt.getVariable(jsonref);
    //     }
    //     return json;
    // }

    // If the value of a property value begins and ends with curlies,
    // eg, {apiproxy.name}, then "resolve" the value by de-referencing
    // the context variable whose name appears between the curlies.
    private String resolvePropertyValue(String spec, MessageContext msgCtxt) {
        if (spec.startsWith("{") && spec.endsWith("}") && (spec.indexOf(" ")==-1)) {
            String varname = spec.substring(1,spec.length() - 1);
            String value = msgCtxt.getVariable(varname);
            return value;
        }
        return spec;
    }

    private static InputStream getResourceAsStream(String resourceName)
      throws IOException {
        // forcibly prepend a slash
        if (!resourceName.startsWith("/")) {
            resourceName = "/" + resourceName;
        }
        if (!resourceName.startsWith("/resources")) {
            resourceName = "/resources" + resourceName;
        }
        InputStream in = ValidatorCallout.class.getResourceAsStream(resourceName);

        if (in == null) {
            throw new IOException("resource \"" + resourceName + "\" not found");
        }

        return in;
    }

    private boolean getDebug() {
        String value = (String) this.properties.get("debug");
        if (value == null) return false;
        if (value.trim().toLowerCase().equals("true")) return true;
        return false;
    }

    public ExecutionResult execute(MessageContext msgCtxt, ExecutionContext exeCtxt) {
        try {
            msgCtxt.removeVariable(varName("error"));
            msgCtxt.removeVariable(varName("valid"));

            String specId = getSpec(msgCtxt);
            Swagger openApiSpec = oasCache.get(specId);

            // validate the request here
            String urlPath = msgCtxt.getVariable("request.url");
            io.swagger.models.Path oasPath = openApiSpec.getPath(urlPath);
            if (oasPath == null) {
                msgCtxt.setVariable(varName("error"), "invalid path");
                msgCtxt.setVariable(varName("valid"), false);
                if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
            }
            else {
                String verb = msgCtxt.getVariable("request.verb");
                io.swagger.models.HttpMethod oasMethod = HttpMethod.valueOf(verb.trim().toUpperCase());
                Map<io.swagger.models.HttpMethod, io.swagger.models.Operation> opsMap = oasPath.getOperationMap();
                if (!opsMap.containsKey(oasMethod)) {
                    msgCtxt.setVariable(varName("error"), "invalid method");
                    msgCtxt.setVariable(varName("valid"), false);
                    if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                }
                else {
                    io.swagger.models.Operation oasOperation = opsMap.get(oasMethod);

                    InputStream src = msgCtxt.getMessage().getContentAsStream();
                    JsonNode contentJson = mapper.readValue(src, JsonNode.class);


                    msgCtxt.setVariable(varName("valid"), true);
                }
            }
        }
        catch (Exception e) {
            if (getDebug()) {
                System.out.println(ExceptionUtils.getStackTrace(e));
            }
            String error = e.toString();
            msgCtxt.setVariable(varName("exception"), error);
            int ch = error.lastIndexOf(':');
            if (ch >= 0) {
                msgCtxt.setVariable(varName("error"), error.substring(ch+2).trim());
            }
            else {
                msgCtxt.setVariable(varName("error"), error);
            }
            msgCtxt.setVariable(varName("stacktrace"), ExceptionUtils.getStackTrace(e));
            msgCtxt.setVariable(varName("success"), false);

            if (getSuppressFault(msgCtxt)) return ExecutionResult.SUCCESS;
            return ExecutionResult.ABORT;
        }
        return ExecutionResult.SUCCESS;
    }
}
