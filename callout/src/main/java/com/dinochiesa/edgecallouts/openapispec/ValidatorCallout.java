package com.dinochiesa.edgecallouts.openapispec;

import java.util.Map;
import java.util.List;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.IOIntensive;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;
import com.apigee.flow.message.Message;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.StringUtils;

import com.dinochiesa.openapispec.OasValidator;

public class ValidatorCallout implements Execution {

    private static String _varPrefix = "oas_";
    private static final String varName(String s) { return _varPrefix + s; }

    private Map properties; // read-only

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

    private String getShortPath(MessageContext msgCtxt) {
        String fullPath = msgCtxt.getVariable("request.path").toString();
        String basePath = msgCtxt.getVariable("proxy.basepath").toString();
        return (fullPath.startsWith(basePath)) ? fullPath.substring(basePath.length()): fullPath;
    }

    private boolean getValidateBasePath(MessageContext msgCtxt) {
        String validateBasePath = (String) this.properties.get("validate-base-path");
        if (StringUtils.isBlank(validateBasePath)) { return false; }
        validateBasePath = resolvePropertyValue(validateBasePath, msgCtxt);
        if (StringUtils.isBlank(validateBasePath)) { return false; }
        return validateBasePath.toLowerCase().equals("true");
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

    private boolean getDebug() {
        String value = (String) this.properties.get("debug");
        if (value == null) return false;
        if (value.trim().toLowerCase().equals("true")) return true;
        return false;
    }

    public class Retriever implements OasValidator.ParameterRetriever {
        private MessageContext msgCtxt;
        private String varSegment;
        public Retriever(String segment, MessageContext context) {
            msgCtxt = context;
            varSegment = segment;
        }

        public String get(String name) {
            String varName = "request." + varSegment + "." + name;
            return (String) (msgCtxt.getVariable(varName));
        }
    }


    public ExecutionResult execute(MessageContext msgCtxt, ExecutionContext exeCtxt) {
        try {
            msgCtxt.removeVariable(varName("error"));
            msgCtxt.removeVariable(varName("valid"));

            // validate the request here
            boolean valid = true;
            OasValidator validator = new OasValidator(getSpec(msgCtxt));

            // It might be simpler to collapse all of this validation down into one call
            // into the OasValidator class. But I think I might want to make the list of
            // validations configurable. For now the validates are individual, separate calls.
            // We may change the interface to that class later.

            if (getValidateBasePath(msgCtxt)) {
                valid = validator.validateBasePath(msgCtxt.getVariable("proxy.basepath").toString());
            }

            if (valid) {
                valid = validator.validatePath(getShortPath(msgCtxt));
            }

            String verb="";
            if (valid) {
                verb = ((String)msgCtxt.getVariable("request.verb")).toUpperCase();
                valid = validator.validateVerb(verb);
            }

            if (valid) {
                valid = validator.validateParameters(new Retriever("queryparam", msgCtxt),
                                                     new Retriever("header", msgCtxt));
            }

            if (valid) {
                Object accept = msgCtxt.getVariable("request.header.accept");
                if (accept != null) {
                    valid = validator.validateAccept(accept);
                }
            }

            if (valid) {
                if (!(verb.equals("GET") || verb.equals("DELETE") || verb.equals("OPTIONS"))) {
                    Object ctype = msgCtxt.getVariable("request.header.content-type");
                    if (ctype != null) {
                        valid = validator.validateContentType(ctype.toString());
                    }
                    if (valid)
                        valid = validator.validatePayload(msgCtxt.getMessage().getContentAsStream());
                }
            }

            if (valid) {
                msgCtxt.setVariable(varName("valid"), true);
            }
            else {
                String[] errorInfo = validator.getErrorInfo();
                msgCtxt.setVariable(varName("error"), errorInfo[0]);
                msgCtxt.setVariable(varName("error_detail"), errorInfo[1]);
                msgCtxt.setVariable(varName("valid"), false);
                if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
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
