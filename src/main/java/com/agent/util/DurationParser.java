package com.agent.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时长与容量解析：支持 {@code 30m / 3d / 1h30m / 500ms} 与 {@code 20mb / 64kb}。
 */
public final class DurationParser {

    private static final Pattern SEGMENT = Pattern.compile("(\\d+)(ms|s|m|h|d)");
    private static final Pattern SIZE = Pattern.compile("(\\d+)(b|kb|mb|gb)", Pattern.CASE_INSENSITIVE);

    /** 解析时长；纯数字按秒处理；无法解析抛 {@link IllegalArgumentException}。 */
    public static Duration parseDuration(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("duration is blank");
        }
        Matcher m = SEGMENT.matcher(text.toLowerCase());
        Duration total = Duration.ZERO;
        int consumed = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> Duration.ZERO;
            });
            consumed += m.group().length();
        }
        if (consumed == 0) {
            if (text.matches("\\d+")) {
                return Duration.ofSeconds(Long.parseLong(text));
            }
            throw new IllegalArgumentException("illegal duration: " + text);
        }
        return total;
    }

    /** 解析容量为字节数：{@code 20mb -> 20 * 1024 * 1024}。 */
    public static long parseDataSize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("size is blank");
        }
        Matcher m = SIZE.matcher(text.toLowerCase());
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "b" -> n;
                case "kb" -> n * 1024;
                case "mb" -> n * 1024 * 1024;
                case "gb" -> n * 1024 * 1024 * 1024;
                default -> throw new IllegalArgumentException("illegal size: " + text);
            };
        }
        if (text.matches("\\d+")) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("illegal size: " + text);
    }

    private DurationParser() {
    }
}
