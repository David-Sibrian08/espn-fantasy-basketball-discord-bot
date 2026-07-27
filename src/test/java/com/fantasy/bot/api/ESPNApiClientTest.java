package com.fantasy.bot.api;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ESPNApiClientTest {

    private MockWebServer readonlyServer;
    private MockWebServer primaryServer;

    private ESPNApiClient client(long leagueCacheTtlMillis) throws IOException {
        readonlyServer = new MockWebServer();
        primaryServer = new MockWebServer();
        readonlyServer.start();
        primaryServer.start();

        return new ESPNApiClient(new OkHttpClient(), "58113", "2026", null, null,
                hostUrl(readonlyServer), hostUrl(primaryServer),
                leagueCacheTtlMillis, leagueCacheTtlMillis, leagueCacheTtlMillis);
    }

    private static String hostUrl(MockWebServer server) {
        return "http://localhost:" + server.getPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (readonlyServer != null) readonlyServer.shutdown();
        if (primaryServer != null) primaryServer.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json");
    }

    @Test
    void succeedsOnFirstTry() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(json("{\"id\": 1}"));

        assertEquals(1, client.getLeagueData().get("id").getAsInt());
        assertEquals(1, readonlyServer.getRequestCount());
        assertEquals(0, primaryServer.getRequestCount());
    }

    @Test
    void transientFailureOnReadonlyHostRetriesSameHostBeforeFallingBack() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        readonlyServer.enqueue(json("{\"id\": 2}"));

        assertEquals(2, client.getLeagueData().get("id").getAsInt());
        assertEquals(2, readonlyServer.getRequestCount());
        assertEquals(0, primaryServer.getRequestCount());
    }

    @Test
    void readonlyHostExhaustedFallsBackToPrimaryHost() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        primaryServer.enqueue(json("{\"id\": 3}"));

        assertEquals(3, client.getLeagueData().get("id").getAsInt());
        assertEquals(2, readonlyServer.getRequestCount());
        assertEquals(1, primaryServer.getRequestCount());
    }

    @Test
    void nonTransientFailureFallsBackImmediatelyWithoutRetrying() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(new MockResponse().setResponseCode(404));
        primaryServer.enqueue(json("{\"id\": 4}"));

        assertEquals(4, client.getLeagueData().get("id").getAsInt());
        assertEquals(1, readonlyServer.getRequestCount(), "404 is a config problem, not worth retrying");
        assertEquals(1, primaryServer.getRequestCount());
    }

    @Test
    void bothHostsFailingWithNoPriorCacheThrows() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        primaryServer.enqueue(new MockResponse().setResponseCode(503));
        primaryServer.enqueue(new MockResponse().setResponseCode(503));

        assertThrows(IOException.class, client::getLeagueData);
    }

    @Test
    void servesStaleCacheWhenFetchFailsAfterTtlExpires() throws Exception {
        ESPNApiClient client = client(50); // very short TTL so it expires quickly
        readonlyServer.enqueue(json("{\"id\": 5}"));

        assertEquals(5, client.getLeagueData().get("id").getAsInt());

        Thread.sleep(100); // let the cache entry go stale

        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        readonlyServer.enqueue(new MockResponse().setResponseCode(503));
        primaryServer.enqueue(new MockResponse().setResponseCode(503));
        primaryServer.enqueue(new MockResponse().setResponseCode(503));

        // Fresh fetch fails entirely, but the stale cached value should be served instead of throwing.
        assertEquals(5, client.getLeagueData().get("id").getAsInt());
    }

    @Test
    void redirectIndicatesPrivateLeagueAndFallsBackWithoutRetrying() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(new MockResponse().setResponseCode(302));
        primaryServer.enqueue(json("{\"id\": 6}"));

        assertEquals(6, client.getLeagueData().get("id").getAsInt());
        assertEquals(1, readonlyServer.getRequestCount());
    }

    @Test
    void htmlResponseIsTreatedAsNonTransientFailure() throws IOException {
        ESPNApiClient client = client(30_000);
        readonlyServer.enqueue(new MockResponse().setResponseCode(200).setBody("<html>nope</html>"));
        primaryServer.enqueue(json("{\"id\": 7}"));

        assertEquals(7, client.getLeagueData().get("id").getAsInt());
        assertEquals(1, readonlyServer.getRequestCount());
    }
}
