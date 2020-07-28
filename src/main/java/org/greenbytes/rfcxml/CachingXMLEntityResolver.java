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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class CachingXMLEntityResolver implements EntityResolver {

    private static String FOLDER = ".cachingXMLReferenceResolver";
    private static String PAYLOAD = "payload";
    private static String STATUS = "status";
    private static String ETAG = "field.etag";

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
                    try (ZipFile zip = new ZipFile(file)) {
                        String status = getZipEntryContentAsString(zip, new ZipEntry(STATUS));
                        if ("200".equals(status)) {
                            if (filedate <= cutoff) {
                                System.err.println(
                                        "RESOLVER: using old entry (" + (age(System.currentTimeMillis() - filedate)) + ") for " + systemId);
                            }
                            try (InputStream is = zip.getInputStream(new ZipEntry(PAYLOAD))) {
                                InputSource source = new InputSource(new ByteArrayInputStream(getBytes(is)));
                                source.setPublicId(publicId);
                                source.setSystemId(systemId);
                                return source;
                            }
                        }
                    }
                }
            }

            URLConnection conn = get(systemId, 5);
            if (!(conn instanceof HttpURLConnection)) {
                // what?
                return null;
            } else {
                boolean success = dumpHttpResponseToFile(systemId, file, (HttpURLConnection) conn);
                if (success) {
                    // retry with populated cache
                    return resolveEntity(publicId, systemId, true);
                } else {
                    // handle back
                    return null;
                }
            }
        }
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
        return resolveEntity(publicId, systemId, false);
    }

    // Utilities

    private URLConnection get(String uri, int redirects) throws IOException {
        URLConnection conn = new URL(uri).openConnection();
        if (conn instanceof HttpURLConnection) {
            HttpURLConnection hc = (HttpURLConnection) conn;
            hc.setRequestProperty("User-Agent", "Julian's CachingXMLReader");
            hc.setConnectTimeout(2000);
            int status = hc.getResponseCode();
            if (status >= 200 && status <= 299) {
                return hc;
            } else if (status >= 300 && status <= 399) {
                if (redirects < 1) {
                    throw new IOException("Too many redirects");
                } else {
                    String loc = hc.getHeaderField("location");
                    if (loc == null) {
                        System.err.println("RESOLVER: GET on uri " + uri + " redirects with status code " + status
                                + ", no location header field");
                        throw new IOException("redirected with " + status + ", but no location");
                    } else {
                        try {
                            URI red = new URI(loc);
                            URI base = new URI(uri);
                            URI fin = base.resolve(red);
                            System.err.println("RESOLVER: GET on uri " + uri + " redirects with status code " + status
                                    + ", retrying with " + fin);
                            return get(fin.toString(), redirects - 1);
                        } catch (URISyntaxException ex) {
                            throw new IOException(ex);
                        }

                    }
                }
            } else {
                System.err.println("RESOLVER: GET on uri " + uri + " failed with status code " + status);
                throw new IOException("Status: " + status);
            }
        } else {
            return conn;
        }
    }

    private static String getFileForUri(String uri) {
        String hash = toSHA1(uri.toString());
        return FOLDER + "/" + hash + ".zip";
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

    private static byte[] getBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    private static boolean dumpHttpResponseToFile(String systemId, File destFile, HttpURLConnection conn) {
        File folder = new File(FOLDER);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File tfile = new File(folder, UUID.randomUUID().toString());
        try (InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(tfile);
                ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry(STATUS));
            zos.write(Integer.toString(conn.getResponseCode()).getBytes("UTF-8"));
            zos.flush();
            zos.closeEntry();

            String etag = conn.getHeaderField("etag");
            if (etag != null) {
                zos.putNextEntry(new ZipEntry(ETAG));
                zos.write(etag.trim().getBytes("UTF-8"));
                zos.flush();
                zos.closeEntry();
            }

            byte[] bytes = getBytes(is);
            zos.putNextEntry(new ZipEntry(PAYLOAD));
            zos.write(bytes);
            zos.flush();
            zos.closeEntry();
            zos.close();
            // try rename
            boolean deleted = false;
            long filedate = destFile.lastModified();
            if (destFile.exists()) {
                deleted = destFile.delete();
            }
            if (tfile.renameTo(destFile)) {
                if (deleted) {
                    System.err.println(
                            "RESOLVER: replaced " + age(System.currentTimeMillis() - filedate) + " old entry for " + systemId);
                } else {
                    System.err.println("RESOLVER: created entry for " + systemId);
                }
                return true;
            } else {
                tfile.deleteOnExit();
            }
        } catch (IOException ex) {
            System.err.println("RESOLVER: error for " + systemId + " - " + ex.getMessage());
        }
        return false;
    }

    private static String getZipEntryContentAsString(ZipFile zf, ZipEntry entry) {
        try (InputStream is = zf.getInputStream(entry)) {
            return new String(getBytes(is), "UTF-8");
        } catch (Exception expected) {
        }
        return null;
    }
}
