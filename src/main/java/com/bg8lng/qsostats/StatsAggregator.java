package com.bg8lng.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * 纯逻辑：把 Wavelog 返回的 QSO 列表聚合为统计图表所需的日 / 月 / 历年 / 波段 / 模式数据。
 *
 * <p>不依赖 Spring，便于单元测试。
 */
public final class StatsAggregator {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("M月");

    private StatsAggregator() {
    }

    /**
     * 聚合 QSO 列表（newest first）为仪表盘统计块。
     *
     * @param qsos          Wavelog /api/v2/qso 返回的通联节点列表
     * @param year          统计基准年（日/月统计均基于该年）
     * @param today         用于计算「近 N 天」窗口的日期（服务端本地时区）
     * @param recentLimit   最近通联保留条数
     */
    public static StatsPayload.Statistics aggregate(List<JsonNode> qsos, int year,
                                                    LocalDate today, int recentLimit) {
        // 通联日期 -> 数量
        Map<LocalDate, Long> dayCounts = new HashMap<>();
        // 年 -> 数量
        Map<Integer, Long> yearCounts = new HashMap<>();
        // 年-月 -> 数量
        Map<String, Long> monthCounts = new HashMap<>();
        // 波段 -> 数量
        Map<String, Long> bandCounts = new HashMap<>();
        // 模式 -> 数量
        Map<String, Long> modeCounts = new HashMap<>();

        long total = 0;
        for (JsonNode qso : qsos) {
            LocalDate date = parseDate(qso.path("qso_date").asText(""));
            total++;
            if (date != null) {
                dayCounts.merge(date, 1L, Long::sum);
                yearCounts.merge(date.getYear(), 1L, Long::sum);
                monthCounts.merge(yearMonth(date), 1L, Long::sum);
            }
            String band = normalize(qso.path("band").asText(""));
            if (StringUtils.isNotBlank(band)) {
                bandCounts.merge(band, 1L, Long::sum);
            }
            String mode = normalize(qso.path("mode").asText(""));
            if (StringUtils.isNotBlank(mode)) {
                modeCounts.merge(mode, 1L, Long::sum);
            }
        }

        return new StatsPayload.Statistics(
            year,
            total,
            totalOfYear(dayCounts, today),
            monthOfYear(dayCounts, year, today.getMonthValue()),
            yearCounts.getOrDefault(year, 0L),
            0, 0, 0, // KPI 中的 DXCC 由 statistic 接口补充，聚合器不负责
            lastDays(dayCounts, today, 30),
            monthsOfYear(monthCounts, year),
            sortedPoints(yearCounts, true),
            sortedPoints(bandCounts, false),
            sortedPoints(modeCounts, false));
    }

    /** 近 N 天逐日数量（含今天），无数据的日期补 0 */
    static List<StatsPayload.CountPoint> lastDays(Map<LocalDate, Long> dayCounts,
                                                  LocalDate today, int days) {
        List<StatsPayload.CountPoint> out = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            out.add(new StatsPayload.CountPoint(d.format(DAY_LABEL),
                dayCounts.getOrDefault(d, 0L)));
        }
        return out;
    }

    /** 指定年份 1-12 月逐月数量（按出现年份的月对齐），无数据的月补 0 */
    static List<StatsPayload.CountPoint> monthsOfYear(Map<String, Long> monthCounts, int year) {
        List<StatsPayload.CountPoint> out = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            String key = year + "-" + (m < 10 ? "0" : "") + m;
            out.add(new StatsPayload.CountPoint(m + "月", monthCounts.getOrDefault(key, 0L)));
        }
        return out;
    }

    static long totalOfYear(Map<LocalDate, Long> dayCounts, LocalDate today) {
        return dayCounts.entrySet().stream()
            .filter(e -> e.getKey().getYear() == today.getYear())
            .mapToLong(Map.Entry::getValue)
            .sum();
    }

    static long monthOfYear(Map<LocalDate, Long> dayCounts, int year, int month) {
        return dayCounts.entrySet().stream()
            .filter(e -> e.getKey().getYear() == year && e.getKey().getMonthValue() == month)
            .mapToLong(Map.Entry::getValue)
            .sum();
    }

    /** 按数量降序输出 count 点 */
    static List<StatsPayload.CountPoint> sortedPoints(Map<?, Long> counts, boolean numericSort) {
        List<Map.Entry<?, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            if (byCount != 0) {
                return byCount;
            }
            return String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey()));
        });
        List<StatsPayload.CountPoint> out = new ArrayList<>(entries.size());
        for (Map.Entry<?, Long> e : entries) {
            out.add(new StatsPayload.CountPoint(String.valueOf(e.getKey()), e.getValue()));
        }
        return out;
    }

    private static String yearMonth(LocalDate date) {
        int m = date.getMonthValue();
        return date.getYear() + "-" + (m < 10 ? "0" : "") + m;
    }

    private static String normalize(String v) {
        return StringUtils.trimToEmpty(v);
    }

    static LocalDate parseDate(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 10) {
            s = s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}