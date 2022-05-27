/**
 *  CachingXMLEntityResolver for use with xml2rfc related tools
 * 
 *  Copyright (c) 2016-2022, Julian Reschke (julian.reschke@greenbytes.de)
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
    private static String ETAG = "etag.field";
    private static String LOCATION = "location.field";

    private static Charset UTF8 = Charset.forName("UTF-8");

    private EntityResolver resolver;

    public CachingXMLEntityResolver(EntityResolver entityResolver) {
        this.resolver = entityResolver != null ? entityResolver : new DefaultHandler();
    }

    private InputSource resolveEntity(String publicId, String systemId, boolean allowStale, int redirects)
            throws SAXException, IOException {
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
        String etag = null;
        if (file.exists()) {
            long filedate = file.lastModified();
            try (ZipFile zip = new ZipFile(file)) {
                String status = getZipEntryContentAsString(zip, new ZipEntry(STATUS));
                etag = getZipEntryContentAsString(zip, new ZipEntry(ETAG));
                String location = getZipEntryContentAsString(zip, new ZipEntry(LOCATION));

                if ("200".equals(status)) {
                    long cutoff = System.currentTimeMillis() - DAY * 1000;
                    if (filedate >= cutoff || allowStale) {
                        if (allowStale) {
                            System.err.println("RESOLVER: using old entry (" + (age(System.currentTimeMillis() - filedate))
                                    + ") for " + systemId);
                        }
                        try (InputStream is = zip.getInputStream(new ZipEntry(PAYLOAD))) {
                            InputSource source = new InputSource(new ByteArrayInputStream(getBytes(is)));
                            source.setPublicId(publicId);
                            source.setSystemId(systemId);
                            return source;
                        }
                    }
                } else if (location != null
                        && ("301".equals(status) || "302".equals(status) || "307".equals(status) || "308".equals(status))) {
                    if (redirects <= 0) {
                        System.err.println("RESOLVER: maximum number of redirects reached, giving up...");
                    } else {
                        boolean isPermanent = "301".equals(status) || "308".equals(status);
                        long cutoff = System.currentTimeMillis() - (isPermanent ? WEEK * 4 : DAY) * 1000;
                        if (location != null && (filedate >= cutoff || allowStale)) {
                            if (allowStale) {
                                System.err.println("RESOLVER: using old entry (" + (age(System.currentTimeMillis() - filedate))
                                        + ") for " + systemId);
                            }
                            try {
                                URI red = new URI(location);
                                URI base = new URI(systemId);
                                URI fin = base.resolve(red);
                                System.err.println("RESOLVER: cached response for " + systemId + " redirects with status code "
                                        + status + " to " + fin);
                                return resolveEntity(publicId, fin.toASCIIString(), allowStale, redirects - 1);
                            } catch (URISyntaxException ex) {
                                throw new IOException(ex);
                            }
                        }
                    }
                } else if ("404".equals(status)) {
                    long cutoff = System.currentTimeMillis() - 15 * MINUTE * 1000;
                    if (filedate >= cutoff) {
                        System.err.println("RESOLVER: using old 'not found' entry (" + (age(System.currentTimeMillis() - filedate))
                                + ") for " + systemId);
                        return null;
                    }
                } else {
                    System.err.println("RESOLVER: cached status for " + systemId + " is " + status + " (location: " + location
                            + "); giving up...");
                    return null;
                }
            }
        }

        boolean success = populateCache(systemId, etag);
        return resolveEntity(publicId, systemId, !success, redirects);
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
        if (systemId == null || systemId.isEmpty()) {
            return resolver.resolveEntity(publicId, systemId);
        } else {
            return resolveEntity(publicId, systemId, false, 5);
        }
    }

    // Utilities

    private boolean populateCache(String uri, String etag) throws IOException {
        URLConnection conn = new URL(uri).openConnection();
        if (conn instanceof HttpURLConnection) {
            HttpURLConnection hc = (HttpURLConnection) conn;
            hc.setInstanceFollowRedirects(false);
            hc.setRequestProperty("User-Agent", "Julian's CachingXMLReader");
            if (etag != null) {
                hc.setRequestProperty("If-None-Match", etag);
            }
            hc.setConnectTimeout(2000);
            return dumpHttpResponseToFile(uri, (HttpURLConnection) conn);
        } else {
            System.err.println("RESOLVER: URLConnection for " + uri + " is not an HttpURLConnection");
            return false;
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
    private static long WEEK = 7 * DAY;

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

    private static boolean dumpHttpResponseToFile(String systemId, HttpURLConnection conn) {

        int status = -1;
        try {
            status = conn.getResponseCode();
        } catch (IOException ignored) {
        }

        File folder = new File(FOLDER);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File destFile = new File(getFileForUri(systemId));
        if (destFile.exists()) {
            if (status == 304) {
                long filedate = destFile.lastModified();
                destFile.setLastModified(System.currentTimeMillis());
                System.err.println(
                        "RESOLVER: revalidated " + age(System.currentTimeMillis() - filedate) + " old entry for " + systemId);
                return true;
            }
        }

        File tfile = new File(folder, UUID.randomUUID().toString());
        try (FileOutputStream fos = new FileOutputStream(tfile); ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry(STATUS));
            zos.write(Integer.toString(status).getBytes("UTF-8"));
            zos.flush();
            zos.closeEntry();

            String etag = conn.getHeaderField("etag");
            if (etag != null) {
                zos.putNextEntry(new ZipEntry(ETAG));
                zos.write(etag.trim().getBytes("UTF-8"));
                zos.flush();
                zos.closeEntry();
            }

            String location = conn.getHeaderField("location");
            if (location != null) {
                zos.putNextEntry(new ZipEntry(LOCATION));
                zos.write(location.trim().getBytes("UTF-8"));
                zos.flush();
                zos.closeEntry();
            }

            try (InputStream is = conn.getInputStream()) {
                byte[] bytes = getBytes(is);
                zos.putNextEntry(new ZipEntry(PAYLOAD));
                zos.write(bytes);
                zos.flush();
                zos.closeEntry();
            } catch (IOException ignored) {
            }

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
            System.err.println("RESOLVER: error " + status + " for " + systemId + " - " + ex.getMessage());
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
