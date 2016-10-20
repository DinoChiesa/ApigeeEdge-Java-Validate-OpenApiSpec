# Java callout - for OpenAPI Spec Validation

This directory contains the Java source code and Java jars required to
compile a Java callout for Apigee Edge that performs validation of
a request against an OpenAPI Spec. 
For more information on Open API Spec, see [Open API Spec](https://github.com/OAI/OpenAPI-Specification). 
This Java callout relies on the [swagger-parser v1.0.8](https://github.com/swagger-api/swagger-parser).

You do not need to compile the Java code in order to use this
callout. It's ready for you to use, as is. However, if you wish to modify and
recompile the code, for whatever reason, you can do so.

## License

This material is copyright 2016 Apigee Corporation, 
and is licensed under the [Apache 2.0 License](LICENSE). This includes the Java code as well as the API Proxy configuration. 


## Usage

The Java JAR here can be used as a Java callout in Apigee Edge. It is expected to run in the request flow, probably in the pre-flow.  It validates the inbound request against a spec, and returns ABORT and sets the flow into fault state if the request is not acceptable according to the spec. 

The callout always reads from the request context variables - request.content, request.verb, request.headers and so on.


### Schema URL

The typical case is to specify a URL at which the OAS spec can be read in. 

```xml
<JavaCallout name='Java-ValidateRequest-1'>
  <Properties>
    <Property name='spec'>https://raw.githubusercontent.com/OAI/OpenAPI-Specification/master/examples/v2.0/yaml/petstore-minimal.yaml</Property>
  </Properties>

  <ClassName>com.dinochiesa.edgecallouts.openapispec.ValidatorCallout</ClassName>
  <ResourceURL>java://edge-custom-oas-validator.jar</ResourceURL>
</JavaCallout>
```

You can also reference a URL in a context variable, by surrounding it with curly-braces.

```xml
<JavaCallout name='Java-ValidateRequest-1'>
  <Properties>
    <Property name='spec'>{url_for_oas}</Property>
  </Properties>
  <ClassName>com.dinochiesa.edgecallouts.openapispec.ValidatorCallout</ClassName>
  <ResourceURL>java://edge-custom-oas-validator.jar</ResourceURL>
</JavaCallout>
```

### Spec in a Resource file

You can also specify a schema file to be found in the /resources
directory of the JAR that contains the callout class. The string must
end with the 5 characters ".json" in order to be recognized as a schema
file. You can specify the schema file name this way:

```xml
<JavaCallout name='Java-ValidateRequest'>
  <DisplayName>Java-ValidateSchema</DisplayName>
  <Properties>
    <!-- find this spec in the JAR under /resources -->
    <Property name='spec'>spec.yaml</Property>
  </Properties>
  <ClassName>com.dinochiesa.edgecallouts.openapispec.ValidatorCallout</ClassName>
  <ResourceURL>java://edge-custom-oas-validator.jar</ResourceURL>
</JavaCallout>
```

This requires that you bundle the schema file into the JAR; in other words, you must recompile the JAR. 


The named spec must exist in the edge-custom-oas-validator.jar. It must end in .json or .yaml and  must be in the resources directory.  The content of the jar
should look like this: 

        meta-inf/
        meta-inf/manifest.mf
        com/
        com/dinochiesa
        com/dinochiesa/edgecallouts
        com/dinochiesa/edgecallouts/openapispec/
        com/dinochiesa/edgecallouts/openapispec/ValidatorCallout.class
        resources/
        resources/spec1.yaml
        resources/spec2.json

You can just drop spec files into the [resources](src/main/resources) directory and rebuild with maven, to make this happen. 


### Spec in a Variable that refers to a Resource file

Finally, you can specify a context variable that contains the spec
file name. This variable will get resolved at runtime. The content of
the variable must end with the 5 characters ".json" or ".yaml" in order to be
recognized as a spec file.  The syntax looks like this:

```xml
<JavaCallout name='Java-ValidateRequest'>
  <DisplayName>Java-ValidateSchema</DisplayName>
  <Properties>
    <!-- find this spec in the JAR under /resources -->
    <Property name='spec'>{context_var_that_contains_name_of_spec_resource}</Property>
  </Properties>
  <ClassName>com.dinochiesa.edgecallouts.openapispec.ValidatorCallout</ClassName>
  <ResourceURL>java://edge-custom-oas-validator.jar</ResourceURL>
</JavaCallout>
```

As above, this also requires that you bundle the referenced schema file into the JAR as a resource.

## Behavior

By default, the Java callout will return ExecutionResult.ABORT, and implicitly put the proxy flow into a Fault state, when:

* the configuration of the policy is incorrect. For example, if there is no spec property present, or if the spec is invalid. 
* validation of the request against the spec fails. 

You can suppress the faults by using a property in the configuration, like this: 

```xml
<JavaCallout name='Java-ValidateRequest-4'>
  <Properties>
    <Property name='suppress-fault'>true</Property>
    <Property name='spec'>{context_var_that_contains_name_of_spec_resource}</Property>
  </Properties>
  <ClassName>com.dinochiesa.edgecallouts.openapispec.ValidatorCallout</ClassName>
  <ResourceURL>java://edge-custom-oas-validator.jar</ResourceURL>
</JavaCallout>
```

Whether or not the policy throws a fault, the policy sets these variables:

| variable name    | meaning                           |
|:-----------------|:----------------------------------|
| oas_valid        | true if the inbound request (verb, URL, headers, payload0 message was valid with respect to the spec. false if not. |
| oas_error        | null if no error. a string indicating the error if the inbound request was invalid, or if there was another error (eg, invalid configuration) |
| oas_error_detail | additional detail related to an error that occurred. For example, this may indicate the expected Accept header values, versus the provided Accept header values. |


### Validating the Base Path

By default the policy does not validate the basepath on the request. The
presumption is that the basepath has already been validated by Edge when
it received the request. There might be cases in which you would like to
use the Java callout to also validate the basepath.  As one example,
imagine a shared-flow, which applies to multiple distinct proxies.

To have the callout also validate the basepath, you can use a configuration like this:

```xml
<JavaCallout name='Java-ValidateRequest-5'>
  <Properties>
    <Property name='validate-base-path'>true</Property>
    <Property name='spec'>{context_var_that_contains_name_of_spec_resource}</Property>
  </Properties>
  <ClassName>com.dinochiesa.edgecallouts.openapispec.ValidatorCallout</ClassName>
  <ResourceURL>java://edge-custom-oas-validator.jar</ResourceURL>
</JavaCallout>
```


## Building

Build the project with maven.  Like so:

```
  mvn clean install
```


