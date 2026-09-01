package com.bg8lng.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link WavelogClient#normalizeBase} 单元测试。
 */
class WavelogClientTest {

    @Test
    void appendsIndexPhpWhenMissing() {
        assertEquals("https://log.example.com/index.php",
            WavelogClient.normalizeBase("https://log.example.com"));
    }

    @Test
    void keepsExistingIndexPhp() {
        assertEquals("https://log.example.com/index.php",
            WavelogClient.normalizeBase("https://log.example.com/index.php"));
    }

    @Test
    void trimsTrailingSlashes() {
        assertEquals("https://log.example.com/index.php",
            WavelogClient.normalizeBase("https://log.example.com/"));
        assertEquals("https://log.example.com/index.php",
            WavelogClient.normalizeBase("https://log.example.com/index.php/"));
    }

    @Test
    void handlesBlank() {
        assertEquals("", WavelogClient.normalizeBase(""));
        assertEquals("", WavelogClient.normalizeBase(null));
        assertEquals("", WavelogClient.normalizeBase("   "));
    }

    @Test
    void keepsSubPathInstalls() {
        assertEquals("https://log.example.com/radio/index.php",
            WavelogClient.normalizeBase("https://log.example.com/radio"));
    }
}
