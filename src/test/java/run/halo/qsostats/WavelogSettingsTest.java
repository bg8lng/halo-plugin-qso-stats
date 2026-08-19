package run.halo.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 设置模型反序列化与默认值测试。
 *
 * <p>验证 Halo {@code ReactiveSettingFetcher.fetch(group, clazz)} 使用的
 * {@code JsonNode.convertValue} 语义（字段缺失 → null → 默认值生效）。
 */
class WavelogSettingsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void apiGroupBinding() throws Exception {
        String json = """
            {"baseUrl":"https://log.example.com","apiToken":"wl2_abc",
             "cacheSeconds":300,"timeoutSeconds":10,"pageTitle":"我的通联"}
            """;
        WavelogSettings.Api api =
            mapper.convertValue(mapper.readTree(json), WavelogSettings.Api.class);
        assertEquals("https://log.example.com", api.baseUrlOrDefault());
        assertEquals("wl2_abc", api.apiTokenOrDefault());
        assertEquals(300, api.cacheSecondsOrDefault());
        assertEquals(10, api.timeoutSecondsOrDefault());
        assertEquals("我的通联", api.pageTitleOrDefault());
        assertTrue(api.isConfigured());
    }

    @Test
    void statsItemsBinding() throws Exception {
        String json = """
            {"items":[{"key":"bands","title":"波段","enabled":true,"limit":6},
                      {"key":"total_qsos","title":"通联总数","enabled":true}]}
            """;
        WavelogSettings.Stats stats =
            mapper.convertValue(mapper.readTree(json), WavelogSettings.Stats.class);
        assertEquals(2, stats.itemsOrDefault().size());
        WavelogSettings.Item bands = stats.itemsOrDefault().get(0);
        assertEquals("bands", bands.key());
        assertTrue(bands.enabledOrDefault());
        assertEquals(6, bands.limitOrDefault(5));
        // 未配置 limit 的项目使用默认值
        assertEquals(5, stats.itemsOrDefault().get(1).limitOrDefault(5));
    }

    @Test
    void missingFieldsFallBackToDefaults() throws Exception {
        WavelogSettings.Api api =
            mapper.convertValue(mapper.readTree("{}"), WavelogSettings.Api.class);
        assertFalse(api.isConfigured());
        assertEquals(300, api.cacheSecondsOrDefault());
        assertEquals(10, api.timeoutSecondsOrDefault());
        assertEquals("通联统计", api.pageTitleOrDefault());

        WavelogSettings.Stats stats =
            mapper.convertValue(mapper.readTree("{}"), WavelogSettings.Stats.class);
        assertTrue(stats.itemsOrDefault().isEmpty());

        WavelogSettings.Display display =
            mapper.convertValue(mapper.readTree("{}"), WavelogSettings.Display.class);
        assertTrue(display.showSectionTitleOrDefault());
        assertTrue(display.showUpdatedAtOrDefault());
        assertEquals("通联统计", display.sectionTitleOrDefault());
        assertEquals("统计数据暂不可用，请稍后再试", display.fallbackTextOrDefault());
    }

    @Test
    void searchGroupBinding() throws Exception {
        WavelogSettings.Search search = mapper.convertValue(
            mapper.readTree("{\"enabled\":false,\"maxResults\":20}"),
            WavelogSettings.Search.class);
        assertFalse(search.enabledOrDefault());
        assertEquals(20, search.maxResultsOrDefault());

        WavelogSettings.Search defaults = mapper.convertValue(
            mapper.readTree("{}"), WavelogSettings.Search.class);
        assertTrue(defaults.enabledOrDefault());
        assertEquals(50, defaults.maxResultsOrDefault());
    }

    @Test
    void disabledFlagDefaultsToEnabled() throws Exception {
        WavelogSettings.Item item =
            mapper.convertValue(mapper.readTree("{\"key\":\"dxcc\"}"),
                WavelogSettings.Item.class);
        assertTrue(item.enabledOrDefault());
    }
}
