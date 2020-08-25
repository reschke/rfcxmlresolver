/**
 *  LineNumberingContentHandler for use with xml2rfc related tools
 * 
 *  Copyright (c) 2020, Julian Reschke (julian.reschke@greenbytes.de)
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *  * Neither the name of Julian Reschke nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 *  AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 *  IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 *  ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 *  LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 *  INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 *  CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *  POSSIBILITY OF SUCH DAMAGE.
 */
package org.greenbytes.rfcxml;

import java.util.Stack;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

public class LineNumberingContentHandler implements ContentHandler {

    private ContentHandler delegate;
    private Locator locator;
    private Stack<String> ids = new Stack<>();
    private Stack<Integer> linenos = new Stack<>();

    public LineNumberingContentHandler(ContentHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        delegate.characters(ch, start, length);
    }

    @Override
    public void endDocument() throws SAXException {
        delegate.endDocument();
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (locator != null) {
//            if (linenos.empty() || !linenos.peek().equals(locator.getLineNumber())) {
//                delegate.processingInstruction("lineno", "" + locator.getLineNumber());
//            }
            ids.pop();
            linenos.pop();
        }
        delegate.endElement(uri, localName, qName);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        delegate.endPrefixMapping(prefix);
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
        delegate.ignorableWhitespace(ch, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        delegate.processingInstruction(target, data);
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
        delegate.setDocumentLocator(locator);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        delegate.skippedEntity(name);
    }

    @Override
    public void startDocument() throws SAXException {
        delegate.startDocument();
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        delegate.startElement(uri, localName, qName, atts);
        if (locator != null) {
            String sid = null;
            if (ids.empty() || !ids.peek().equals(locator.getSystemId())) {
                sid = locator.getSystemId();
            }
            ids.push(locator.getSystemId());
            linenos.push(locator.getLineNumber());
            delegate.processingInstruction("rfc-ext",
                    (sid == null ? "" : "system-id='" + sid + "' ") + "line-no='" + locator.getLineNumber() + "'");
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        delegate.startPrefixMapping(prefix, uri);
    }
}
