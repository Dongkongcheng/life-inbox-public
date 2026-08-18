package com.lifeinbox.server.service;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLConnection;
import java.util.Locale;

@Service
public class UrlMetadataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UrlMetadataService.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final String USER_AGENT = "LifeInbox/0.1 URL metadata fetcher";

    private final ConnectionFactory connectionFactory;

    public UrlMetadataService() {
        this(uri -> {
            URLConnection connection = uri.toURL().openConnection();
            if (!(connection instanceof HttpURLConnection httpConnection)) {
                throw new IOException("Only HTTP connections are supported");
            }
            return httpConnection;
        });
    }

    UrlMetadataService(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public String resolveTitle(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException exception) {
            return truncate(sourceUrl);
        }

        String fallbackTitle = fallbackTitle(uri, sourceUrl);
        try {
            String fetchedTitle = fetchTitle(uri);
            return fetchedTitle == null ? fallbackTitle : truncate(fetchedTitle);
        } catch (Exception exception) {
            LOGGER.debug("Unable to fetch URL title for {}", sourceUrl, exception);
            return fallbackTitle;
        }
    }

    private String fetchTitle(URI initialUri) throws IOException {
        URI currentUri = initialUri;

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            ensureSafePublicUri(currentUri);
            HttpURLConnection connection = openConnection(currentUri);
            int statusCode = connection.getResponseCode();

            if (isRedirect(statusCode)) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank() || redirectCount == MAX_REDIRECTS) {
                    return null;
                }
                currentUri = currentUri.resolve(location);
                continue;
            }

            if (statusCode < 200 || statusCode >= 300 || !isHtml(connection.getContentType())) {
                connection.disconnect();
                return null;
            }

            try (InputStream inputStream = connection.getInputStream()) {
                byte[] body = inputStream.readNBytes(MAX_RESPONSE_BYTES);
                String title = Jsoup.parse(
                        new ByteArrayInputStream(body),
                        null,
                        currentUri.toString()
                ).title().trim();
                return title.isBlank() ? null : title;
            } finally {
                connection.disconnect();
            }
        }

        return null;
    }

    private HttpURLConnection openConnection(URI uri) throws IOException {
        HttpURLConnection connection = connectionFactory.open(uri);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private void ensureSafePublicUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IOException("Only HTTP and HTTPS URLs with a host are allowed");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")) {
            throw new IOException("Local hosts are not allowed");
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("URL host did not resolve");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IOException("Private or local network addresses are not allowed");
            }
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 100 && second >= 64 && second <= 127;
        }

        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc;
        }

        return false;
    }

    private boolean isHtml(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "text/html".equals(mediaType) || "application/xhtml+xml".equals(mediaType);
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MULT_CHOICE
                || statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private String fallbackTitle(URI uri, String sourceUrl) {
        String host = uri.getHost();
        return truncate(host == null || host.isBlank() ? sourceUrl : host);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_TITLE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TITLE_LENGTH);
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }
}
