package run.halo.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 服务层端到端测试：模拟设置 + 本地 HTTP 假 Wavelog 服务，
 * 验证「读取配置 → 请求 Wavelog → 构建载荷」完整链路。
 */
class QsoStatsServiceTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/index.php/api/v2/statistic", exchange -> {
            byte[] body = """
                {"data":{"qso":{"total":28,"activity":{"today":2,"month":5,"year":7},
                  "breakdown":{"by_band":[{"band":"20m","count":12},{"band":"40m","count":8}],
                               "by_mode":[{"mode":"FT8","count":15},{"mode":"CW","count":7}]},
                  "dxcc":{"worked":15,"confirmed":9,"available":340}}},"meta":{}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/index.php/api/v2/qso", exchange -> {
            byte[] body = """
                {"data":[{"id":4886,"station_id":1,"call":"N9EAT","band":"20m","mode":"SSB",
                  "qso_date":"2026-06-16 17:06:00","gridsquare":"EN42"}],"meta":{}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ReactiveSettingFetcher mockFetcher() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("api"), eq(WavelogSettings.Api.class)))
            .thenReturn(Mono.just(new WavelogSettings.Api(
                "http://localhost:" + port, "wl2_test", 60, 5, "我的通联")));
        when(fetcher.fetch(eq("stats"), eq(WavelogSettings.Stats.class)))
            .thenReturn(Mono.just(new WavelogSettings.Stats(List.of(
                new WavelogSettings.Item("total_qsos", "通联总数", true, null),
                new WavelogSettings.Item("bands", "波段分布", true, 2),
                new WavelogSettings.Item("recent", "最近通联", true, 5),
                new WavelogSettings.Item("modes", "模式分布", false, null)
            ))));
        when(fetcher.fetch(eq("display"), eq(WavelogSettings.Display.class)))
            .thenReturn(Mono.just(new WavelogSettings.Display(
                true, 50, true, "我的通联统计", true, "暂不可用", "modern", "auto")));
        return fetcher;
    }

    @Test
    void buildsPayloadEndToEnd() {
        QsoStatsService service = new QsoStatsService(mockFetcher(), new WavelogClient());
        StatsPayload.Payload payload = service.buildPayload().block();

        assertNotNull(payload);
        assertEquals(null, payload.error());
        assertEquals("我的通联统计", payload.sectionTitle());
        assertTrue(payload.showSectionTitle());
        assertNotNull(payload.updatedAt());

        // 启用的项目：total_qsos、bands、recent；禁用与未配置的不出现
        assertEquals(3, payload.sections().size());
        assertEquals("total_qsos", payload.sections().get(0).key());
        assertEquals("bands", payload.sections().get(1).key());
        assertEquals("recent", payload.sections().get(2).key());

        StatsPayload.NumberValue total =
            (StatsPayload.NumberValue) payload.sections().get(0).value();
        assertEquals(28L, total.value());

        @SuppressWarnings("unchecked")
        List<StatsPayload.DistributionRow> bands =
            (List<StatsPayload.DistributionRow>) payload.sections().get(1).value();
        assertEquals(2, bands.size());
        assertEquals("20m", bands.get(0).label());

        @SuppressWarnings("unchecked")
        List<StatsPayload.RecentRow> recent =
            (List<StatsPayload.RecentRow>) payload.sections().get(2).value();
        assertEquals(1, recent.size());
        assertEquals("N9EAT", recent.get(0).call());
        assertEquals("2026-06-16 17:06", recent.get(0).time());
    }

    @Test
    void unconfiguredApiReturnsFriendlyError() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("api"), eq(WavelogSettings.Api.class)))
            .thenReturn(Mono.just(new WavelogSettings.Api("", "", null, null, null)));
        when(fetcher.fetch(any(), any())).thenReturn(Mono.empty());

        QsoStatsService service = new QsoStatsService(fetcher, new WavelogClient());
        StatsPayload.Payload payload = service.buildPayload().block();

        assertNotNull(payload.error());
        assertTrue(payload.error().contains("未配置"));
        assertTrue(payload.sections().isEmpty());
        // 展示设置使用默认值
        assertEquals("统计数据暂不可用，请稍后再试", payload.fallbackText());
    }

    @Test
    void wavelogHttpErrorProducesFriendlyMessage() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("api"), eq(WavelogSettings.Api.class)))
            .thenReturn(Mono.just(new WavelogSettings.Api(
                "http://localhost:" + port + "/wrong", "wl2_test", 60, 5, null)));
        when(fetcher.fetch(eq("stats"), eq(WavelogSettings.Stats.class)))
            .thenReturn(Mono.just(new WavelogSettings.Stats(List.of(
                new WavelogSettings.Item("total_qsos", "通联总数", true, null)))));
        when(fetcher.fetch(eq("display"), eq(WavelogSettings.Display.class)))
            .thenReturn(Mono.empty());

        QsoStatsService service = new QsoStatsService(fetcher, new WavelogClient());
        StatsPayload.Payload payload = service.buildPayload().block();

        // /wrong 路径下 404（Handler not found 由连接器返回 404 或连接错误），无论哪种都应是错误载荷
        assertNotNull(payload);
        assertNotNull(payload.error());
        assertTrue(payload.sections().isEmpty());
    }

    @Test
    void settingsFetchFailureFallsBackToDefaults() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(any(), any())).thenReturn(Mono.error(new RuntimeException("boom")));

        QsoStatsService service = new QsoStatsService(fetcher, new WavelogClient());
        StatsPayload.Payload payload = service.buildPayload().block();

        // 设置读取失败 → 视为未配置，返回友好错误而非 500
        assertNotNull(payload);
        assertTrue(payload.error().contains("未配置"));
    }

    // ---------- 呼号查询与 OQRS ----------

    @Test
    void searchQsosByCallsignReturnsRows() {
        QsoStatsService service = new QsoStatsService(mockFetcher(), new WavelogClient());
        StatsPayload.SearchPayload payload = service.searchQsos("n9eat").block();

        assertNotNull(payload);
        assertEquals(null, payload.error());
        assertEquals("N9EAT", payload.callsign());
        assertEquals(1, payload.qsos().size());

        StatsPayload.QsoRow row = payload.qsos().get(0);
        assertEquals("2026-06-16", row.date());
        assertEquals("17:06", row.time());
        assertEquals("20m", row.band());
        assertEquals("SSB", row.mode());
        assertEquals(1L, row.stationId());
    }

    @Test
    void blankCallsignReturnsFriendlyError() {
        QsoStatsService service = new QsoStatsService(mockFetcher(), new WavelogClient());
        StatsPayload.SearchPayload payload = service.searchQsos("   ").block();

        assertNotNull(payload);
        assertNotNull(payload.error());
        assertTrue(payload.error().contains("呼号"));
        assertTrue(payload.qsos().isEmpty());
    }

    @Test
    void unconfiguredApiSearchReturnsFriendlyError() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("api"), eq(WavelogSettings.Api.class)))
            .thenReturn(Mono.just(new WavelogSettings.Api("", "", null, null, null)));
        when(fetcher.fetch(any(), any())).thenReturn(Mono.empty());

        QsoStatsService service = new QsoStatsService(fetcher, new WavelogClient());
        StatsPayload.SearchPayload payload = service.searchQsos("BG8LNG").block();

        assertNotNull(payload);
        assertNotNull(payload.error());
        assertTrue(payload.error().contains("未配置"));
    }

    @Test
    void submitsOqrsRequestEndToEnd() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/index.php/oqrs/save_oqrs_request_grouped", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            capturedBody.set(body);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(new byte[0]);
            }
        });

        QsoStatsService service = new QsoStatsService(mockFetcher(), new WavelogClient());
        StatsPayload.OqrsResult result = service.submitOqrs(new StatsPayload.OqrsSubmitRequest(
            "bg8lng", "me@example.com", "你好，申请卡片", "B",
            List.of(new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1)))).block();

        assertNotNull(result);
        assertTrue(result.success());

        String body = capturedBody.get();
        assertNotNull(body);
        assertTrue(body.contains("callsign=BG8LNG"), body);
        assertTrue(body.contains("email=me%40example.com"), body);
        assertTrue(body.contains("qslroute=B"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B0%5D=2026-06-16"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B1%5D=17%3A06"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B2%5D=20m"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B3%5D=SSB"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B4%5D=1"), body);
    }

    @Test
    void oqrsWithoutEmailReturnsError() {
        QsoStatsService service = new QsoStatsService(mockFetcher(), new WavelogClient());
        StatsPayload.OqrsResult result = service.submitOqrs(new StatsPayload.OqrsSubmitRequest(
            "BG8LNG", "  ", "", "B",
            List.of(new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1)))).block();

        assertNotNull(result);
        assertTrue(!result.success());
        assertTrue(result.message().contains("邮箱"));
    }

    @Test
    void oqrsWithoutQsosReturnsError() {
        QsoStatsService service = new QsoStatsService(mockFetcher(), new WavelogClient());
        StatsPayload.OqrsResult result = service.submitOqrs(new StatsPayload.OqrsSubmitRequest(
            "BG8LNG", "me@example.com", "", "B", List.of())).block();

        assertNotNull(result);
        assertTrue(!result.success());
        assertTrue(result.message().contains("通联"));
    }
}
