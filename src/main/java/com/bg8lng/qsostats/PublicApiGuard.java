package com.bg8lng.qsostats;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 公开接口的防滥用组件：固定窗口频率限制 + 重复提交检测。
 *
 * <p>本插件的 {@code /qso-stats/api/search} 与 {@code /qso-stats/api/oqrs} 为
 * 无需认证的公开端点，其中 OQRS 还是写操作（会把访客邮箱与留言转发到 Wavelog），
 * 因此必须限制单个来源的调用频率，并拒绝短时间内内容完全相同的重复提交。
 *
 * <p>实现要点：
 * <ul>
 *   <li>纯内存、无外部依赖，单节点生效；重启后计数清零（对防滥用场景足够）；</li>
 *   <li>每次访问顺带清理过期条目，并设置条目上限，避免被构造大量来源标识撑爆内存；</li>
 *   <li>时间源可注入，便于单元测试推进时间而不依赖 sleep。</li>
 * </ul>
 */
@Component
public class PublicApiGuard {

    /** 计数器 / 指纹表的条目上限，超过后整表重置，防止内存被恶意撑大 */
    static final int MAX_ENTRIES = 20_000;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> fingerprints = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final AtomicLong lastSweep = new AtomicLong(0);

    public PublicApiGuard() {
        this(System::currentTimeMillis);
    }

    /** 供单元测试注入可控时间源 */
    PublicApiGuard(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 固定窗口频率限制。
     *
     * @param bucket       限流桶（不同接口互不影响）
     * @param clientId     调用方标识（通常是客户端 IP）
     * @param limit        窗口内允许的最大次数；{@code <= 0} 表示不限制
     * @param windowMillis 窗口长度（毫秒）
     * @return true 表示放行，false 表示已超出限制
     */
    public boolean allow(String bucket, String clientId, int limit, long windowMillis) {
        if (limit <= 0 || windowMillis <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        sweep(now);
        String key = bucket + '|' + normalize(clientId);
        Counter updated = counters.compute(key, (k, current) -> {
            if (current == null || current.expiresAt() <= now) {
                return new Counter(now + windowMillis, 1);
            }
            // 超限后计数封顶在 limit + 1，既能持续拒绝又不会溢出
            int next = current.count() >= limit ? limit + 1 : current.count() + 1;
            return new Counter(current.expiresAt(), next);
        });
        return updated.count() <= limit;
    }

    /**
     * 重复提交检测：窗口内首次出现的指纹会被登记并返回 false；
     * 若窗口内已存在同一指纹则返回 true（判定为重复提交）。
     *
     * @param fingerprint  提交内容指纹
     * @param windowMillis 去重窗口（毫秒）；{@code <= 0} 表示不去重
     */
    public boolean registerOrDuplicate(String fingerprint, long windowMillis) {
        if (windowMillis <= 0 || fingerprint == null) {
            return false;
        }
        long now = clock.getAsLong();
        sweep(now);
        Long previous = fingerprints.putIfAbsent(fingerprint, now + windowMillis);
        if (previous == null) {
            return false;
        }
        if (previous <= now) {
            // 旧指纹已过期，替换为新窗口
            fingerprints.put(fingerprint, now + windowMillis);
            return false;
        }
        return true;
    }

    /**
     * 撤销一次 {@link #registerOrDuplicate} 登记。
     *
     * <p>用于提交最终失败的场景：失败的请求不应占用去重窗口，
     * 否则访客修正问题后无法重新提交。
     */
    public void release(String fingerprint) {
        if (fingerprint != null) {
            fingerprints.remove(fingerprint);
        }
    }

    /** 当前登记的限流条目数（测试用） */
    int counterSize() {
        return counters.size();
    }

    /** 当前登记的指纹条目数（测试用） */
    int fingerprintSize() {
        return fingerprints.size();
    }

    /** 清理过期条目；最多每秒执行一次，避免高频请求下的无谓遍历 */
    private void sweep(long now) {
        long previous = lastSweep.get();
        boolean oversized = counters.size() > MAX_ENTRIES || fingerprints.size() > MAX_ENTRIES;
        if (!oversized && now - previous < 1_000L) {
            return;
        }
        if (!lastSweep.compareAndSet(previous, now)) {
            return;
        }
        removeExpired(counters.entrySet().iterator(), entry -> entry.getValue().expiresAt() <= now);
        removeExpired(fingerprints.entrySet().iterator(), entry -> entry.getValue() <= now);
        if (counters.size() > MAX_ENTRIES) {
            counters.clear();
        }
        if (fingerprints.size() > MAX_ENTRIES) {
            fingerprints.clear();
        }
    }

    private static <K, V> void removeExpired(Iterator<Map.Entry<K, V>> iterator,
                                             java.util.function.Predicate<Map.Entry<K, V>> expired) {
        while (iterator.hasNext()) {
            if (expired.test(iterator.next())) {
                iterator.remove();
            }
        }
    }

    private static String normalize(String clientId) {
        return clientId == null || clientId.isBlank() ? "unknown" : clientId;
    }

    /** 固定窗口计数条目：窗口过期时间 + 窗口内计数 */
    private record Counter(long expiresAt, int count) {
    }
}
