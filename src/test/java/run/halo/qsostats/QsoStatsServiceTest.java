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
                {"data":[{"id":4886,"call":"N9EAT","band":"20m","mode":"SSB",
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
            .thenReturn(Mono.just(new WavelogSettings.Display(true, "我的通联统计", true, "暂不可用")));
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
}
