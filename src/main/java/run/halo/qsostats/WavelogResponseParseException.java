package run.halo.qsostats;

/**
 * Wavelog 响应体不是合法 JSON 时抛出。
 */
public class WavelogResponseParseException extends RuntimeException {

    public WavelogResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
