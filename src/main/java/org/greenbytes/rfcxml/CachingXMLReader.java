/**
 *  CachingXMLReader for use with xml2rfc related tools
 * 
 *  Copyright (c) 2016-2020, Julian Reschke (julian.reschke@greenbytes.de)
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

import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

public class CachingXMLReader implements XMLReader {

    private final XMLReader reader;

    public CachingXMLReader() throws ParserConfigurationException, SAXException {
        SAXParserFactory sfactory = SAXParserFactory.newInstance();
        SAXParser parser = sfactory.newSAXParser();
        reader = parser.getXMLReader();
        reader.setEntityResolver(new CachingXMLEntityResolver(reader.getEntityResolver()));
    }

    // XMLReader

    @Override
    public ContentHandler getContentHandler() {
        return reader.getContentHandler();
    }

    @Override
    public DTDHandler getDTDHandler() {
        return reader.getDTDHandler();
    }

    @Override
    public EntityResolver getEntityResolver() {
        return reader.getEntityResolver();
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return reader.getErrorHandler();
    }

    @Override
    public boolean getFeature(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        return reader.getFeature(name);
    }

    @Override
    public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
        return reader.getProperty(name);
    }

    @Override
    public void parse(InputSource source) throws IOException, SAXException {
        InputSource ns = source;
        if (ns.getByteStream() == null && ns.getCharacterStream() == null) {
            InputSource t = getEntityResolver().resolveEntity(ns.getPublicId(), ns.getSystemId());
            if (t != null) {
                 ns = t;
            }
        }
        reader.parse(ns);
    }

    @Override
    public void parse(String string) throws IOException, SAXException {
        reader.parse(string);
    }

    @Override
    public void setContentHandler(ContentHandler handler) {
        reader.setContentHandler(handler);
    }

    @Override
    public void setDTDHandler(DTDHandler handler) {
        reader.setDTDHandler(handler);
    }

    @Override
    public void setEntityResolver(EntityResolver resolver) {
        reader.setEntityResolver(new CachingXMLEntityResolver(resolver));
    }

    @Override
    public void setErrorHandler(ErrorHandler handler) {
        reader.setErrorHandler(handler);
    }

    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
        reader.setFeature(name, value);
    }

    @Override
    public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
        reader.setProperty(name, value);
    }
}
