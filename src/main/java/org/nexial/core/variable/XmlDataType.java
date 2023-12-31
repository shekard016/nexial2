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

package org.nexial.core.variable;

import java.io.IOException;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.nexial.commons.utils.XmlUtils;
import org.nexial.core.utils.ConsoleUtils;

import static java.lang.System.lineSeparator;

public class XmlDataType extends ExpressionDataType<Element> {
    private XmlTransformer transformer = new XmlTransformer();

    private Document document;

    public XmlDataType(String textValue) throws TypeConversionException { super(textValue); }

    private XmlDataType() { super(); }

    @Override
    public String getName() { return "XML"; }

    public void reset(Element newXmlNode) throws IOException {
        this.document = newXmlNode.getDocument();
        this.value = newXmlNode;
        this.textValue = XmlUtils.toPrettyXml(newXmlNode);
    }

    public Document getDocument() { return document; }

    @Override
    public String toString() { return getName() + "(" + lineSeparator() + getTextValue() + lineSeparator() + ")"; }

    @Override
    Transformer getTransformer() { return transformer; }

    @Override
    XmlDataType snapshot() {
        XmlDataType snapshot = new XmlDataType();
        snapshot.transformer = transformer;
        snapshot.value = value;
        snapshot.textValue = textValue;
        snapshot.document = document;
        return snapshot;
    }

    @Override
    protected void init() throws TypeConversionException { parse(); }

    protected void parse() throws TypeConversionException {
        try {
            document = XmlUtils.parse(textValue);
            if (document == null) {
                throw new TypeConversionException(getName(), getTextValue(), "Invalid XML: " + textValue);
            }

            value = document.getRootElement();
        } catch (JDOMException | IOException e) {
            ConsoleUtils.error(getName(), e.getMessage());
            throw new TypeConversionException(getName(), getTextValue(), "Error when converting " + textValue, e);
        }
    }
}
