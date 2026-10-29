package com.agent.util;

import org.bouncycastle.crypto.digests.SM3Digest;

import java.nio.charset.StandardCharsets;

/**
 * 国密 SM3 摘要工具（BouncyCastle 实现），替代 MD5 作为文件/内容唯一摘要。
 * 输出 256 位（32 字节）摘要的 64 位十六进制串。
 */
public final class Sm3Utils {

    /** 计算字节数组的 SM3 摘要，返回 64 位小写十六进制串。 */
    public static String sm3Hex(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return toHex(out);
    }

    public static String sm3Hex(String text) {
        return sm3Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private Sm3Utils() {
    }
}
