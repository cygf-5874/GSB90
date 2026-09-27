package utf8cut;

/** 字节级小工具：十六进制转写、区间拷贝、字节分类。纯函数，不依赖默认字符集与 Locale。 */
public final class Bytes {

    private Bytes() {
    }

    /** 把 [from, to) 区间的字节写成小写十六进制，空格分隔，例如 {@code "e4 b8 ad"}。 */
    public static String hex(byte[] data, int from, int to) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < data.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            int v = data[i] & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /** 把整个数组写成小写十六进制，空格分隔。 */
    public static String hex(byte[] data) {
        return hex(data, 0, data == null ? 0 : data.length);
    }

    /** 复制 [from, to) 区间；区间越界时按可用范围收缩。 */
    public static byte[] slice(byte[] data, int from, int to) {
        int end = Math.min(to, data.length);
        if (from < 0 || end < from) {
            return new byte[0];
        }
        byte[] out = new byte[end - from];
        System.arraycopy(data, from, out, 0, end - from);
        return out;
    }

    /** 判断一个字节的最高位是否为 1，即取值落在 0x80..0xFF。 */
    public static boolean isContinuation(int b) {
        int v = b & 0xFF;
        return v >= 0x80 && v <= 0xFF;
    }

    /** 判断一个字节是否是多字节序列的起始字节，即取值不小于 0xC0。 */
    public static boolean isLead(int b) {
        return (b & 0xFF) >= 0xC0;
    }
}
