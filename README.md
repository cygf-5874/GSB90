# utf8cut —— UTF-8 字节切片

Java 17，只用 JDK 标准库（`javac` / `java`），无第三方依赖，不用 Maven / Gradle。

按「不超过 `maxBytes` 的最长合法 UTF-8 前缀」切分字节序列：输入是字节、返回值也是字节，
切点必须落在一个完整码点的边界上。

## 目录

```
src/main/java/utf8cut/Bytes.java    字节级小工具（十六进制转写、区间拷贝、字节分类）
src/main/java/utf8cut/Utf8.java     UTF-8 解码与校验
src/main/java/utf8cut/Cut.java      按字节上限切分
src/test/java/utf8cut/CutTest.java  既有用例（14 个）
repro/Repro.java                    下游抓下来的最小复现
review/CHECKLIST.md                 审查自查清单（模板）
review/REVIEW.md                    审查结论（待填模板）
check/Checker.java                  固定验收程序（勿改）
scripts/build.sh                    构建
scripts/check.sh                    自检与验收
```

## 构建与自检

```bash
bash scripts/build.sh     # 编译 src/main/java 与 repro/ -> out/
bash scripts/check.sh     # 构建 + 跑既有用例 + 跑固定件
```

`scripts/check.sh` 支持 `-list` 看场景名、`--only <组名>[,<组名>...]` 只跑某一组。

## 复现工具

`repro/Repro.java` 是下游抓下来的最小输入：

```bash
javac -cp out -d out repro/Repro.java
java -cp out repro.Repro                       # 打印两条现象，退出码 1
```

带参数时按十六进制字节跑单条用例，输出为纯 ASCII，便于逐字节比对：

```bash
java -cp out repro.Repro cut <hex> [maxBytes]  # 切分结果 + 长度 + 合法性
java -cp out repro.Repro decode <hex>          # 码点表
java -cp out repro.Repro validate <hex>        # 严格校验
```

## 对外保证

1. `cut(byte[] src, int maxBytes)` 返回的字节切片必须是合法 UTF-8：每一条编码都满足最短形式
   （禁止 overlong），续字节必须落在 `0x80..0xBF`；
2. 切片长度必须 ≤ `maxBytes`，且是「不超过 `maxBytes` 的最长合法前缀」——
   若在第 `k` 个码点处截断会切坏，就必须回退到第 `k-1` 个码点结束；
3. 遇到非法输入（overlong、孤立续字节、`0xC0/0xC1` 起始、`0xF5..0xFF` 起始、截断的多字节序列）
   必须抛 `MalformedUtf8`，不得用替换字符静默吞掉；
4. BOM（`EF BB BF`）只允许出现在开头且只允许出现一次；出现在别处一律算非法输入；
5. 偏移量一律是字节偏移，与 `String.length()`（UTF-16 code unit 数）无关；
   报告错误时必须给出发生错误的字节下标与违反的规则号；
6. 4 字节码点（U+10000 以上的代理对）与 2/3 字节码点必须同等正确；不得在代理对中间切开；
7. `maxBytes = 0` → 返回空数组；`maxBytes < 0` → `IllegalArgumentException`；
   `src` 为空 → 空数组；`src == null` → `IllegalArgumentException`；
8. 纯函数：不依赖默认字符集与 Locale，不用 `new String(bytes)` 做校验，
   不用 `CharsetDecoder` 的 REPLACE 动作。

## 审查交付物

审查结论写在 `review/REVIEW.md`（仓库里给的是**待填模板**，只有章节骨架）。

- 每条结论用二级标题 `## 结论 N：<一句话>` 起头；结论条数不少于 6 条。
- 每条结论必须有四要素，缺一条就不算结论：
  - `文件与行号`：`<相对路径>:<行号>`，路径相对仓库根；
  - `复现命令`：一节 bash 代码块，固定两行 —— 一行 `javac`、一行 `java`；
    两行都用**单条目** classpath（`-cp out`），不要依赖平台相关的路径分隔符；
    两行会被逐字执行（不经过 shell），所以别用 `&&`、管道或通配符；
    源码里有非 ASCII 注释，`javac` 那行请照 `scripts/build.sh` 带上 `-encoding UTF-8`，
    否则在默认编码不是 UTF-8 的机器上会直接编译失败；
  - `实际输出`：一节 text 代码块，逐行照抄上面 `java` 那行的 stdout；
  - `违反的保证`：写成 `第 N 条`（可多条），`N` 取「对外保证」的编号 1~8。
- 全部结论至少覆盖 6 条**不同**的保证，且不得有两条结论指向同一个 `文件:行号`。
- 另需一节 `## 源码基线`：用 `sha256sum` 列出 `src/` 与 `repro/` 下的**全部**文件，
  每行形如 `<sha256>  <相对路径>`。固定件会拿它与硬编码基线一起验「源码未被改动」。

固定件（`check/Checker.java`）共 10 个场景：`format` 3 + `evidence` 3 + `coverage` 2 + `integrity` 2。
`evidence` 组会**真去执行**你写在 `REVIEW.md` 里的命令，并与你写的「实际输出」逐行比对。
