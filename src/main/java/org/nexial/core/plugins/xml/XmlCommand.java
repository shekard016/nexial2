/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.xml;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jdom2.Attribute;
import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaderJDOMFactory;
import org.jdom2.input.sax.XMLReaderXSDFactory;
import org.jdom2.output.XMLOutputter;
import org.jetbrains.annotations.NotNull;
import org.nexial.commons.utils.CollectionUtil;
import org.nexial.commons.utils.TextUtils;
import org.nexial.commons.utils.XmlUtils;
import org.nexial.core.model.StepResult;
import org.nexial.core.plugins.base.BaseCommand;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.OutputFileUtils;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import static org.jdom2.input.sax.XMLReaders.XSDVALIDATING;
import static org.nexial.core.NexialConst.*;
import static org.nexial.core.NexialConst.Data.toCloudIntegrationNotReadyMessage;
import static org.nexial.core.utils.CheckUtils.*;

public class XmlCommand extends BaseCommand {
    public static final String PROLOG_START = "<?xml version=";
    public static final String DEFAULT_PROLOG = PROLOG_START + "\"1.0\" encoding=\"utf-8\"?>";

    private static class SchemaError implements Serializable {
        String severity;
        int line;
        int column;
        String message;

        public static SchemaError toSchemaError(String severity, SAXParseException exception) {
            if (exception == null) { return null; }
            SchemaError error = new SchemaError();
            error.severity = severity;
            error.line = exception.getLineNumber();
            error.column = exception.getColumnNumber();
            error.message = exception.getMessage();
            return error;
        }
    }

    private class SchemaErrorCollector implements ErrorHandler {
        List<SchemaError> errors = new ArrayList<>();
        boolean hasErrors;

        @Override
        public void warning(SAXParseException exception) {
            errors.add(SchemaError.toSchemaError("warning", exception));
        }

        @Override
        public void error(SAXParseException exception) {
            hasErrors = true;
            errors.add(SchemaError.toSchemaError("ERROR", exception));
        }

        @Override
        public void fatalError(SAXParseException exception) {
            hasErrors = true;
            errors.add(SchemaError.toSchemaError("FATAL", exception));
        }

        public List<SchemaError> getErrors() { return errors; }

        public boolean hasErrors() { return hasErrors; }
    }

    @Override
    public String getTarget() { return "xml"; }

    public StepResult assertElementPresent(String xml, String xpath) {
        Document doc = resolveDoc(xml, xpath);

        try {
            Object match = XmlUtils.findNode(doc, xpath);
            if (match == null) {
                return StepResult.fail("XML does not contain structure as defined by '" + xpath + "'");
            }
            return StepResult.success("XML matches to '" + xpath + "'");
        } catch (Exception e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult assertElementNotPresent(String xml, String xpath) {
        Document doc = resolveDoc(xml, xpath);

        try {
            Object match = XmlUtils.findNode(doc, xpath);
            if (match == null) {
                return StepResult.success("XML does not match '" + xpath + "' as EXPECTED");
            }
            return StepResult.fail("XML matches to '" + xpath + "', which is UNEXPECTED");
        } catch (Exception e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult assertValue(String xml, String xpath, String expected) {
        requiresNotBlank(expected, "invalid expected value", expected);
        try {
            String value = getValueByXPath(xml, xpath);
            if (value == null) {
                if (StringUtils.equals(context.getNullValueToken(), expected)) {
                    return StepResult.success("EXPECTED null value via xpath '" + xpath + "' found");
                } else {
                    return StepResult.fail("XML does not contain structure as defined by '" + xpath + "'");
                }
            }

            return assertEqual(expected, value);
        } catch (Exception e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult assertValues(String xml, String xpath, String array, String exactOrder) {
        requires(StringUtils.isNotBlank(array), "invalid array", array);

        try {
            String values = getValuesByXPath(xml, xpath);
            if (values == null) {
                if (StringUtils.equals(context.getNullValueToken(), array)) {
                    return StepResult.success("EXPECTED null value via xpath '" + xpath + "' found");
                } else {
                    return StepResult.fail("XML does not contain structure as defined by '" + xpath + "'");
                }
            }

            return assertArrayEqual(array, values, exactOrder);
        } catch (Exception e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult assertElementCount(String xml, String xpath, String count) {
        int countInt = toPositiveInt(count, "count");
        try {
            return assertEqual(countInt + "", count(xml, xpath) + "");
        } catch (JDOMException e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult storeValue(String xml, String xpath, String var) {
        requires(StringUtils.isNotBlank(var), "invalid variable name", var);
        try {
            String value = getValueByXPath(xml, xpath);
            if (value == null) {
                return StepResult.fail("XML does not contain structure as defined by '" + xpath + "'");
            }

            updateDataVariable(var, value);
            return StepResult.success("XML matches saved to '" + var + "'");
        } catch (Exception e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult storeValues(String xml, String xpath, String var) {
        requires(StringUtils.isNotBlank(var), "invalid variable name", var);

        try {
            String values = getValuesByXPath(xml, xpath);
            if (values == null) {
                return StepResult.fail("XML does not contain structure as defined by '" + xpath + "'");
            }

            updateDataVariable(var, values);
            return StepResult.success("XML matches saved to '" + var + "'");
        } catch (Exception e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult storeCount(String xml, String xpath, String var) {
        requires(StringUtils.isNotBlank(var), "invalid variable name", var);

        try {
            context.setData(var, count(xml, xpath));
            return StepResult.success("XML matches saved to '" + var + "'");
        } catch (JDOMException e) {
            return StepResult.fail("Error while filtering XML via xpath '" + xpath + "': " + e.getMessage());
        }
    }

    public StepResult assertCorrectness(String xml, String schema) {
        Document doc = deriveWellformedXml(xml);
        if (doc == null) { return StepResult.fail("invalid xml: " + xml); }

        SAXBuilder builder;
        if (StringUtils.isNotBlank(schema) && !context.isNullValue(schema)) {
            try {
                Source[] schemaSources = getSchemaSources(schema);
                XMLReaderJDOMFactory schemaFactory = new XMLReaderXSDFactory(schemaSources);
                builder = new SAXBuilder(schemaFactory);
            } catch (IOException e) {
                ConsoleUtils.log("Error reading schema as file '" + schema + "': " + e.getMessage());
                return StepResult.fail("Error reading as file '" + schema + "': " + e.getMessage());
            } catch (JDOMException e) {
                String error = "Error when loading schema: " + e.getMessage();
                Throwable t = e.getCause();
                if (t != null) { error += ", " + t.getMessage(); }
                ConsoleUtils.log(error);
                e.printStackTrace();
                return StepResult.fail(error);
            }
        } else {
            builder = new SAXBuilder(XSDVALIDATING);
        }

        SchemaErrorCollector errorHandler = new SchemaErrorCollector();
        builder.setErrorHandler(errorHandler);

        try {
            xml = cleanXmlContent(OutputFileUtils.resolveContent(xml, context, false));
            if (StringUtils.isBlank(xml)) { return StepResult.fail("empty XML found"); }

            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes());
            builder.build(input);

            if (errorHandler.hasErrors()) {
                generateErrorLogs(errorHandler);
                return StepResult.fail("xml validation failed");
            } else {
                return StepResult.success("xml validated successfully");
            }
        } catch (IOException e) {
            String message = "Error reading as file '" + xml + "': " + e.getMessage();
            ConsoleUtils.log(message);
            return StepResult.fail(message);
        } catch (JDOMException e) {
            String message = "Error when validating xml: " + e.getMessage();
            ConsoleUtils.log(message);
            return StepResult.fail(message);
        }
    }

    public StepResult assertWellformed(String xml) {
        Document doc = deriveWellformedXml(xml);
        return doc == null ?
               StepResult.fail("invalid xml: " + xml) :
               StepResult.success("xml validated as well-formed");
    }

    public StepResult beautify(String xml, String var) { return format(xml, var, PRETTY_XML_OUTPUTTER); }

    public StepResult minify(String xml, String var) { return format(xml, var, COMPRESSED_XML_OUTPUTTER); }

    public StepResult append(String xml, String xpath, String content, String var) throws IOException {
        return modify(xml, xpath, content, var, Modification.Companion.getAppend());
    }

    public StepResult prepend(String xml, String xpath, String content, String var) throws IOException {
        return modify(xml, xpath, content, var, Modification.Companion.getPrepend());
    }

    public StepResult insertAfter(String xml, String xpath, String content, String var) throws IOException {
        return modify(xml, xpath, content, var, Modification.Companion.getInsertAfter());
    }

    public StepResult insertBefore(String xml, String xpath, String content, String var) throws IOException {
        return modify(xml, xpath, content, var, Modification.Companion.getInsertBefore());
    }

    public StepResult replaceIn(String xml, String xpath, String content, String var) throws IOException {
        return modify(xml, xpath, content, var, Modification.Companion.getReplaceIn());
    }

    public StepResult replace(String xml, String xpath, String content, String var) throws IOException {
        return modify(xml, xpath, content, var, Modification.Companion.getReplace());
    }

    public StepResult delete(String xml, String xpath, String var) throws IOException {
        return modify(xml, xpath, null, var, Modification.Companion.getDelete());
    }

    public StepResult clear(String xml, String xpath, String var) throws IOException {
        return modify(xml, xpath, null, var, Modification.Companion.getClear());
    }

    public String getValuesByXPath(String xml, String xpath) {
        List<String> buffer = getValuesListByXPath(xml, xpath);
        if (buffer == null) { return null; }

        // support junit
        String delim = context == null ? "|" : context.getTextDelim();
        return CollectionUtil.toString(buffer, delim);
    }

    public List<String> getValuesListByXPath(String xml, String xpath) {
        Document doc = resolveDoc(xml, xpath);
        List matches = XmlUtils.findNodes(doc, xpath);
        if (CollectionUtils.isEmpty(matches)) { return null; }

        List<String> buffer = new ArrayList<>();
        for (Object match : matches) {
            if (match instanceof Element) {
                buffer.add(StringUtils.trim(((Element) match).getTextNormalize()));
            } else if (match instanceof Content) {
                buffer.add(StringUtils.trim(((Content) match).getValue()));
            } else if (match instanceof Attribute) {
                buffer.add(StringUtils.defaultIfEmpty(((Attribute) match).getValue(), ""));
            } else {
                buffer.add(StringUtils.trim(match.toString()));
            }
        }

        return buffer;
    }

    public String getValueByXPath(String xml, String xpath) {
        return getValueByXPath(resolveDoc(xml, xpath), xpath);
    }

    public static String getValueByXPath(Document doc, String xpath) {
        Object match = XmlUtils.findNode(doc, xpath);
        if (match == null) { return null; }

        if (match instanceof Element) { return StringUtils.trim(((Element) match).getTextNormalize()); }
        if (match instanceof Attribute) { return StringUtils.trim(((Attribute) match).getValue()); }
        if (match instanceof Content) { return StringUtils.trim(((Content) match).getValue()); }
        return StringUtils.trim(match.toString());
    }

    public static String cleanXmlContent(String xml) {
        // sanity check
        if (StringUtils.isBlank(xml)) { return xml; }

        String trimmed = StringUtils.trim(xml);
        if (!StringUtils.contains(trimmed, "<") || !StringUtils.contains(trimmed, ">")) { return xml; }

        int startOfProlog = StringUtils.indexOf(trimmed, PROLOG_START);
        if (startOfProlog > 0) { trimmed = StringUtils.substring(trimmed, startOfProlog); }
        if (!StringUtils.startsWith(trimmed, PROLOG_START)) { trimmed = DEFAULT_PROLOG + trimmed; }

        int lastCloseTag = StringUtils.lastIndexOf(trimmed, ">");
        if (lastCloseTag < StringUtils.length(trimmed)) {
            trimmed = StringUtils.substring(trimmed, 0, lastCloseTag + 1);
        }

        return trimmed;
    }

    @NotNull
    protected StepResult modify(String xml, String xpath, String content, String var, Modification modification)
        throws IOException {

        if (modification == null) { throw new IllegalArgumentException("modification NOT specified!"); }

        requiresNotBlank(xml, "Invalid xml", xml);
        requiresNotBlank(xpath, "Invalid xpath", xpath);
        if (modification.getRequireInput()) {
            content = OutputFileUtils.resolveContent(content, context, false, true);
            requiresNotBlank(content, "Invalid content", content);
        }

        Document doc = deriveWellformedXml(xml);
        List matches = XmlUtils.findNodes(doc, xpath);
        if (CollectionUtils.isEmpty(matches)) {
            return StepResult.fail("No matches found on target XML using xpath '" + xpath + "'");
        }

        int edits = modification.modify(matches, content);
        updateDataVariable(var, XmlUtils.toPrettyXml(doc.getRootElement()));

        return StepResult.success(edits + " edit(s) made to XML and save to '" + var + "'");
    }

    protected void generateErrorLogs(XmlCommand.SchemaErrorCollector errorHandler) {
        String outFile = context.generateTestStepOutput("log");

        String output = TextUtils.createAsciiTable(Arrays.asList("severity", "line", "column", "message"),
                                                   errorHandler.getErrors(),
                                                   (row, position) -> {
                                                       if (position == 0) { return row.severity; }
                                                       if (position == 1) { return row.line + ""; }
                                                       if (position == 2) { return row.column + ""; }
                                                       if (position == 3) { return row.message; }
                                                       return "";
                                                   });
        if (StringUtils.isBlank(output)) {
            ConsoleUtils.error("Unable to generate schema validation log");
        } else {
            File outputFile = new File(outFile);
            try {
                FileUtils.writeStringToFile(outputFile, output, DEF_FILE_ENCODING);
                if (context.isOutputToCloud()) {
                    try {
                        outFile = context.getOtc().importMedia(outputFile);
                    } catch (IOException e) {
                        log(toCloudIntegrationNotReadyMessage(outFile) + ": " + e.getMessage());
                    }
                }

                String caption = "Validation error(s) found (click link on the right for details)";
                addLinkRef(caption, "errors", outFile);
            } catch (IOException e) {
                error("Unable to write log file to '" + outFile + "': " + e.getMessage(), e);
            }
        }
    }

    protected Source[] getSchemaSources(String schema) throws IOException {
        List<Source> sources = new ArrayList<>();
        String[] schemas = StringUtils.splitByWholeSeparator(schema, context.getTextDelim());
        for (String schemaLocation : schemas) {
            String schemaContent = OutputFileUtils.resolveContent(schemaLocation, context, false);
            if (StringUtils.isNotBlank(schemaContent)) {
                log("resolving schema content via " + schemaLocation + "...");
                ByteArrayInputStream schemaStream = new ByteArrayInputStream(schemaContent.getBytes());
                sources.add(new StreamSource(schemaStream));
            }
        }

        // one last try..
        if (CollectionUtils.isEmpty(sources)) {
            String schemaContent = OutputFileUtils.resolveContent(schema, context, false);
            ByteArrayInputStream schemaStream = new ByteArrayInputStream(schemaContent.getBytes());
            sources.add(new StreamSource(schemaStream));
        }

        return sources.toArray(new Source[sources.size()]);
    }

    protected Document deriveWellformedXml(String xml) {
        requires(StringUtils.isNotBlank(xml), "invalid xml", xml);

        try {
            // support path-based content specification
            xml = cleanXmlContent(OutputFileUtils.resolveContent(xml, context, false));
            if (StringUtils.isBlank(xml)) {
                ConsoleUtils.log("empty XML found");
                return null;
            } else {
                return XmlUtils.parse(xml);
            }
        } catch (IOException e) {
            ConsoleUtils.log("Error reading as file '" + xml + "': " + e.getMessage());
        } catch (JDOMException e) {
            ConsoleUtils.log("Error when validating xml: " + e.getMessage());
        }

        return null;
    }

    protected Document deriveWellformedXmlQuietly(String xml) {
        if (StringUtils.isBlank(xml)) { return null; }

        try {
            // support path-based content specification
            xml = cleanXmlContent(OutputFileUtils.resolveContent(xml, context, false));
            if (StringUtils.isBlank(xml)) { return null; }
            if (!StringUtils.startsWith(xml, "<") || !StringUtils.endsWith(xml, ">")) { return null; }

            return XmlUtils.parse(xml);
        } catch (IOException | JDOMException e) {
            // shh.. exit quietly...
        }

        return null;
    }

    protected Document resolveDoc(String xml, String xpath) {
        requires(StringUtils.isNotBlank(xml), "invalid xml", xml);
        requires(StringUtils.isNotBlank(xpath), "invalid xpath", xpath);

        Document doc = null;
        try {
            // support file-based content specification
            xml = cleanXmlContent(OutputFileUtils.resolveContent(xml, context, false));
            requiresNotBlank(xml, "empty XML found");

            doc = XmlUtils.parse(xml);
            requires(doc != null, "invalid/malformed xml", xml);
        } catch (JDOMException | IOException e) {
            ConsoleUtils.log("invalid/malformed xml: " + e.getMessage());
        }

        return doc;
    }

    protected int count(String xml, String xpath) throws JDOMException {
        return XmlUtils.count(resolveDoc(xml, xpath), xpath);
    }

    private StepResult format(String xml, String var, XMLOutputter outputter) {
        requiresValidVariableName(var);
        requiresNotBlank(xml, "invalid xml", xml);

        Document doc = deriveWellformedXml(xml);
        if (doc == null) { return StepResult.fail("invalid xml: " + xml); }

        String action = "XML " + (outputter == COMPRESSED_XML_OUTPUTTER ? "minification" : "beautification");

        String outputXml = outputter.outputString(doc);
        if (StringUtils.isBlank(outputXml)) { return StepResult.fail(action + " failed with blank XML content"); }

        updateDataVariable(var, outputXml.trim());
        return StepResult.success(action + " completed and saved to '" + var + "'");
    }
}
