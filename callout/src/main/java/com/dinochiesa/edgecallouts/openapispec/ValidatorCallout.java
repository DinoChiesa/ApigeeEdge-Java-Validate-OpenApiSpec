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

    public ExecutionResult execute(MessageContext msgCtxt, ExecutionContext exeCtxt) {
        try {
            msgCtxt.removeVariable(varName("error"));
            msgCtxt.removeVariable(varName("valid"));

            // validate the request here
            boolean valid = true;
            OasValidator validator = new OasValidator(getSpec(msgCtxt));

            if (getValidateBasePath(msgCtxt)) {
                valid = validator.validateBasePath(msgCtxt.getVariable("proxy.basepath").toString());
                if (!valid) {
                    msgCtxt.setVariable(varName("error"), "invalid basepath");
                    msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                    msgCtxt.setVariable(varName("valid"), false);
                    if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                }
            }

            if (valid) {
                valid = validator.validatePath(getShortPath(msgCtxt));
                if (!valid) {
                    msgCtxt.setVariable(varName("error"), "invalid path");
                    msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                    msgCtxt.setVariable(varName("valid"), false);
                    if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                }
            }

            String verb="";
            if (valid) {
                verb = ((String)msgCtxt.getVariable("request.verb")).toUpperCase();
                valid = validator.validateVerb(verb);
                if (!valid) {
                    msgCtxt.setVariable(varName("error"), "invalid method");
                    msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                    msgCtxt.setVariable(varName("valid"), false);
                    if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                }
            }

            if (valid) {
                valid = validator.validateParameters("params");
                if (!valid) {
                    msgCtxt.setVariable(varName("error"), "invalid parameters");
                    msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                    msgCtxt.setVariable(varName("valid"), false);
                    if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                }
            }

            if (valid) {
                Object accept = msgCtxt.getVariable("request.header.accept");
                if (accept != null) {
                    valid = validator.validateAccept(accept);
                    if (!valid) {
                        msgCtxt.setVariable(varName("error"), "invalid accept header");
                        msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                        msgCtxt.setVariable(varName("valid"), false);
                        if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                    }
                }
            }

            if (valid) {
                if (!(verb.equals("GET") || verb.equals("DELETE") || verb.equals("OPTIONS"))) {
                    valid = validator.validateContentType(msgCtxt.getVariable("request.header.content-type").toString());
                    if (!valid) {
                        msgCtxt.setVariable(varName("error"), "invalid content-type header");
                        msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                        msgCtxt.setVariable(varName("valid"), false);
                        if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                    }
                    valid = validator.validatePayload(msgCtxt.getMessage().getContentAsStream());
                    if (!valid) {
                        msgCtxt.setVariable(varName("error"), "invalid payload");
                        msgCtxt.setVariable(varName("error_detail"), validator.getErrorDetail());
                        msgCtxt.setVariable(varName("valid"), false);
                        if (!getSuppressFault(msgCtxt)) { return ExecutionResult.ABORT; }
                    }
                }
            }

            if (valid) {
                msgCtxt.setVariable(varName("valid"), true);
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
