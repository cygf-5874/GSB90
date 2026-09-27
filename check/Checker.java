import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * utf8cut 的固定验收程序（勿改）。
 *
 * <p>10 个场景：format 3（章节齐全 / 四要素齐全 / 结论条数 ≥ 6）+
 * evidence 3（引用的文件与类存在 / 命令确实可执行 / 输出与条目号与所写一致）+
 * coverage 2（至少 6 条不同的保证 / 没有两条结论指向同一处代码）+
 * integrity 2（src/ 与 repro/ 的 SHA-256 基线未变 / 没有新增或删除源码文件）。
 *
 * <p>每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。evidence 组会真去执行
 * {@code review/REVIEW.md} 里写的 {@code javac} / {@code java} 命令，再与所写输出逐行比对。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 普通场景的看门狗（秒）。 */
    private static final long DEFAULT_WATCHDOG_SECONDS = 60L;

    /** 需要真跑外部命令的场景给更长的看门狗（秒）。 */
    private static final long COMMAND_WATCHDOG_SECONDS = 300L;

    /** 单条外部命令的看门狗（毫秒）。 */
    private static final long COMMAND_TIMEOUT_MS = 60_000L;

    /** 审查报告的位置（相对仓库根）。 */
    private static final Path REPORT = Paths.get("review", "REVIEW.md");

    /** src/ 与 repro/ 的 SHA-256 基线（写死；改动源码即判不通过）。 */
    private static final Map<String, String> BASELINE = baseline();

    // ==================================================================== 入口

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final long watchdogSeconds;
        final Body body;

        Scenario(String name, long watchdogSeconds, Body body) {
            this.name = name;
            this.watchdogSeconds = watchdogSeconds;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        String scenarioOnly = null;
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    /** 在独立子进程里跑单个场景，把子进程给出的 PASS/FAIL 行原样返回。 */
    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(s.watchdogSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + s.watchdogSeconds + " 秒内给出 PASS/FAIL 实际="
                    + s.watchdogSeconds + " 秒看门狗超时（子进程已强杀）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=退出码 "
                    + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
        }
        return line;
    }

    /** 子进程模式：就地跑一个场景，打印一行 PASS/FAIL，返回退出码。 */
    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "期望=场景正常求值 实际=检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ================================================================ 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();

        // -------------------------------------------------------- format（3）

        out.add(new Scenario("format/sections", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (!report.hasH1) {
                return "期望=报告含一个一级标题（该行以 `# ` 开头） 实际=未找到";
            }
            if (!report.hasBaselineSection) {
                return "期望=报告含 `## 源码基线` 小节 实际=未找到";
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条 `## 结论 N：…` 小节 实际=0 条";
            }
            for (Conclusion c : report.conclusions) {
                if (c.bodyTo <= c.bodyFrom) {
                    return "期望=结论 " + c.number + " 的标题下有内容 实际=该小节为空";
                }
            }
            return null;
        }));

        out.add(new Scenario("format/four-parts", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条结论 实际=0 条";
            }
            StringBuilder missing = new StringBuilder();
            for (Conclusion c : report.conclusions) {
                List<String> gaps = new ArrayList<>();
                for (String label : new String[] {"文件与行号", "复现命令", "实际输出", "违反的保证"}) {
                    if (c.field(label).isEmpty()) {
                        gaps.add(label);
                    }
                }
                if (!gaps.isEmpty()) {
                    if (missing.length() > 0) {
                        missing.append("；");
                    }
                    missing.append("结论 ").append(c.number).append(" 缺 ").append(String.join("、", gaps));
                }
            }
            if (missing.length() > 0) {
                return "期望=每条结论四要素齐全 实际=" + missing;
            }
            return null;
        }));

        out.add(new Scenario("format/count", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            int n = report.conclusions.size();
            if (n < 6) {
                return "期望=结论条数 ≥ 6 实际=" + n;
            }
            return null;
        }));

        // ------------------------------------------------------ evidence（3）

        out.add(new Scenario("evidence/files-exist", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条结论 实际=0 条";
            }
            for (Conclusion c : report.conclusions) {
                List<String> refs = fileLineRefs(c.field("文件与行号"));
                if (refs.isEmpty()) {
                    return "期望=结论 " + c.number + " 的「文件与行号」含 `<路径>.java:<行号>` 实际="
                            + oneLine(c.field("文件与行号"));
                }
                for (String ref : refs) {
                    String path = ref.substring(0, ref.lastIndexOf(':'));
                    int line = Integer.parseInt(ref.substring(ref.lastIndexOf(':') + 1));
                    Path p = Paths.get(path);
                    if (!Files.isRegularFile(p)) {
                        return "期望=结论 " + c.number + " 引用的文件存在 实际=找不到 " + path;
                    }
                    int total = Files.readAllLines(p, StandardCharsets.UTF_8).size();
                    if (line < 1 || line > total) {
                        return "期望=结论 " + c.number + " 引用的行号落在 1.." + total + " 实际=" + path + ":" + line;
                    }
                }
                String commandBlock = c.field("复现命令");
                List<String> cmds = commandLines(commandBlock);
                if (!hasTool(cmds, "javac")) {
                    return "期望=结论 " + c.number + " 的复现命令含一行 `javac` 实际=" + oneLine(commandBlock);
                }
                if (!hasTool(cmds, "java")) {
                    return "期望=结论 " + c.number + " 的复现命令含一行 `java` 实际=" + oneLine(commandBlock);
                }
                for (String src : javaFileRefs(commandBlock)) {
                    if (!Files.isRegularFile(Paths.get(src))) {
                        return "期望=结论 " + c.number + " 命令里引用的源文件存在 实际=找不到 " + src;
                    }
                }
                for (String cmd : cmds) {
                    if (!cmd.startsWith("java ")) {
                        continue;
                    }
                    String main = mainClassOf(cmd);
                    if (main == null) {
                        return "期望=结论 " + c.number + " 的 `java` 行能用 `-cp <路径> <主类>` 解析 实际=" + oneLine(cmd);
                    }
                    Path cls = Paths.get("out", main.replace('.', '/') + ".class");
                    if (!Files.isRegularFile(cls)) {
                        return "期望=结论 " + c.number + " 的主类已编译到 out/ 实际=找不到 " + cls.toString().replace('\\', '/');
                    }
                }
            }
            return null;
        }));

        out.add(new Scenario("evidence/commands-run", COMMAND_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条结论 实际=0 条";
            }
            Map<String, RunResult> cache = new LinkedHashMap<>();
            for (Conclusion c : report.conclusions) {
                List<String> cmds = commandLines(c.field("复现命令"));
                if (cmds.isEmpty()) {
                    return "期望=结论 " + c.number + " 有可执行命令 实际=没有 `javac`/`java` 行";
                }
                for (String cmd : cmds) {
                    RunResult r = cache.get(cmd);
                    if (r == null) {
                        r = runCommand(cmd);
                        cache.put(cmd, r);
                    }
                    if (r.timeout) {
                        return "期望=`" + oneLine(cmd) + "` 在 " + (COMMAND_TIMEOUT_MS / 1000L)
                                + " 秒内结束 实际=超时（子进程已强杀）";
                    }
                    if (r.exit < 0) {
                        return "期望=`" + oneLine(cmd) + "` 能启动 实际=" + oneLine(r.output);
                    }
                    String crash = crashMarker(r.output);
                    if (crash != null) {
                        return "期望=`" + oneLine(cmd) + "` 正常执行 实际=输出含「" + crash + "」：" + oneLine(r.output);
                    }
                    if (cmd.startsWith("javac") && r.exit != 0) {
                        return "期望=`" + oneLine(cmd) + "` 退出码 0"
                                + "（源码含非 ASCII 注释，`javac` 行需照 scripts/build.sh 带 `-encoding UTF-8`）"
                                + " 实际=" + r.exit + "，输出=" + oneLine(r.output);
                    }
                    if (cmd.startsWith("java ") && r.exit != 0 && r.exit != 1) {
                        return "期望=`" + oneLine(cmd) + "` 退出码 0（复现工具允许 1） 实际=" + r.exit
                                + "，输出=" + oneLine(r.output);
                    }
                }
            }
            return null;
        }));

        out.add(new Scenario("evidence/output-matches", COMMAND_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条结论 实际=0 条";
            }
            Map<String, RunResult> cache = new LinkedHashMap<>();
            for (Conclusion c : report.conclusions) {
                String declared = c.field("实际输出");
                if (declared.isEmpty()) {
                    return "期望=结论 " + c.number + " 写了「实际输出」 实际=为空";
                }
                List<Integer> rules = guaranteeNumbers(c.field("违反的保证"));
                if (rules.isEmpty()) {
                    return "期望=结论 " + c.number + " 的「违反的保证」写成 `第 N 条` 实际="
                            + oneLine(c.field("违反的保证"));
                }
                String javaCmd = lastJavaLine(commandLines(c.field("复现命令")));
                if (javaCmd == null) {
                    return "期望=结论 " + c.number + " 有 `java` 命令行 实际=没有";
                }
                RunResult r = cache.get(javaCmd);
                if (r == null) {
                    r = runCommand(javaCmd);
                    cache.put(javaCmd, r);
                }
                if (r.timeout) {
                    return "期望=`" + oneLine(javaCmd) + "` 在 " + (COMMAND_TIMEOUT_MS / 1000L)
                            + " 秒内结束 实际=超时（子进程已强杀）";
                }
                if (r.exit < 0) {
                    return "期望=`" + oneLine(javaCmd) + "` 能启动 实际=" + oneLine(r.output);
                }
                List<String> want = normalize(declared);
                List<String> got = normalize(r.output);
                if (!want.equals(got)) {
                    return "期望=结论 " + c.number + " 的「实际输出」与真实输出逐行一致 实际=声明=["
                            + String.join(" | ", want) + "] 真实=[" + String.join(" | ", got) + "]";
                }
            }
            return null;
        }));

        // ------------------------------------------------------ coverage（2）

        out.add(new Scenario("coverage/guarantees", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条结论 实际=0 条";
            }
            Set<Integer> all = new TreeSet<>();
            for (Conclusion c : report.conclusions) {
                List<Integer> rules = guaranteeNumbers(c.field("违反的保证"));
                if (rules.isEmpty()) {
                    return "期望=结论 " + c.number + " 的「违反的保证」写成 `第 N 条` 实际="
                            + oneLine(c.field("违反的保证"));
                }
                for (int n : rules) {
                    if (n < 1 || n > 8) {
                        return "期望=保证条目号落在 1..8 实际=结论 " + c.number + " 写了第 " + n + " 条";
                    }
                    all.add(n);
                }
            }
            if (all.size() < 6) {
                return "期望=全部结论至少覆盖 6 条不同的保证 实际=" + all.size() + " 条：" + all;
            }
            return null;
        }));

        out.add(new Scenario("coverage/distinct-code", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            if (report.conclusions.isEmpty()) {
                return "期望=至少 1 条结论 实际=0 条";
            }
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (Conclusion c : report.conclusions) {
                List<String> refs = fileLineRefs(c.field("文件与行号"));
                if (refs.isEmpty()) {
                    return "期望=结论 " + c.number + " 的「文件与行号」含 `<路径>.java:<行号>` 实际="
                            + oneLine(c.field("文件与行号"));
                }
                for (String ref : refs) {
                    Integer other = seen.putIfAbsent(ref, c.number);
                    if (other != null && other != c.number) {
                        return "期望=没有两条结论指向同一处代码 实际=" + ref + " 同时出现在结论 " + other
                                + " 与结论 " + c.number;
                    }
                }
            }
            return null;
        }));

        // ----------------------------------------------------- integrity（2）

        out.add(new Scenario("integrity/hashes", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            Map<String, String> declared = declaredBaseline(report);
            if (declared.isEmpty()) {
                return "期望=「## 源码基线」列出 `<sha256>  <相对路径>` 实际=一条都没有";
            }
            Set<String> want = new TreeSet<>(BASELINE.keySet());
            Set<String> got = new TreeSet<>(declared.keySet());
            if (!want.equals(got)) {
                Set<String> missing = new TreeSet<>(want);
                missing.removeAll(got);
                Set<String> extra = new TreeSet<>(got);
                extra.removeAll(want);
                return "期望=源码基线的文件清单与仓库基线一致 实际=缺 " + missing + "，多 " + extra;
            }
            for (Map.Entry<String, String> e : BASELINE.entrySet()) {
                String path = e.getKey();
                Path p = Paths.get(path);
                if (!Files.isRegularFile(p)) {
                    return "期望=" + path + " 存在 实际=文件缺失";
                }
                String actual = sha256(p);
                if (!actual.equals(e.getValue())) {
                    return "期望=src/ 与 repro/ 未被改动 实际=" + path + " 的 SHA-256=" + actual
                            + "，基线=" + e.getValue();
                }
                String claim = declared.get(path);
                if (!claim.equals(e.getValue())) {
                    return "期望=「源码基线」里 " + path + " 的 SHA-256 与仓库基线一致 实际=" + claim;
                }
            }
            return null;
        }));

        out.add(new Scenario("integrity/tree", DEFAULT_WATCHDOG_SECONDS, () -> {
            List<String> problems = new ArrayList<>();
            Report report = readReport(problems);
            if (!problems.isEmpty()) {
                return "期望=review/REVIEW.md 存在且可读 实际=" + String.join("；", problems);
            }
            Set<String> actual = new TreeSet<>(actualSourceFiles());
            Set<String> want = new TreeSet<>(BASELINE.keySet());
            if (!actual.equals(want)) {
                Set<String> added = new TreeSet<>(actual);
                added.removeAll(want);
                Set<String> removed = new TreeSet<>(want);
                removed.removeAll(actual);
                return "期望=src/ 与 repro/ 下没有新增或删除源码文件 实际=新增 " + added + "，缺少 " + removed;
            }
            Map<String, String> declared = declaredBaseline(report);
            if (declared.isEmpty()) {
                return "期望=「## 源码基线」与仓库文件清单一致 实际=一条都没有";
            }
            Set<String> got = new TreeSet<>(declared.keySet());
            if (!got.equals(actual)) {
                return "期望=「源码基线」覆盖 src/ 与 repro/ 下全部文件 实际=清单=" + got + "，磁盘=" + actual;
            }
            for (String path : actual) {
                String h = sha256(Paths.get(path));
                if (!BASELINE.containsKey(path) || !h.equals(BASELINE.get(path))) {
                    return "期望=" + path + " 与基线一致 实际=SHA-256=" + h;
                }
            }
            return null;
        }));

        return out;
    }

    // ============================================================== 报告解析

    private static final class Conclusion {
        final Report report;
        final int number;
        final String title;
        final int headingLine;
        final int bodyFrom;
        final int bodyTo;

        Conclusion(Report report, int number, String title, int headingLine, int bodyFrom, int bodyTo) {
            this.report = report;
            this.number = number;
            this.title = title;
            this.headingLine = headingLine;
            this.bodyFrom = bodyFrom;
            this.bodyTo = bodyTo;
        }

        String field(String label) {
            return report.field(this, label);
        }
    }

    private static final class Report {
        final List<String> lines;
        final boolean[] fenced;
        final List<Conclusion> conclusions = new ArrayList<>();
        boolean hasH1;
        boolean hasBaselineSection;

        Report(List<String> lines, boolean[] fenced) {
            this.lines = lines;
            this.fenced = fenced;
        }

        /** 取结论里的字段值：先看标签行本身，再看紧随其后的围栏块或连续行。 */
        String field(Conclusion c, String label) {
            for (int i = c.bodyFrom; i < c.bodyTo; i++) {
                if (fenced[i]) {
                    continue;
                }
                String rest = afterLabel(lines.get(i), label);
                if (rest == null) {
                    continue;
                }
                if (!rest.isEmpty()) {
                    return rest;
                }
                int j = i + 1;
                while (j < c.bodyTo && lines.get(j).trim().isEmpty()) {
                    j++;
                }
                if (j < c.bodyTo && isFenceOpen(lines.get(j).trim())) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = j + 1; k < c.bodyTo; k++) {
                        if (isFenceClose(lines.get(k).trim())) {
                            break;
                        }
                        sb.append(lines.get(k)).append('\n');
                    }
                    return sb.toString().trim();
                }
                StringBuilder sb = new StringBuilder();
                for (int k = j; k < c.bodyTo && !lines.get(k).trim().isEmpty(); k++) {
                    sb.append(lines.get(k)).append('\n');
                }
                return sb.toString().trim();
            }
            return "";
        }
    }

    private static Report readReport(List<String> problems) throws IOException {
        if (!Files.isRegularFile(REPORT)) {
            problems.add("review/REVIEW.md 不存在");
            return new Report(Collections.emptyList(), new boolean[0]);
        }
        List<String> lines = Files.readAllLines(REPORT, StandardCharsets.UTF_8);
        boolean[] fenced = fenceFlags(lines);
        Report report = new Report(lines, fenced);

        Pattern heading = Pattern.compile("^\\s*##\\s*结论\\s*(\\d+)\\s*[:：]?\\s*(.*)$");
        Pattern anyHeading = Pattern.compile("^\\s*#{1,2}\\s");
        Pattern h1 = Pattern.compile("^\\s*#\\s+\\S");
        Pattern baselineHeading = Pattern.compile("^\\s*##\\s*源码基线\\s*$");

        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (fenced[i]) {
                continue;
            }
            if (h1.matcher(lines.get(i)).find()) {
                report.hasH1 = true;
            }
            if (baselineHeading.matcher(lines.get(i)).find()) {
                report.hasBaselineSection = true;
            }
            if (heading.matcher(lines.get(i)).find()) {
                heads.add(i);
            }
        }
        for (int k = 0; k < heads.size(); k++) {
            int start = heads.get(k);
            int end = lines.size();
            for (int j = start + 1; j < lines.size(); j++) {
                if (!fenced[j] && anyHeading.matcher(lines.get(j)).find()) {
                    end = j;
                    break;
                }
            }
            Matcher m = heading.matcher(lines.get(start));
            m.find();
            report.conclusions.add(new Conclusion(report, Integer.parseInt(m.group(1)),
                    m.group(2).trim(), start + 1, start + 1, end));
        }
        return report;
    }

    /** 标记每一行是否落在围栏代码块内（含围栏本身）。 */
    private static boolean[] fenceFlags(List<String> lines) {
        boolean[] out = new boolean[lines.size()];
        int open = 0;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (open == 0) {
                int ticks = fenceTicks(t);
                if (ticks >= 3) {
                    open = ticks;
                    out[i] = true;
                } else {
                    out[i] = false;
                }
                continue;
            }
            out[i] = true;
            if (isFenceClose(t) && fenceTicks(t) >= open) {
                open = 0;
            }
        }
        return out;
    }

    private static int fenceTicks(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == '`') {
            i++;
        }
        return i;
    }

    private static boolean isFenceOpen(String trimmed) {
        return fenceTicks(trimmed) >= 3;
    }

    private static boolean isFenceClose(String trimmed) {
        int n = fenceTicks(trimmed);
        return n >= 3 && trimmed.substring(n).trim().isEmpty();
    }

    /** 解析 `- 标签：值` / `- **标签**：值` 这类行；不是该标签时返回 null。 */
    private static String afterLabel(String rawLine, String label) {
        String s = rawLine.trim();
        while (s.startsWith("-") || s.startsWith("*") || s.startsWith("+") || s.startsWith(">")) {
            s = s.substring(1).trim();
        }
        if (s.startsWith("**")) {
            s = s.substring(2).trim();
        }
        if (!s.startsWith(label)) {
            return null;
        }
        String rest = s.substring(label.length()).trim();
        if (rest.startsWith("**")) {
            rest = rest.substring(2).trim();
        }
        if (rest.isEmpty()) {
            return "";
        }
        char c0 = rest.charAt(0);
        if (c0 == '：' || c0 == ':') {
            return rest.substring(1).trim();
        }
        return null;
    }

    /** 从「文件与行号」字段里取出全部 `路径.java:行号`。 */
    private static List<String> fileLineRefs(String field) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java)\\s*:\\s*(\\d+)").matcher(field);
        while (m.find()) {
            out.add(m.group(1).replace('\\', '/').replaceAll("^\\./", "") + ":" + m.group(2));
        }
        return out;
    }

    /** 从命令块里取出全部 `javac` / `java` 行。 */
    private static List<String> commandLines(String block) {
        List<String> out = new ArrayList<>();
        for (String raw : block.split("\n", -1)) {
            String l = raw.trim();
            if (l.startsWith("$")) {
                l = l.substring(1).trim();
            }
            if (l.equals("javac") || l.startsWith("javac ") || l.startsWith("java ")) {
                out.add(l);
            }
        }
        return out;
    }

    private static boolean hasTool(List<String> cmds, String tool) {
        for (String c : cmds) {
            if (c.equals(tool) || c.startsWith(tool + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String lastJavaLine(List<String> cmds) {
        String found = null;
        for (String c : cmds) {
            if (c.startsWith("java ")) {
                found = c;
            }
        }
        return found;
    }

    private static List<String> javaFileRefs(String block) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java)").matcher(block);
        while (m.find()) {
            out.add(m.group(1).replace('\\', '/').replaceAll("^\\./", ""));
        }
        return out;
    }

    /** 解析 `java … -cp <路径> <主类> …`，返回主类名。 */
    private static String mainClassOf(String javaLine) {
        List<String> tokens = tokenize(javaLine);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if ("-cp".equals(t) || "-classpath".equals(t)) {
                return i + 2 < tokens.size() ? tokens.get(i + 2) : null;
            }
            if (t.startsWith("-cp=") || t.startsWith("-classpath=")) {
                return i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            }
        }
        return null;
    }

    private static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 从「违反的保证」字段里取出全部条目号。 */
    private static List<Integer> guaranteeNumbers(String field) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("第\\s*(\\d+)\\s*条").matcher(field);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** 取「## 源码基线」一节里声明的 `<sha256>  <路径>`。 */
    private static Map<String, String> declaredBaseline(Report report) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("([0-9a-fA-F]{64})\\s+([^\\s]+)").matcher(String.join("\n", report.lines));
        while (m.find()) {
            String path = m.group(2).replace('\\', '/').trim();
            if (path.startsWith("*")) {
                path = path.substring(1);
            }
            if (path.startsWith("./")) {
                path = path.substring(2);
            }
            out.put(path, m.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ================================================================ 外部命令

    private static final class RunResult {
        final int exit;
        final String output;
        final boolean timeout;

        RunResult(int exit, String output, boolean timeout) {
            this.exit = exit;
            this.output = output;
            this.timeout = timeout;
        }
    }

    /**
     * 以 argv 方式跑一行命令，合并 stdout/stderr，带 60 秒看门狗。
     *
     * <p>不经过 shell：Windows 上把带空格的一整行塞给 {@code bash -c} 会被引号规则改写，
     * 命令反而跑不对。这里直接按引号规则切成 argv，与在终端里逐字执行等价。
     */
    private static RunResult runCommand(String commandLine) {
        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            return new RunResult(-1, "空命令", false);
        }
        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return new RunResult(-1, "子进程启动失败：" + ex, false);
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-cmd-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new RunResult(-1, "（超时）", true);
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (sink) {
            return new RunResult(proc.exitValue(), sink.toString().trim(), false);
        }
    }

    private static String crashMarker(String output) {
        for (String raw : output.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("Exception in thread")) {
                return "Exception in thread";
            }
            if (l.startsWith("error:") || l.startsWith("错误：") || l.startsWith("错误:")) {
                return "error:";
            }
            if (l.contains("Could not find or load main class")) {
                return "Could not find or load main class";
            }
            if (l.contains("找不到或无法加载主类")) {
                return "找不到或无法加载主类";
            }
        }
        return null;
    }

    // ================================================================ 小工具

    private static Map<String, String> baseline() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("repro/Repro.java", "6d119fe3ba83a08d59c6e70061bc99af5fe8c17a8149ec85d90b43fa38be20a1");
        m.put("src/main/java/utf8cut/Bytes.java", "1e605e6143d94a28c7ee6cdbce1ebe8e94bc9b2fc04acd7a9c519efde34f407b");
        m.put("src/main/java/utf8cut/Cut.java", "e6d31dc9bfdabb94c3d57f8bd250cd9000906960943d0f472953517187820acf");
        m.put("src/main/java/utf8cut/Utf8.java", "e3753c8330df415a3e8b229b391d974811c2e8d3a31c4a1bf64bfae8064d26cf");
        m.put("src/test/java/utf8cut/CutTest.java", "5d7e20d0e5416e133d8365937fc8c775609f42f12bfdd8fd876a9f34fc0406f7");
        return Collections.unmodifiableMap(m);
    }

    private static List<String> actualSourceFiles() throws IOException {
        List<String> out = new ArrayList<>();
        for (String root : new String[] {"src", "repro"}) {
            Path base = Paths.get(root);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(base)) {
                for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    out.add(p.toString().replace('\\', '/'));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    private static String sha256(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IOException(ex);
        }
        byte[] digest = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 逐行归一化：去掉行首行尾空白（围栏块常被 markdown 缩进），并裁掉首尾空行。 */
    private static List<String> normalize(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.replace("\r\n", "\n").split("\n", -1)) {
            out.add(l.trim());
        }
        while (!out.isEmpty() && out.get(0).trim().isEmpty()) {
            out.remove(0);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) {
            return "(空)";
        }
        return t.length() > 160 ? t.substring(0, 157) + "..." : t;
    }
}
