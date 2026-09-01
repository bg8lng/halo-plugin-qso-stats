package com.bg8lng.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link PayloadBuilder} 单元测试。
 */
class PayloadBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wavelog /api/v2/statistic?profile=qso 示例响应（来自官方文档） */
    private static final String STATS_JSON = """
        {
          "data": {
            "qso": {
              "total": 28,
              "activity": { "today": 2, "month": 5, "year": 7 },
              "breakdown": {
                "by_band": [
                  { "band": "20m", "count": 12 },
                  { "band": "40m", "count": 8 },
                  { "band": "15m", "count": 5 },
                  { "band": "10m", "count": 3 }
                ],
                "by_mode": [
                  { "mode": "FT8", "count": 15 },
                  { "mode": "CW", "count": 7 },
                  { "mode": "SSB", "count": 6 }
                ]
              },
              "dxcc": {
                "worked": 15, "confirmed": 9, "confirmed_paper": 6,
                "confirmed_lotw": 7, "available": 340,
                "deleted": { "worked": 1, "confirmed_paper": 1, "confirmed_lotw": 0 }
              }
            }
          },
          "meta": { "profile": "qso", "admin": false }
        }
        """;

    /** Wavelog /api/v2/qso?limit=... 示例响应 */
    private static final String RECENT_JSON = """
        {
          "data": [
            { "id": 4886, "call": "N9EAT", "band": "20m", "mode": "SSB",
              "qso_date": "2026-06-16 17:06:00", "gridsquare": "EN42" },
            { "id": 4885, "call": "W1AW", "band": "40m", "mode": "CW",
              "qso_date": "2026-06-16T12:04:00Z", "gridsquare": "" }
          ],
          "meta": { "page": 1, "per_page": 50, "count": 2, "total": 2 }
        }
        """;

    private JsonNode statsNode() throws Exception {
        return MAPPER.readTree(STATS_JSON);
    }

    private JsonNode recentNode() throws Exception {
        return MAPPER.readTree(RECENT_JSON);
    }

    @Test
    void buildsAllDefaultSections() throws Exception {
        List<WavelogSettings.Item> items = List.of(
            new WavelogSettings.Item("total_qsos", "通联总数", true, null),
            new WavelogSettings.Item("activity", "活跃度", true, null),
            new WavelogSettings.Item("dxcc", "DXCC 字头", true, null),
            new WavelogSettings.Item("bands", "波段分布", true, 3),
            new WavelogSettings.Item("modes", "模式分布", true, 2),
            new WavelogSettings.Item("recent", "最近通联", true, 5)
        );

        List<StatsPayload.Section> sections =
            PayloadBuilder.buildSections(statsNode(), recentNode(), items);

        assertEquals(6, sections.size());

        StatsPayload.Section total = sections.get(0);
        assertEquals("number", total.type());
        assertEquals(28L, ((StatsPayload.NumberValue) total.value()).value());

        StatsPayload.Section activity = sections.get(1);
        StatsPayload.ActivityValue av = (StatsPayload.ActivityValue) activity.value();
        assertEquals(2L, av.today());
        assertEquals(5L, av.month());
        assertEquals(7L, av.year());

        StatsPayload.Section dxcc = sections.get(2);
        StatsPayload.DxccValue dv = (StatsPayload.DxccValue) dxcc.value();
        assertEquals(15L, dv.worked());
        assertEquals(9L, dv.confirmed());
        assertEquals(340L, dv.available());

        // 波段分布按配置 limit=3 截断
        StatsPayload.Section bands = sections.get(3);
        @SuppressWarnings("unchecked")
        List<StatsPayload.DistributionRow> bandRows =
            (List<StatsPayload.DistributionRow>) bands.value();
        assertEquals(3, bandRows.size());
        assertEquals("20m", bandRows.get(0).label());
        assertEquals(12L, bandRows.get(0).count());
        assertEquals("15m", bandRows.get(2).label());

        // 模式分布 limit=2
        StatsPayload.Section modes = sections.get(4);
        @SuppressWarnings("unchecked")
        List<StatsPayload.DistributionRow> modeRows =
            (List<StatsPayload.DistributionRow>) modes.value();
        assertEquals(2, modeRows.size());
        assertEquals("FT8", modeRows.get(0).label());

        // 最近通联
        StatsPayload.Section recent = sections.get(5);
        @SuppressWarnings("unchecked")
        List<StatsPayload.RecentRow> recentRows =
            (List<StatsPayload.RecentRow>) recent.value();
        assertEquals(2, recentRows.size());
        assertEquals("N9EAT", recentRows.get(0).call());
        assertEquals("20m", recentRows.get(0).band());
        assertEquals("SSB", recentRows.get(0).mode());
        assertEquals("2026-06-16 17:06", recentRows.get(0).time());
        assertEquals("2026-06-16 12:04", recentRows.get(1).time());
    }

    @Test
    void distributionPercentSumsToHundred() throws Exception {
        List<WavelogSettings.Item> items = List.of(
            new WavelogSettings.Item("bands", "波段", true, 10)
        );
        List<StatsPayload.Section> sections =
            PayloadBuilder.buildSections(statsNode(), null, items);
        @SuppressWarnings("unchecked")
        List<StatsPayload.DistributionRow> rows =
            (List<StatsPayload.DistributionRow>) sections.get(0).value();
        double sum = rows.stream().mapToDouble(StatsPayload.DistributionRow::percent).sum();
        // 12/28 + 8/28 + 5/28 + 3/28 = 100，四舍五入误差在 0.5 以内
        assertTrue(Math.abs(sum - 100) < 0.5, "percent sum should be ~100, got " + sum);
    }

    @Test
    void disabledAndUnknownItemsAreSkipped() throws Exception {
        List<WavelogSettings.Item> items = List.of(
            new WavelogSettings.Item("total_qsos", "通联总数", false, null),
            new WavelogSettings.Item("unknown_key", "未知", true, null),
            new WavelogSettings.Item("modes", "模式", true, null)
        );
        List<StatsPayload.Section> sections =
            PayloadBuilder.buildSections(statsNode(), null, items);
        assertEquals(1, sections.size());
        assertEquals("modes", sections.get(0).key());
    }

    @Test
    void nullStatsNodeYieldsEmptySections() {
        List<StatsPayload.Section> sections = PayloadBuilder.buildSections(null, null,
            List.of(new WavelogSettings.Item("total_qsos", "通联总数", true, null)));
        assertTrue(sections.isEmpty());
    }

    @Test
    void formatsDateTimeVariants() {
        assertEquals("2026-06-16 17:06", PayloadBuilder.formatDateTime("2026-06-16 17:06:00"));
        assertEquals("2026-06-16 17:06", PayloadBuilder.formatDateTime("2026-06-16T17:06:00Z"));
        assertEquals("2026-06-16 17:06", PayloadBuilder.formatDateTime("2026-06-16T17:06"));
        assertEquals("2026-06-16", PayloadBuilder.formatDateTime("2026-06-16"));
        assertEquals("", PayloadBuilder.formatDateTime(""));
        assertEquals("", PayloadBuilder.formatDateTime(null));
    }

    @Test
    void buildsSearchResultRows() throws Exception {
        String json = """
            {"data":[
              {"id":4886,"station_id":1,"call":"N9EAT","band":"20m","mode":"SSB",
               "qso_date":"2026-06-16 17:06:00","gridsquare":"EN42"},
              {"id":4885,"station_id":2,"call":"N9EAT","band":"SAT","mode":"FM",
               "qso_date":"2026-06-16T12:04:00Z","gridsquare":""}
            ],"meta":{"total":2}}
            """;
        StatsPayload.SearchPayload result =
            PayloadBuilder.buildSearchResult("N9EAT", MAPPER.readTree(json));

        assertEquals(null, result.error());
        assertEquals("N9EAT", result.callsign());
        assertEquals(2, result.qsos().size());

        StatsPayload.QsoRow first = result.qsos().get(0);
        assertEquals("2026-06-16", first.date());
        assertEquals("17:06", first.time());
        assertEquals("20m", first.band());
        assertEquals("SSB", first.mode());
        assertEquals(1L, first.stationId());

        StatsPayload.QsoRow second = result.qsos().get(1);
        assertEquals("2026-06-16", second.date());
        assertEquals("12:04", second.time());
        assertEquals("SAT", second.band());
        assertEquals("FM", second.mode());
        assertEquals(2L, second.stationId());
    }

    @Test
    void nullSearchNodeYieldsEmptyRows() {
        StatsPayload.SearchPayload result = PayloadBuilder.buildSearchResult("BG8LNG", null);
        assertEquals("BG8LNG", result.callsign());
        assertTrue(result.qsos().isEmpty());
    }
}
