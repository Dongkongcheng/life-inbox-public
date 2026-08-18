package com.lifeinbox.server.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlMetadataServiceTests {

    private static final URI PUBLIC_URI = URI.create("http://93.184.216.34/article");

    @Test
    void extractsTitleFromHtmlResponse() throws Exception {
        HttpURLConnection connection = htmlConnection("<html><head><title> Example Article </title></head></html>");
        UrlMetadataService.ConnectionFactory connectionFactory = mock(UrlMetadataService.ConnectionFactory.class);
        when(connectionFactory.open(PUBLIC_URI)).thenReturn(connection);
        UrlMetadataService service = new UrlMetadataService(connectionFactory);

        String title = service.resolveTitle(PUBLIC_URI.toString());

        assertEquals("Example Article", title);
        verify(connection).setInstanceFollowRedirects(false);
        verify(connection).setConnectTimeout(3_000);
        verify(connection).setReadTimeout(5_000);
    }

    @Test
    void skipsNonHtmlResponseAndUsesHostFallback() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getContentType()).thenReturn("application/pdf");
        UrlMetadataService.ConnectionFactory connectionFactory = mock(UrlMetadataService.ConnectionFactory.class);
        when(connectionFactory.open(PUBLIC_URI)).thenReturn(connection);
        UrlMetadataService service = new UrlMetadataService(connectionFactory);

        String title = service.resolveTitle(PUBLIC_URI.toString());

        assertEquals("93.184.216.34", title);
        verify(connection, never()).getInputStream();
    }

    @Test
    void blocksLocalhostWithoutOpeningConnection() throws Exception {
        UrlMetadataService.ConnectionFactory connectionFactory = mock(UrlMetadataService.ConnectionFactory.class);
        UrlMetadataService service = new UrlMetadataService(connectionFactory);

        String title = service.resolveTitle("http://localhost/private");

        assertEquals("localhost", title);
        verify(connectionFactory, never()).open(URI.create("http://localhost/private"));
    }

    @Test
    void blocksPrivateIpWithoutOpeningConnection() throws Exception {
        UrlMetadataService.ConnectionFactory connectionFactory = mock(UrlMetadataService.ConnectionFactory.class);
        UrlMetadataService service = new UrlMetadataService(connectionFactory);

        String title = service.resolveTitle("http://192.168.1.10/private");

        assertEquals("192.168.1.10", title);
        verify(connectionFactory, never()).open(URI.create("http://192.168.1.10/private"));
    }

    @Test
    void validatesRedirectTargetBeforeFollowingIt() throws Exception {
        HttpURLConnection redirectConnection = mock(HttpURLConnection.class);
        when(redirectConnection.getResponseCode()).thenReturn(302);
        when(redirectConnection.getHeaderField("Location")).thenReturn("http://127.0.0.1/private");
        UrlMetadataService.ConnectionFactory connectionFactory = mock(UrlMetadataService.ConnectionFactory.class);
        when(connectionFactory.open(PUBLIC_URI)).thenReturn(redirectConnection);
        UrlMetadataService service = new UrlMetadataService(connectionFactory);

        String title = service.resolveTitle(PUBLIC_URI.toString());

        assertEquals("93.184.216.34", title);
        verify(connectionFactory).open(PUBLIC_URI);
        verify(connectionFactory, never()).open(URI.create("http://127.0.0.1/private"));
    }

    private HttpURLConnection htmlConnection(String html) throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getContentType()).thenReturn("text/html; charset=UTF-8");
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(
                html.getBytes(StandardCharsets.UTF_8)
        ));
        return connection;
    }
}
