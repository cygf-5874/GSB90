package utf8cut;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UTF-8 解码与校验：把字节序列解成码点表，并可还原成文本。
 *
 * <p>{@link #validate(byte[])} 是严格入口，发现非法编码时抛 {@link MalformedUtf8}；
 * {@link #decode(byte[])} 是解码入口，返回码点表。两者共用同一套长度分派规则。
 */
public final class Utf8 {

    /** 字节序标记 U+FEFF。 */
    public static final int BOM = 0xFEFF;

    /** 替换字符 U+FFFD。 */
    public static final int REPLACEMENT = 0xFFFD;

    /** 一段多字节编码允许的最小码点，按「续字节个数」索引。 */
    private static final int[] MIN_VALUE = {0, 0, 0, 0};

    private Utf8() {
    }

    /** 校验失败时抛出：带上报告出的位置与违反的保证条目号。 */
    public static final class MalformedUtf8 extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int index;
        private final int rule;

        public MalformedUtf8(int index, int rule, String detail) {
            super("非法 UTF-8：源字节=" + detail + "（报告位置=" + index + "，违反第 " + rule + " 条保证）");
            this.index = index;
            this.rule = rule;
        }

        /** 报告出的出错位置。 */
        public int index() {
            return index;
        }

        /** 违反的保证条目号（README「对外保证」的编号）。 */
        public int rule() {
            return rule;
        }
    }

    // ---------------------------------------------------------------- 严格校验

    /**
     * 严格校验整段字节序列：合法则正常返回，发现非法编码时抛 {@link MalformedUtf8}。
     *
     * <p>非法情形包括：孤立续字节、0xC0 或 0xC1 起始、0xF5..0xFF 起始、截断的多字节序列。
     */
    public static void validate(byte[] src) {
        if (src == null) {
            throw new IllegalArgumentException("src 不能为 null");
        }
        StringBuilder seen = new StringBuilder();
        int i = skipLeadingBom(src);
        while (i < src.length) {
            int b = src[i] & 0xFF;
            if (b < 0x80) {
                seen.append((char) b);
                i += 1;
                continue;
            }
            if (b < 0xC0) {
                throw malformed(seen, 1, src);
            }
            if (b >= 0xF5) {
                throw malformed(seen, 3, src);
            }
            int extra = (b < 0xE0) ? 1 : (b < 0xF0) ? 2 : 3;
            if (i + extra >= src.length) {
                throw malformed(seen, 3, src);
            }
            int cp = b & (0x7F >> extra);
            boolean shapeOk = true;
            for (int k = 1; k <= extra; k++) {
                int c = src[i + k] & 0xFF;
                if (!Bytes.isContinuation(c)) {
                    shapeOk = false;
                    break;
                }
                cp = (cp << 6) | (c & 0x3F);
            }
            if (!shapeOk || cp < MIN_VALUE[extra]) {
                throw malformed(seen, 1, src);
            }
            seen.appendCodePoint(cp);
            i += extra + 1;
        }
    }

    // -------------------------------------------------------------------- 解码

    /** 把整段字节序列解成码点表；开头的 BOM 已被剥离。 */
    public static int[] decode(byte[] src) {
        if (src == null) {
            throw new IllegalArgumentException("src 不能为 null");
        }
        List<Integer> out = new ArrayList<>();
        int i = skipLeadingBom(src);
        while (i < src.length) {
            int b = src[i] & 0xFF;
            if (b < 0x80) {
                out.add(b);
                i += 1;
                continue;
            }
            if (b < 0xC0) {
                out.add(REPLACEMENT);
                i += 1;
                continue;
            }
            if (b >= 0xF5) {
                out.add(REPLACEMENT);
                i += 1;
                continue;
            }
            int extra = (b < 0xE0) ? 1 : (b < 0xF0) ? 2 : 3;
            if (i + extra >= src.length) {
                out.add(REPLACEMENT);
                i += 1;
                continue;
            }
            int cp = b & (0x7F >> extra);
            boolean shapeOk = true;
            for (int k = 1; k <= extra; k++) {
                int c = src[i + k] & 0xFF;
                if (!Bytes.isContinuation(c)) {
                    shapeOk = false;
                    break;
                }
                cp = (cp << 6) | (c & 0x3F);
            }
            if (!shapeOk || cp < MIN_VALUE[extra]) {
                out.add(b);
                i += 1;
                continue;
            }
            out.add(cp);
            i += extra + 1;
        }
        int[] arr = new int[out.size()];
        for (int k = 0; k < arr.length; k++) {
            arr[k] = out.get(k);
        }
        return arr;
    }

    // -------------------------------------------------------------------- 文本

    /** 把码点表还原成文本。 */
    public static String toText(int[] codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int cp : codePoints) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** 把一个码点写成 {@code U+XXXX} 形式。 */
    public static String codePointName(int cp) {
        String h = Integer.toHexString(cp).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("U+");
        for (int k = h.length(); k < 4; k++) {
            sb.append('0');
        }
        return sb.append(h).toString();
    }

    // -------------------------------------------------------------------- 内部

    /** 跳过开头的 BOM（EF BB BF），返回第一个有效字节的下标。 */
    private static int skipLeadingBom(byte[] src) {
        if (src.length >= 3
                && (src[0] & 0xFF) == 0xEF
                && (src[1] & 0xFF) == 0xBB
                && (src[2] & 0xFF) == 0xBF) {
            return 3;
        }
        return 0;
    }

    /** 构造异常：位置取自「已解出文本」的长度。 */
    private static MalformedUtf8 malformed(StringBuilder seen, int rule, byte[] src) {
        return new MalformedUtf8(seen.length(), rule, Bytes.hex(src));
    }
}
