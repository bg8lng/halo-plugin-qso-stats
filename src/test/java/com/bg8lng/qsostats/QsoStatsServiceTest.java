package com.bg8lng.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private static QsoStatsService service(ReactiveSettingFetcher fetcher) {
        return new QsoStatsService(fetcher, new WavelogClient(), new PublicApiGuard());
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
                true, 50, true, "我的通联统计", true, "暂不可用", "modern", "auto", true)));
        when(fetcher.fetch(eq("security"), eq(WavelogSettings.Security.class)))
            .thenReturn(Mono.just(new WavelogSettings.Security(null, null, null, null)));
        when(fetcher.fetch(eq("layout"), eq(WavelogSettings.Layout.class)))
            .thenReturn(Mono.empty());
        return fetcher;
    }

    @Test
    void buildsPayloadEndToEnd() {
        QsoStatsService service = service(mockFetcher());
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

        QsoStatsService service = service(fetcher);
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

        QsoStatsService service = service(fetcher);
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

        QsoStatsService service = service(fetcher);
        StatsPayload.Payload payload = service.buildPayload().block();

        // 设置读取失败 → 视为未配置，返回友好错误而非 500
        assertNotNull(payload);
        assertTrue(payload.error().contains("未配置"));
    }

    // ---------- 呼号查询与 OQRS ----------

    @Test
    void searchQsosByCallsignReturnsRows() {
        QsoStatsService service = service(mockFetcher());
        StatsPayload.ApiResponse<StatsPayload.SearchPayload> response =
            service.searchQsos("n9eat", "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(200, response.status());
        StatsPayload.SearchPayload payload = response.body();
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
        QsoStatsService service = service(mockFetcher());
        StatsPayload.ApiResponse<StatsPayload.SearchPayload> response =
            service.searchQsos("   ", "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(400, response.status());
        assertNotNull(response.body().error());
        assertTrue(response.body().error().contains("呼号"));
        assertTrue(response.body().qsos().isEmpty());
    }

    @Test
    void malformedCallsignIsRejectedBeforeReachingWavelog() {
        QsoStatsService service = service(mockFetcher());
        StatsPayload.ApiResponse<StatsPayload.SearchPayload> response =
            service.searchQsos("'; DROP TABLE qsos--", "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(400, response.status());
        assertTrue(response.body().error().contains("格式"));
    }

    /** 审核意见 1：开关关闭后，直接调用公开接口必须被服务端拒绝 */
    @Test
    void searchIsRejectedWhenFeatureDisabled() {
        ReactiveSettingFetcher fetcher = mockFetcher();
        when(fetcher.fetch(eq("display"), eq(WavelogSettings.Display.class)))
            .thenReturn(Mono.just(new WavelogSettings.Display(
                false, 50, true, "我的通联统计", true, "暂不可用", "modern", "auto", true)));

        StatsPayload.ApiResponse<StatsPayload.SearchPayload> response =
            service(fetcher).searchQsos("N9EAT", "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(403, response.status());
        assertTrue(response.body().qsos().isEmpty());
        assertTrue(response.body().error().contains("已关闭"));
    }

    /** 审核意见 1：呼号查询的频率限制 */
    @Test
    void searchIsRateLimitedPerClient() {
        ReactiveSettingFetcher fetcher = mockFetcher();
        when(fetcher.fetch(eq("security"), eq(WavelogSettings.Security.class)))
            .thenReturn(Mono.just(new WavelogSettings.Security(2, 5, 50, 24)));
        QsoStatsService service = service(fetcher);

        assertEquals(200, service.searchQsos("N9EAT", "9.9.9.9").block().status());
        assertEquals(200, service.searchQsos("N9EAT", "9.9.9.9").block().status());
        StatsPayload.ApiResponse<StatsPayload.SearchPayload> third =
            service.searchQsos("N9EAT", "9.9.9.9").block();
        assertEquals(429, third.status());
        // 其他来源不受影响
        assertEquals(200, service.searchQsos("N9EAT", "8.8.8.8").block().status());
    }

    @Test
    void unconfiguredApiSearchReturnsFriendlyError() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("api"), eq(WavelogSettings.Api.class)))
            .thenReturn(Mono.just(new WavelogSettings.Api("", "", null, null, null)));
        when(fetcher.fetch(any(), any())).thenReturn(Mono.empty());

        StatsPayload.ApiResponse<StatsPayload.SearchPayload> response =
            service(fetcher).searchQsos("BG8LNG", "1.2.3.4").block();

        assertNotNull(response);
        assertNotNull(response.body().error());
        assertTrue(response.body().error().contains("未配置"));
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

        QsoStatsService service = service(mockFetcher());
        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service.submitOqrs(oqrs("n9eat", "me@example.com"), "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(200, response.status());
        assertTrue(response.body().success());

        String body = capturedBody.get();
        assertNotNull(body);
        assertTrue(body.contains("callsign=N9EAT"), body);
        assertTrue(body.contains("email=me%40example.com"), body);
        assertTrue(body.contains("qslroute=B"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B0%5D=2026-06-16"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B1%5D=17%3A06"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B2%5D=20m"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B3%5D=SSB"), body);
        assertTrue(body.contains("qsos%5B0%5D%5B4%5D=1"), body);
    }

    /** 与假 Wavelog 日志中唯一一条记录一致的合法申请 */
    private static StatsPayload.OqrsSubmitRequest oqrs(String callsign, String email) {
        return new StatsPayload.OqrsSubmitRequest(callsign, email, "你好，申请卡片", "B",
            List.of(new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1)));
    }

    @Test
    void oqrsWithoutEmailReturnsError() {
        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(mockFetcher()).submitOqrs(new StatsPayload.OqrsSubmitRequest(
                "N9EAT", "  ", "", "B",
                List.of(new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1))),
                "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(400, response.status());
        assertTrue(!response.body().success());
        assertTrue(response.body().message().contains("邮箱"));
    }

    @Test
    void oqrsWithMalformedEmailReturnsError() {
        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(mockFetcher()).submitOqrs(oqrs("N9EAT", "not-an-email"), "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(400, response.status());
        assertTrue(response.body().message().contains("邮箱"));
    }

    @Test
    void oqrsWithoutQsosReturnsError() {
        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(mockFetcher()).submitOqrs(new StatsPayload.OqrsSubmitRequest(
                "N9EAT", "me@example.com", "", "B", List.of()), "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(400, response.status());
        assertTrue(response.body().message().contains("通联"));
    }

    /** 审核意见 1：开关关闭后，OQRS 写接口必须被服务端拒绝 */
    @Test
    void oqrsIsRejectedWhenFeatureDisabled() {
        ReactiveSettingFetcher fetcher = mockFetcher();
        when(fetcher.fetch(eq("display"), eq(WavelogSettings.Display.class)))
            .thenReturn(Mono.just(new WavelogSettings.Display(
                true, 50, true, "我的通联统计", true, "暂不可用", "modern", "auto", false)));

        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(fetcher).submitOqrs(oqrs("N9EAT", "me@example.com"), "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(403, response.status());
        assertTrue(!response.body().success());
    }

    /** 审核意见 1：总开关关闭时 OQRS 一并关闭 */
    @Test
    void oqrsIsRejectedWhenSearchDisabled() {
        ReactiveSettingFetcher fetcher = mockFetcher();
        when(fetcher.fetch(eq("display"), eq(WavelogSettings.Display.class)))
            .thenReturn(Mono.just(new WavelogSettings.Display(
                false, 50, true, "我的通联统计", true, "暂不可用", "modern", "auto", true)));

        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(fetcher).submitOqrs(oqrs("N9EAT", "me@example.com"), "1.2.3.4").block();

        assertEquals(403, response.status());
    }

    /** 审核意见 1：服务端记录校验——伪造的通联记录不得被转发 */
    @Test
    void oqrsRejectsQsosThatDoNotExistInTheLog() throws Exception {
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);
        server.createContext("/index.php/oqrs/save_oqrs_request_grouped", exchange -> {
            forwarded.set(true);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(mockFetcher()).submitOqrs(new StatsPayload.OqrsSubmitRequest(
                "N9EAT", "me@example.com", "", "B",
                List.of(new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1),
                    // 本站日志中不存在的伪造记录
                    new StatsPayload.OqrsQso("1999-01-01", "00:00", "160m", "CW", 42))),
                "1.2.3.4").block();

        assertNotNull(response);
        assertEquals(400, response.status());
        assertTrue(response.body().message().contains("不一致"));
        assertTrue(!forwarded.get(), "校验失败的申请不应转发到 Wavelog");
    }

    /** 审核意见 1：呼号在本站日志中没有任何记录时不得提交 */
    @Test
    void oqrsRejectsCallsignWithoutAnyLoggedQso() throws Exception {
        server.removeContext("/index.php/api/v2/qso");
        server.createContext("/index.php/api/v2/qso", exchange -> {
            byte[] b = "{\"data\":[],\"meta\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });

        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(mockFetcher()).submitOqrs(oqrs("N9EAT", "me@example.com"), "1.2.3.4").block();

        assertEquals(400, response.status());
        assertTrue(response.body().message().contains("没有"));
    }

    /** 审核意见 1：防重复提交 */
    @Test
    void duplicateOqrsSubmissionIsRejected() throws Exception {
        AtomicReference<Integer> forwardCount = new AtomicReference<>(0);
        server.createContext("/index.php/oqrs/save_oqrs_request_grouped", exchange -> {
            forwardCount.set(forwardCount.get() + 1);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        QsoStatsService service = service(mockFetcher());
        assertEquals(200,
            service.submitOqrs(oqrs("N9EAT", "me@example.com"), "1.2.3.4").block().status());

        StatsPayload.ApiResponse<StatsPayload.OqrsResult> again =
            service.submitOqrs(oqrs("n9eat", "ME@Example.com"), "1.2.3.4").block();
        assertEquals(409, again.status());
        assertTrue(again.body().message().contains("重复"));
        assertEquals(1, forwardCount.get(), "重复申请不应再次转发到 Wavelog");
    }

    /** 审核意见 1：OQRS 频率限制 */
    @Test
    void oqrsIsRateLimitedPerClient() throws Exception {
        server.createContext("/index.php/oqrs/save_oqrs_request_grouped", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        ReactiveSettingFetcher fetcher = mockFetcher();
        when(fetcher.fetch(eq("security"), eq(WavelogSettings.Security.class)))
            .thenReturn(Mono.just(new WavelogSettings.Security(20, 1, 50, 24)));
        QsoStatsService service = service(fetcher);

        assertEquals(200,
            service.submitOqrs(oqrs("N9EAT", "a@example.com"), "7.7.7.7").block().status());
        assertEquals(429,
            service.submitOqrs(oqrs("N9EAT", "b@example.com"), "7.7.7.7").block().status());
    }

    /** 审核意见 1：单次提交条数上限 */
    @Test
    void oqrsRejectsTooManyQsos() {
        ReactiveSettingFetcher fetcher = mockFetcher();
        when(fetcher.fetch(eq("security"), eq(WavelogSettings.Security.class)))
            .thenReturn(Mono.just(new WavelogSettings.Security(20, 5, 1, 24)));

        StatsPayload.ApiResponse<StatsPayload.OqrsResult> response =
            service(fetcher).submitOqrs(new StatsPayload.OqrsSubmitRequest(
                "N9EAT", "me@example.com", "", "B",
                List.of(new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1),
                    new StatsPayload.OqrsQso("2026-06-16", "17:06", "20m", "SSB", 1))),
                "1.2.3.4").block();

        assertEquals(400, response.status());
        assertTrue(response.body().message().contains("单次最多"));
    }

    // ---------- 变更检测缓存 ----------

    /**
     * 验证缓存策略：
     * 1. 缓存有效期内直接命中，不请求 Wavelog，「更新于」时间不变；
     * 2. TTL 到期后数据无变化 → 保留原「更新于」时间，仅延长有效期；
     * 3. TTL 到期后数据有变化 → 更新缓存与「更新于」时间。
     */
    @Test
    void cacheKeepsUpdatedAtWhenDataUnchangedAndRefreshesOnChange() throws Exception {
        AtomicReference<String> statBody = new AtomicReference<>("""
            {"data":{"qso":{"total":100,"activity":{"today":1,"month":2,"year":3},
              "breakdown":{"by_band":[{"band":"20m","count":12}],
                           "by_mode":[{"mode":"FT8","count":12}]},
              "dxcc":{"worked":1,"confirmed":1,"available":340}}},"meta":{}}
            """);
        AtomicReference<String> qsoBody = new AtomicReference<>(
            "{\"data\":[{\"id\":1,\"station_id\":1,\"call\":\"N9EAT\","
                + "\"band\":\"20m\",\"mode\":\"SSB\","
                + "\"qso_date\":\"2026-06-16 17:06:00\",\"gridsquare\":\"EN42\"}],\"meta\":{}}");
        server.removeContext("/index.php/api/v2/statistic");
        server.removeContext("/index.php/api/v2/qso");
        server.createContext("/index.php/api/v2/statistic", exchange -> {
            byte[] b = statBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        server.createContext("/index.php/api/v2/qso", exchange -> {
            byte[] b = qsoBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });

        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("api"), eq(WavelogSettings.Api.class)))
            .thenReturn(Mono.just(new WavelogSettings.Api(
                "http://localhost:" + port, "wl2_test", 1, 5, "我的通联")));
        when(fetcher.fetch(eq("display"), eq(WavelogSettings.Display.class)))
            .thenReturn(Mono.just(new WavelogSettings.Display(
                true, 50, true, "我的通联统计", true, "暂不可用", "modern", "auto", true)));
        when(fetcher.fetch(eq("layout"), eq(WavelogSettings.Layout.class)))
            .thenReturn(Mono.empty());

        QsoStatsService service = service(fetcher);
        StatsPayload.DashboardPayload first = service.buildDashboard().block();
        assertNotNull(first);
        String firstUpdatedAt = first.updatedAt();

        // 1) 缓存有效期内：第二次请求直接命中缓存，更新时间不变
        StatsPayload.DashboardPayload cached = service.buildDashboard().block();
        assertEquals(firstUpdatedAt, cached.updatedAt());

        // 2) TTL 到期后数据无变化：保留原「更新于」时间
        Thread.sleep(1200);
        StatsPayload.DashboardPayload unchanged = service.buildDashboard().block();
        assertEquals(firstUpdatedAt, unchanged.updatedAt());

        // 3) TTL 到期后数据有变化：更新缓存与「更新于」时间
        statBody.set(statBody.get().replace("\"total\":100", "\"total\":101"));
        Thread.sleep(1200);
        StatsPayload.DashboardPayload changed = service.buildDashboard().block();
        assertNotEquals(firstUpdatedAt, changed.updatedAt());
    }
}


