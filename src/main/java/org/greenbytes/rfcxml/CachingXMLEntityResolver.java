/**
 *  CachingXMLEntityResolver for use with xml2rfc related tools
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class CachingXMLEntityResolver implements EntityResolver {

    private static String FOLDER = ".cachingXMLReferenceResolver";

    private static Charset UTF8 = Charset.forName("UTF-8");

    private EntityResolver resolver;

    public CachingXMLEntityResolver(EntityResolver entityResolver) {
        this.resolver = entityResolver != null ? entityResolver : new DefaultHandler();
    }

    private InputSource resolveEntity(String publicId, String systemId, boolean useOld) throws SAXException, IOException {
        if (systemId == null || systemId.isEmpty()) {
            return resolver.resolveEntity(publicId, systemId);
        } else {
            URI parsed = null;
            try {
                parsed = new URI(systemId);
                if (parsed.getScheme() == null || parsed.getScheme().toLowerCase(Locale.ENGLISH).equals("file")) {
                    return null;
                }
            } catch (URISyntaxException e) {
                System.err.println("RESOLVER: not a URI: " + systemId);
                return null;
            }

            if (!systemId.contains("reference.")) {
                return null;
            }

            File file = new File(getFileForUri(systemId));
            if (file.exists()) {
                long cutoff = System.currentTimeMillis() - DAY * 1000;
                long filedate = file.lastModified();
                if (filedate > cutoff || useOld) {
                    if (filedate <= cutoff) {
                        System.err.println(
                                "RESOLVER: using old entry (" + (age(System.currentTimeMillis() - filedate)) + ") for " + systemId);
                    }
                    InputSource is = new InputSource(new FileInputStream(file));
                    is.setPublicId(publicId);
                    is.setSystemId(systemId);
                    return is;
                }
            }

            InputStream is = null;
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            try {
                is = getContent(systemId, 5);
                byte[] bytes = new byte[16384];
                int length = 0;
                while ((length = is.read(bytes)) != -1) {
                    os.write(bytes, 0, length);
                }
                File folder = new File(FOLDER);
                if (!folder.exists()) {
                    folder.mkdirs();
                }
                File tfile = new File(folder, UUID.randomUUID().toString());
                FileOutputStream fos = new FileOutputStream(tfile);
                fos.write(os.toByteArray());
                fos.close();
                // try rename
                boolean deleted = false;
                long filedate = file.lastModified();
                if (file.exists()) {
                    deleted = file.delete();
                }
                if (tfile.renameTo(file)) {
                    if (deleted) {
                        System.err.println("RESOLVER: replaced " + age(System.currentTimeMillis() - filedate)
                                + " old entry for " + systemId);
                    } else {
                        System.err.println("RESOLVER: created entry for " + systemId);
                    }
                    return resolveEntity(publicId, systemId, true);
                } else {
                    tfile.deleteOnExit();
                    return null;
                }
            } catch (IOException ex) {
                System.err.println("RESOLVER: error for " + systemId + " - " + ex.getMessage());
                if (!useOld && file.exists()) {
                    return resolveEntity(publicId, systemId, true);
                }
            } finally {
                if (is != null) {
                    is.close();
                }
            }
        }
        return null;
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
        return resolveEntity(publicId, systemId, false);
    }
    
    // Utilities

    private InputStream getContent(String uri, int redirects) throws IOException {
        URLConnection conn = new URL(uri).openConnection();
        if (conn instanceof HttpURLConnection) {
            HttpURLConnection hc = (HttpURLConnection) conn;
            hc.setRequestProperty("User-Agent", "Julian's CachingXMLReader");
            hc.setConnectTimeout(2000);
            int status = hc.getResponseCode();
            if (status >= 200 && status <= 299) {
                return hc.getInputStream();
            } else if (status >= 300 && status <= 399) {
                if (redirects < 1) {
                    throw new IOException("Too many redirects");
                } else {
                    String loc = hc.getHeaderField("location");
                    if (loc == null) {
                        System.err.println(
                                "RESOLVER: GET on uri " + uri + " redirects with status code " + status + ", no location header field");
                        throw new IOException("redirected with " + status + ", but no location");
                    } else {
                        try {
                            URI red = new URI(loc);
                            URI base = new URI(uri);
                            URI fin = base.resolve(red);
                            System.err.println(
                                "RESOLVER: GET on uri " + uri + " redirects with status code " + status + ", retrying with " + fin);
                            return getContent(fin.toString(), redirects - 1);
                        } catch (URISyntaxException ex) {
                            throw new IOException(ex);
                        }

                    }
                }
            } else {
                System.err.println(
                        "RESOLVER: GET on uri " + uri + " failed with status code " + status);
                throw new IOException("Status: " + status);
            }
        } else {
            return conn.getInputStream();
        }
    }

    private static String getFileForUri(String uri) {
        String hash = toSHA1(uri.toString());
        return FOLDER + "/" + hash + ".xml";
    }

    private static String toSHA1(String convertme) {
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("SHA-1");
            return toHex(md.digest(convertme.getBytes(UTF8)));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }

    private static long MINUTE = 60;
    private static long HOUR = 60 * MINUTE;
    private static long DAY = 24 * HOUR;

    private static String age(long ms) {
        long sec = ms / 1000;
        if (sec < 2 * MINUTE) {
            return sec + " seconds";
        } else if (sec < 2 * HOUR) {
            return (sec / MINUTE) + " minutes";
        } else if (sec < 2 * DAY) {
            return (sec / HOUR) + " hours";
        } else {
            return (sec / DAY) + " days";
        }
    }
}
