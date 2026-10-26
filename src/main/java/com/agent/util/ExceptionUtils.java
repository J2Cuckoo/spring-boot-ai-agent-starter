package com.agent.util;

/**
 * 异常工具：提取根因与简述。
 */
public final class ExceptionUtils {

    public static Throwable root(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    public static String describe(Throwable t) {
        Throwable r = root(t);
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    private ExceptionUtils() {
    }
}
