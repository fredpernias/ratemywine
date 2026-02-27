package com.ratemywine;

import com.ratemywine.model.Millenia;
import com.ratemywine.repository.MilleniaRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MilleniaScrapingServiceTest {

    @Autowired
    private MilleniaScrapingService scrapingService;

    @Autowired
    private MilleniaRepository milleniaRepository;

    private HttpServer server;
    private String startUrl;

    @BeforeEach
    void setUp() throws IOException {
        milleniaRepository.deleteAll();
        server = HttpServer.create(new InetSocketAddress(0), 0);

        server.createContext("/page-1", exchange -> {
            String body = """
                    <html><head><title>Vin Test 1</title></head>
                    <body>
                      <h1>Vin Test 1</h1>
                      <p>Wine Spectator 95/100</p>
                      <a href=\"/page-2\">Page suivante</a>
                    </body></html>
                    """;
            respond(exchange.getResponseBody(), exchange, body);
        });

        server.createContext("/page-2", exchange -> {
            String body = """
                    <html><head><title>Vin Test 2</title></head>
                    <body>
                      <h1>Vin Test 2</h1>
                      <p>James Suckling 94/100</p>
                    </body></html>
                    """;
            respond(exchange.getResponseBody(), exchange, body);
        });

        server.start();
        startUrl = "http://localhost:" + server.getAddress().getPort() + "/page-1";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void scrapeDeuxPremieresPagesEtSauvegardeUniquementSiDifferent() throws InterruptedException {
        int changedFirstRun = scrapingService.scrapeAndSync(startUrl, 2);

        Assertions.assertEquals(2, changedFirstRun);
        Assertions.assertEquals(2, milleniaRepository.count());

        Millenia first = milleniaRepository.findByPageUrlAndSourceKey(startUrl, "wine_spectator").orElseThrow();
        OffsetDateTime firstScrapedAt = first.getScrapedAt();

        int changedSecondRun = scrapingService.scrapeAndSync(startUrl, 2);

        Assertions.assertEquals(0, changedSecondRun);
        Assertions.assertEquals(2, milleniaRepository.count());

        Millenia reloaded = milleniaRepository.findByPageUrlAndSourceKey(startUrl, "wine_spectator").orElseThrow();
        Assertions.assertEquals(firstScrapedAt, reloaded.getScrapedAt());
    }

    private void respond(OutputStream responseBody, com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        responseBody.write(payload);
        responseBody.close();
    }
}
