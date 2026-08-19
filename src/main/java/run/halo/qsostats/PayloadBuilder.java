package run.halo.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * 纯逻辑：把 Wavelog 接口返回的原始 JSON 转换为前台组件所需的统计区块。
 *
 * <p>不依赖 Spring，便于单元测试。
 */
public final class PayloadBuilder {

    private PayloadBuilder() {
    }

    /**
     * 根据启用的统计项目构建区块列表。
     *
     * @param qsoNode    /api/v2/statistic?profile=qso 的完整响应
     * @param recentNode /api/v2/qso?limit=N 的完整响应，可为 null
     * @param items      后台配置的统计项目
     */
    public static List<StatsPayload.Section> buildSections(JsonNode qsoNode, JsonNode recentNode,
                                                           List<WavelogSettings.Item> items) {
        List<StatsPayload.Section> sections = new ArrayList<>();
        if (qsoNode == null) {
            return sections;
        }
        JsonNode qso = qsoNode.path("data").path("qso");
        if (qso.isMissingNode()) {
            return sections;
        }
        for (WavelogSettings.Item item : items) {
            if (!item.enabledOrDefault()) {
                continue;
            }
            StatsPayload.Section section = buildSection(item, qso, recentNode);
            if (section != null) {
                sections.add(section);
            }
        }
        return sections;
    }

    static StatsPayload.Section buildSection(WavelogSettings.Item item, JsonNode qso,
                                             JsonNode recentNode) {
        return switch (item.key()) {
            case "total_qsos" -> StatsPayload.section(item.key(), item.titleOrDefault("通联总数"),
                "number", new StatsPayload.NumberValue(qso.path("total").asLong(0)));
            case "activity" -> {
                JsonNode activity = qso.path("activity");
                yield StatsPayload.section(item.key(), item.titleOrDefault("活跃度"), "activity",
                    new StatsPayload.ActivityValue(activity.path("today").asLong(0),
                        activity.path("month").asLong(0),
                        activity.path("year").asLong(0)));
            }
            case "dxcc" -> {
                JsonNode dxcc = qso.path("dxcc");
                yield StatsPayload.section(item.key(), item.titleOrDefault("DXCC 字头"), "dxcc",
                    new StatsPayload.DxccValue(dxcc.path("worked").asLong(0),
                        dxcc.path("confirmed").asLong(0),
                        dxcc.path("available").asLong(0)));
            }
            case "bands" -> distribution(item, qso.path("breakdown").path("by_band"), "band");
            case "modes" -> distribution(item, qso.path("breakdown").path("by_mode"), "mode");
            case "recent" -> recent(item, recentNode);
            default -> null;
        };
    }

    private static StatsPayload.Section distribution(WavelogSettings.Item item, JsonNode array,
                                                     String labelField) {
        int limit = item.limitOrDefault(5);
        List<StatsPayload.DistributionRow> rows = new ArrayList<>();
        long total = 0;
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                total += node.path("count").asLong(0);
            }
            int taken = 0;
            for (JsonNode node : array) {
                if (taken >= limit) {
                    break;
                }
                long count = node.path("count").asLong(0);
                double percent = total > 0 ? Math.round(count * 1000.0 / total) / 10.0 : 0;
                rows.add(new StatsPayload.DistributionRow(node.path(labelField).asText("—"),
                    count, percent));
                taken++;
            }
        }
        return StatsPayload.section(item.key(), item.titleOrDefault("分布"), "distribution", rows);
    }

    private static StatsPayload.Section recent(WavelogSettings.Item item, JsonNode recentNode) {
        int limit = item.limitOrDefault(5);
        List<StatsPayload.RecentRow> rows = new ArrayList<>();
        JsonNode data = recentNode == null ? null : recentNode.path("data");
        if (data != null && data.isArray()) {
            int taken = 0;
            for (JsonNode node : data) {
                if (taken >= limit) {
                    break;
                }
                rows.add(new StatsPayload.RecentRow(node.path("call").asText("—"),
                    node.path("band").asText(""),
                    node.path("mode").asText(""),
                    formatDateTime(node.path("qso_date").asText("")),
                    node.path("gridsquare").asText("")));
                taken++;
            }
        }
        return StatsPayload.section(item.key(), item.titleOrDefault("最近通联"), "recent", rows);
    }

    /**
     * 兼容 Wavelog 多种日期时间格式：{@code YYYY-MM-DD}、
     * {@code YYYY-MM-DD HH:MM[:SS]}、ISO-8601（{@code T} 分隔）等。
     * 统一输出 {@code YYYY-MM-DD HH:MM}。
     */
    static String formatDateTime(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String s = raw.trim().replace('T', ' ');
        if (s.length() >= 16) {
            return s.substring(0, 16);
        }
        if (s.length() >= 10) {
            return s.substring(0, 10);
        }
        return s;
    }
}
