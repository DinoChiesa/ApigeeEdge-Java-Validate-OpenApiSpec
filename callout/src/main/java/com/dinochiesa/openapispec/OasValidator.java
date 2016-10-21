package com.dinochiesa.openapispec;

import io.swagger.models.Swagger;
import io.swagger.models.Path;
import io.swagger.models.Operation;
import io.swagger.models.parameters.Parameter;
import io.swagger.parser.SwaggerParser;
import io.swagger.util.Json;
import io.swagger.util.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.CacheLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.Map;
import java.util.HashMap;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import java.util.ArrayList;

public class OasValidator {

    private Swagger openApiSpec;
    private Path path;
    private Operation operation;
    private String[] errorInfo;
    // private Map<String, io.swagger.models.Operation> operationsMap;

    private static SwaggerParser swaggerParser = new SwaggerParser();
    private static ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally

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
                            ObjectMapper yamlMapper = Yaml.mapper();
                            return yamlMapper.readValue(source, Swagger.class);
                            //return swaggerParser.parse(source);
                        }

                        // assume this is a name of a resource embedded into the JAR
                        InputStream in = getResourceAsStream(source);
                        byte[] bytes = IOUtils.toByteArray(in);
                        String stringContent = new String(bytes, StandardCharsets.UTF_8);
                        return swaggerParser.parse(stringContent);
                    }
                });
    }


    public interface ParameterRetriever {
        public String get(String name);
    }

    public OasValidator(String specId) throws ExecutionException {
        openApiSpec = oasCache.get(specId);
    }

    public boolean validateBasePath(String basePath) {
        String expectedBasePath = openApiSpec.getBasePath();
        boolean ok = expectedBasePath.equals(basePath);
        if(!ok) {
            errorInfo = new String[] {
                "invalid basepath",
                String.format("basepath of (%s) does not match expected (%s)", basePath, expectedBasePath)
            };
        }
        return ok;
    }

    public boolean validatePath(String urlPath) {
        path = openApiSpec.getPath(urlPath);
        boolean ok = (path != null);
        if(!ok) {
            errorInfo = new String[] {
                "invalid path",
                String.format("no path found for (%s)", urlPath)
            };
        }
        return ok;
    }

    public boolean validateVerb(String verb) {
        operation = findOperation(verb);
        boolean ok = (operation != null);
        if(!ok) {
            errorInfo = new String[] {
                "invalid method",
                String.format("no operation found for the verb of (%s)", verb)
            };
        }
        return ok;
    }

    public boolean validateAccept(Object arg) throws IllegalStateException {
        if (operation==null) throw new IllegalStateException("call validateVerb before validateAccept");
        String[] accepts = null;
        if (arg instanceof String[]) {
            accepts = (String[]) arg;
        }
        else if (arg instanceof String) {
            if (StringUtils.isBlank((String) arg)) return true;
            accepts = ((String)arg).split(",");
        }
        else {
            String msg = String.format("unknown object type (%s)", arg.getClass().getSimpleName());
            throw new IllegalStateException(msg);
        }

        if (accepts == null || accepts.length==0) return true;
        // TODO: must we consider the Produces on the entire swagger, also?
        List<String> produces = operation.getProduces();
        boolean ok = false;
        for (String a : accepts) {
            if (!ok) {
                if (a.equals("*/*") || produces.contains(a)) { ok = true; }
            }
        }
        if(!ok) {
            errorInfo = new String[] {
                "invalid accept header",
                String.format("the accept values of [%s] was not valid", Arrays.toString(accepts))
            };
        }
        return ok;
    }

    public boolean validateContentType(String ctype) throws IllegalStateException {
        if (operation==null) throw new IllegalStateException("call validateVerb before validateContentType");
        if (StringUtils.isBlank(ctype)) return false;
        List<String> consumes = operation.getConsumes();
        // TODO: consider encoding?
        boolean ok = consumes.contains(ctype);
        if(!ok) {
            errorInfo = new String[] {
                "invalid content-type header",
                String.format("content-type of (%s) is not supported", ctype)
            };
        }
        return ok;
    }

    public boolean validateParameters(ParameterRetriever headers, ParameterRetriever qparams)
        throws IllegalStateException {
        if (operation==null) throw new IllegalStateException("call validateVerb before validateParameters");
        boolean ok = true;
        List<String> missing = new ArrayList<String>();
        List<Parameter> params = operation.getParameters();
        for(Parameter p : params) {
            if(p.getIn().equals("query")) {
                String name = p.getName();
                String paramValue = qparams.get(name);
                if (paramValue == null)  {
                    missing.add("qparam:"+name);
                }
            }
            else if(p.getIn().equals("header")) {
                String name = p.getName();
                String paramValue = headers.get(name);
                if (paramValue == null)  {
                    missing.add("header:"+name);
                }
            }
        }

        if(!ok) {
            errorInfo = new String[] {
                "invalid parameters",
                String.format("missing parameters %s",
                              Arrays.toString(missing.toArray(new String[missing.size()])))
            };
        }
        return ok;
    }

    public boolean validatePayload(InputStream src) throws IllegalStateException, IOException {
        if (operation==null) throw new IllegalStateException("call validateVerb before validateAccept");
        JsonNode contentJson = mapper.readValue(src, JsonNode.class);
        // TODO: validate against the schema here. Which schema? How can I get it?
        boolean ok = true;
        if(!ok) {
            errorInfo = new String[] {
                "invalid payload",
                String.format("something something (%s) not supported", "something")
            };
        }
        return ok;
    }

    public Path getPath() {
        return path;
    }
    public Operation getOperation() {
        return operation;
    }
    public String[] getErrorInfo() {
        return errorInfo;
    }

    // private Map<String, io.swagger.models.Operation> populateOperationsMap() {
    //     if (operationsMap==null) {
    //     operationsMap = new HashMap<String, io.swagger.models.Operation>();
    //     operationsMap.put("get", path.getGet());
    //     operationsMap.put("put", path.getPut());
    //     operationsMap.put("post", path.getPost());
    //     operationsMap.put("delete", path.getDelete());
    //     operationsMap.put("patch", path.getPatch());
    //     operationsMap.put("options", path.getOptions());
    //     }
    //     return operationsMap;
    // }

    private Operation findOperation(String verb) {
        switch(verb.toUpperCase()) {
            case "GET":
                return path.getGet();
            case "PUT":
                return path.getPut();
            case "POST":
                return path.getPost();
            case "DELETE":
                return path.getDelete();
            case "PATCH":
                return path.getPatch();
            case "OPTIONS":
                return path.getOptions();
            default:
                return null;
        }
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
        InputStream in = OasValidator.class.getResourceAsStream(resourceName);

        if (in == null) {
            throw new IOException("resource \"" + resourceName + "\" not found");
        }

        return in;
    }

}
