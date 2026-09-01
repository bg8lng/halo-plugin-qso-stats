package com.bg8lng.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 公开接口防滥用组件测试：频率限制窗口、重复提交窗口与失败释放。
 *
 * <p>使用可控时间源推进时间，不依赖 sleep。
 */
class PublicApiGuardTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final PublicApiGuard guard = new PublicApiGuard(now::get);

    @Test
    void allowsUpToLimitWithinWindow() {
        assertTrue(guard.allow("oqrs", "1.1.1.1", 2, 60_000L));
        assertTrue(guard.allow("oqrs", "1.1.1.1", 2, 60_000L));
        assertFalse(guard.allow("oqrs", "1.1.1.1", 2, 60_000L));
    }

    @Test
    void countersAreIsolatedPerClientAndBucket() {
        assertTrue(guard.allow("oqrs", "1.1.1.1", 1, 60_000L));
        assertFalse(guard.allow("oqrs", "1.1.1.1", 1, 60_000L));
        // 不同来源
        assertTrue(guard.allow("oqrs", "2.2.2.2", 1, 60_000L));
        // 不同接口
        assertTrue(guard.allow("search", "1.1.1.1", 1, 60_000L));
    }

    @Test
    void counterResetsAfterWindow() {
        assertTrue(guard.allow("oqrs", "1.1.1.1", 1, 60_000L));
        assertFalse(guard.allow("oqrs", "1.1.1.1", 1, 60_000L));
        now.addAndGet(60_001L);
        assertTrue(guard.allow("oqrs", "1.1.1.1", 1, 60_000L));
    }

    @Test
    void zeroLimitDisablesRateLimiting() {
        for (int i = 0; i < 100; i++) {
            assertTrue(guard.allow("oqrs", "1.1.1.1", 0, 60_000L));
        }
    }

    @Test
    void duplicateFingerprintIsRejectedWithinWindow() {
        assertFalse(guard.registerOrDuplicate("abc", 3_600_000L));
        assertTrue(guard.registerOrDuplicate("abc", 3_600_000L));
        assertFalse(guard.registerOrDuplicate("other", 3_600_000L));
    }

    @Test
    void duplicateWindowExpires() {
        assertFalse(guard.registerOrDuplicate("abc", 1_000L));
        assertTrue(guard.registerOrDuplicate("abc", 1_000L));
        now.addAndGet(1_001L);
        assertFalse(guard.registerOrDuplicate("abc", 1_000L));
    }

    @Test
    void releaseAllowsRetryAfterFailedSubmission() {
        assertFalse(guard.registerOrDuplicate("abc", 3_600_000L));
        guard.release("abc");
        assertFalse(guard.registerOrDuplicate("abc", 3_600_000L));
    }

    @Test
    void zeroWindowDisablesDeduplication() {
        assertFalse(guard.registerOrDuplicate("abc", 0L));
        assertFalse(guard.registerOrDuplicate("abc", 0L));
    }

    @Test
    void expiredEntriesAreSweptSoMemoryDoesNotGrowUnbounded() {
        for (int i = 0; i < 500; i++) {
            guard.allow("search", "10.0.0." + i, 5, 1_000L);
            guard.registerOrDuplicate("fp-" + i, 1_000L);
            now.addAndGet(1L);
        }
        assertTrue(guard.counterSize() > 0);
        // 推进到全部过期后再触发一次访问，过期条目应被清理
        now.addAndGet(10_000L);
        guard.allow("search", "10.0.0.999", 5, 1_000L);
        assertEquals(1, guard.counterSize());
        assertEquals(0, guard.fingerprintSize());
    }
}
