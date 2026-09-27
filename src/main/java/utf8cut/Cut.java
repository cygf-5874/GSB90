package utf8cut;

/**
 * 按「不超过 maxBytes 的最长合法 UTF-8 前缀」切分字节序列。
 *
 * <p>输入按字节给出，返回值也是字节；切点必须落在一个完整码点的边界上。
 */
public final class Cut {

    private Cut() {
    }

    /**
     * 返回 {@code src} 中长度不超过 {@code maxBytes} 的最长合法 UTF-8 前缀。
     *
     * @param src      源字节序列，自身必须是合法 UTF-8
     * @param maxBytes 允许的最大字节数
     * @throws IllegalArgumentException src 为 null 或 maxBytes 为负数
     */
    public static byte[] cut(byte[] src, int maxBytes) {
        if (src == null) {
            throw new IllegalArgumentException("src 不能为 null");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes 不能为负数：" + maxBytes);
        }
        if (maxBytes == 0 || src.length == 0) {
            return new byte[0];
        }

        int end = Math.min(maxBytes, src.length);
        while (end > 0 && Bytes.isLead(src[end - 1])) {
            end--;
        }
        return Bytes.slice(src, 0, end);
    }
}
