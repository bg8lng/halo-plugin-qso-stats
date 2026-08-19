package run.halo.qsostats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 前台组件（qso-stats.js）渲染所需的数据模型。
 *
 * <p>通过 /qso-stats/api/statistics 以 JSON 形式输出。
 */
public final class StatsPayload {

    private StatsPayload() {
    }

    /** 完整载荷 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(List<Section> sections, String updatedAt, String error,
                          String sectionTitle, boolean showSectionTitle,
                          boolean showUpdatedAt, String fallbackText,
                          boolean searchEnabled, int searchMaxResults) {
    }

    /** 一个统计区块，type 决定前端渲染方式 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String key, String title, String type, Object value) {
    }

    /** 单个数值（通联总数） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NumberValue(long value) {
    }

    /** 活跃度：今日 / 本月 / 今年 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityValue(long today, long month, long year) {
    }

    /** DXCC：已通联 / 已确认 / 可用字头 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DxccValue(long worked, long confirmed, long available) {
    }

    /** 分布行（波段 / 模式），percent 为占比（0-100，保留一位小数） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DistributionRow(String label, long count, double percent) {
    }

    /** 最近通联行 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecentRow(String call, String band, String mode, String time,
                            String gridsquare) {
    }

    /** 呼号查询结果中的一条通联（前端仅展示日期/模式/频段，其余字段供 OQRS 使用） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QsoRow(String date, String time, String band, String mode,
                         long stationId) {
    }

    /** 呼号查询接口（/qso-stats/api/search）的响应 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchPayload(String callsign, List<QsoRow> qsos, String error) {
    }

    /** 前端提交 OQRS 申请的请求体（POST /qso-stats/api/oqrs） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OqrsSubmitRequest(String callsign, String email, String message,
                                    String qslroute, List<OqrsQso> qsos) {
    }

    /** OQRS 申请中的一条通联（对应 Wavelog qsos[] 的 [date,time,band,mode,stationId]） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OqrsQso(String date, String time, String band, String mode,
                          long stationId) {
    }

    /** OQRS 提交结果 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OqrsResult(boolean success, String message) {
    }

    /** 静态工厂：便捷构造 */
    public static Section section(String key, String title, String type, Object value) {
        return new Section(key, title, type, value);
    }

    public static Payload success(List<Section> sections, String updatedAt,
                                  DisplayConfig display) {
        return new Payload(sections, updatedAt, null, display.sectionTitle(),
            display.showSectionTitle(), display.showUpdatedAt(), display.fallbackText(),
            display.searchEnabled(), display.searchMaxResults());
    }

    public static Payload error(String message, DisplayConfig display) {
        return new Payload(List.of(), null, message, display.sectionTitle(),
            display.showSectionTitle(), display.showUpdatedAt(), display.fallbackText(),
            display.searchEnabled(), display.searchMaxResults());
    }

    public static SearchPayload searchError(String message) {
        return new SearchPayload(null, List.of(), message);
    }

    public static OqrsResult oqrsResult(boolean success, String message) {
        return new OqrsResult(success, message);
    }

    /** 展示设置的只读快照，避免在载荷构造中反复读取 */
    public record DisplayConfig(String sectionTitle, boolean showSectionTitle,
                                boolean showUpdatedAt, String fallbackText,
                                boolean searchEnabled, int searchMaxResults) {
    }
}
