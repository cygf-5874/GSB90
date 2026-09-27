package utf8cut;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * utf8cut 的既有用例：只覆盖 ASCII 与「切点恰好落在码点边界上」的路径。
 *
 * <p>不用 JUnit，静态方法 + main 聚合自跑；全部通过时打印 {@code 14/14 passed}。
 */
public final class CutTest {

    private static int passed;
    private static int total;

    public static void main(String[] args) {
        emptySourceReturnsEmpty();
        zeroMaxBytesReturnsEmpty();
        nullSourceThrowsIae();
        negativeMaxBytesThrowsIae();
        asciiShortensToBoundary();
        asciiExactBoundary();
        asciiBeyondLength();
        twoByteCodePointExact();
        threeByteStopsBeforeNextCodePoint();
        threeByteCodePointExact();
        asciiAndCjkMixed();
        fourByteCodePointExact();
        leadingBomIsKept();
        decodeThreeByteCodePoint();

        System.out.println(passed + "/" + total + " passed");
        if (passed != total) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    private static void emptySourceReturnsEmpty() {
        expectBytes("empty-source-returns-empty", new byte[0], Cut.cut(new byte[0], 5));
    }

    private static void zeroMaxBytesReturnsEmpty() {
        expectBytes("zero-max-bytes-returns-empty", new byte[0], Cut.cut(utf8("abc"), 0));
    }

    private static void nullSourceThrowsIae() {
        expectIae("null-source-throws", () -> Cut.cut(null, 5));
    }

    private static void negativeMaxBytesThrowsIae() {
        expectIae("negative-max-bytes-throws", () -> Cut.cut(utf8("abc"), -1));
    }

    private static void asciiShortensToBoundary() {
        expectBytes("ascii-shortens-to-boundary", utf8("ab"), Cut.cut(utf8("abc"), 2));
    }

    private static void asciiExactBoundary() {
        expectBytes("ascii-exact-boundary", utf8("abc"), Cut.cut(utf8("abc"), 3));
    }

    private static void asciiBeyondLength() {
        expectBytes("ascii-beyond-length", utf8("abc"), Cut.cut(utf8("abc"), 99));
    }

    private static void twoByteCodePointExact() {
        expectBytes("two-byte-code-point-exact", utf8("\u00e9"), Cut.cut(utf8("\u00e9"), 2));
    }

    private static void threeByteStopsBeforeNextCodePoint() {
        expectBytes("three-byte-stops-before-next", utf8("\u4e2d"), Cut.cut(utf8("\u4e2d\u6587"), 3));
    }

    private static void threeByteCodePointExact() {
        expectBytes("three-byte-code-point-exact", utf8("\u4e2d\u6587"), Cut.cut(utf8("\u4e2d\u6587"), 6));
    }

    private static void asciiAndCjkMixed() {
        expectBytes("ascii-and-cjk-mixed", utf8("a\u4e2d"), Cut.cut(utf8("a\u4e2db"), 4));
    }

    private static void fourByteCodePointExact() {
        expectBytes("four-byte-code-point-exact", utf8("\uD83D\uDE00"), Cut.cut(utf8("\uD83D\uDE00"), 4));
    }

    private static void leadingBomIsKept() {
        expectBytes("leading-bom-is-kept", utf8("\uFEFFA"), Cut.cut(utf8("\uFEFFA"), 4));
    }

    private static void decodeThreeByteCodePoint() {
        expectCodePoints("decode-three-byte-code-point",
                new int[] {0x4E2D}, Utf8.decode(utf8("\u4e2d")));
    }

    // -------------------------------------------------------------- 断言

    private static void expectBytes(String name, byte[] expected, byte[] actual) {
        total++;
        if (Arrays.equals(expected, actual)) {
            passed++;
            return;
        }
        System.out.println("FAIL " + name + "  expected=" + Bytes.hex(expected) + " actual=" + Bytes.hex(actual));
    }

    private static void expectCodePoints(String name, int[] expected, int[] actual) {
        total++;
        if (Arrays.equals(expected, actual)) {
            passed++;
            return;
        }
        System.out.println("FAIL " + name + "  expected=" + names(expected) + " actual=" + names(actual));
    }

    private static void expectIae(String name, Runnable body) {
        total++;
        try {
            body.run();
            System.out.println("FAIL " + name + "  expected=IllegalArgumentException actual=(no exception)");
        } catch (IllegalArgumentException e) {
            passed++;
        } catch (RuntimeException e) {
            System.out.println("FAIL " + name + "  expected=IllegalArgumentException actual=" + e.getClass().getName());
        }
    }

    private static String names(int[] codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int cp : codePoints) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Utf8.codePointName(cp));
        }
        return sb.toString();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
