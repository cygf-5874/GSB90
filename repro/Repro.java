package repro;

import utf8cut.Bytes;
import utf8cut.Cut;
import utf8cut.Utf8;

/**
 * 下游抓下来的最小复现。
 *
 * <p>不带参数时跑固定的两条：切分结果的合法性、非法输入的返回值。
 *
 * <p>带参数时按十六进制字节跑单条用例（输出为纯 ASCII，便于逐字节比对）：
 * <pre>
 *   java -cp out repro.Repro cut &lt;hex&gt; [maxBytes]
 *   java -cp out repro.Repro decode &lt;hex&gt;
 *   java -cp out repro.Repro validate &lt;hex&gt;
 * </pre>
 */
public final class Repro {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.exit(fixedCases());
        }
        String sub = args[0];
        if ("cut".equals(sub)) {
            System.exit(doCut(args));
        }
        if ("decode".equals(sub)) {
            System.exit(doDecode(args));
        }
        if ("validate".equals(sub)) {
            System.exit(doValidate(args));
        }
        System.out.println("unknown subcommand: " + sub);
        System.exit(2);
    }

    // ------------------------------------------------------------ 固定两条

    private static int fixedCases() {
        byte[] emoji = parseHex("f0 9f 98 80");
        byte[] piece = Cut.cut(emoji, 3);
        System.out.println("切出来的 " + piece.length + " 个字节"
                + (isLegal(piece) ? "是" : "不是") + "合法 UTF-8");

        byte[] broken = parseHex("e4 b8");
        String text = Utf8.toText(Utf8.decode(broken));
        System.out.println("该报错的地方返回了 "
                + (text.indexOf(Utf8.REPLACEMENT) >= 0 ? "?" : text.isEmpty() ? "(empty)" : text));
        return 1;
    }

    // ------------------------------------------------------------ 单条用例

    private static int doCut(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: repro.Repro cut <hex> [maxBytes]");
            return 2;
        }
        byte[] data = parseHex(args[1]);
        int maxBytes = args.length >= 3 ? Integer.parseInt(args[2]) : data.length;
        String head = "cut(" + show(data) + ", maxBytes=" + maxBytes + ")";
        try {
            byte[] piece = Cut.cut(data, maxBytes);
            System.out.println(head + " = " + (piece.length == 0 ? "(empty)" : Bytes.hex(piece)));
            System.out.println("bytes = " + piece.length);
            System.out.println("valid utf8 = " + (isLegal(piece) ? "yes" : "no"));
            return 0;
        } catch (IllegalArgumentException e) {
            System.out.println(head + " threw IllegalArgumentException");
            return 0;
        } catch (Utf8.MalformedUtf8 e) {
            System.out.println(head + " threw MalformedUtf8");
            System.out.println("index = " + e.index());
            System.out.println("rule = " + e.rule());
            return 0;
        }
    }

    private static int doDecode(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: repro.Repro decode <hex>");
            return 2;
        }
        byte[] data = parseHex(args[1]);
        String head = "decode(" + show(data) + ")";
        try {
            int[] cps = Utf8.decode(data);
            StringBuilder names = new StringBuilder();
            for (int cp : cps) {
                if (names.length() > 0) {
                    names.append(' ');
                }
                names.append(Utf8.codePointName(cp));
            }
            System.out.println(head + " = " + (names.length() == 0 ? "(none)" : names.toString()));
            System.out.println("count = " + cps.length);
            return 0;
        } catch (Utf8.MalformedUtf8 e) {
            System.out.println(head + " threw MalformedUtf8");
            System.out.println("index = " + e.index());
            System.out.println("rule = " + e.rule());
            return 0;
        }
    }

    private static int doValidate(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: repro.Repro validate <hex>");
            return 2;
        }
        byte[] data = parseHex(args[1]);
        String head = "validate(" + show(data) + ")";
        try {
            Utf8.validate(data);
            System.out.println(head + " = ok");
            return 0;
        } catch (Utf8.MalformedUtf8 e) {
            System.out.println(head + " threw MalformedUtf8");
            System.out.println("index = " + e.index());
            System.out.println("rule = " + e.rule());
            return 0;
        }
    }

    // -------------------------------------------------------------- 小工具

    private static boolean isLegal(byte[] data) {
        try {
            Utf8.validate(data);
            return true;
        } catch (Utf8.MalformedUtf8 e) {
            return false;
        }
    }

    private static String show(byte[] data) {
        return data.length == 0 ? "(none)" : Bytes.hex(data);
    }

    private static byte[] parseHex(String text) {
        String s = text.replace(" ", "").replace("\t", "");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("bad hex length: " + text);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
