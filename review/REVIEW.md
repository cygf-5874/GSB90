# utf8cut 审查报告：UTF-8 边界切分违反「对外保证」的结论

本报告只记录「违反了哪条保证」与可复现证据，不含修复方案。所有命令均在仓库根执行，
输出为纯 ASCII（十六进制），不依赖默认字符集。

## 结论 1：cut 从四字节码点中间切断，返回的片段不是合法 UTF-8（下游报错根因）

`Cut.cut` 只回退「结尾恰好是起始字节」的情况。对 4 字节码点 `f0 9f 98 80`（U+1F600），
`maxBytes=3` 时结尾字节是续字节 `0x98`，回退循环一次都不触发，直接返回 `f0 9f 98`：
这是一个被截断的 4 字节序列，不是合法 UTF-8，也不是「不超过 3 字节的最长合法前缀」
（最长合法前缀应为空）。同样的路径在 3 字节码点上也会留下悬空续字节，例如
`cut(e4 b8 ad e4 b8 ad, 5)` 返回 5 字节的 `e4 b8 ad e4 b8`（`valid utf8 = no`）。

- 文件与行号：`src/main/java/utf8cut/Cut.java:32`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro cut f09f9880 3
  ```
- 实际输出：
  ```text
  cut(f0 9f 98 80, maxBytes=3) = f0 9f 98
  bytes = 3
  valid utf8 = no
  ```
- 违反的保证：第 1 条、第 2 条、第 6 条

## 结论 2：最短形式校验阈值恒为 0，overlong 编码 c0 af 被判合法

`MIN_VALUE` 四个元素全是 0，`cp < MIN_VALUE[extra]` 永远不成立，最短形式（禁止 overlong）
实际没有被校验。`c0 af` 是 U+002F 的 2 字节非法最短形式（起始字节 `0xC0` 本就属于保证中
明列的 `0xC0/0xC1` 起始），严格校验却返回 ok；`decode` 还把它解成 U+002F
（`decode(c0 af) = U+002F`）。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:22`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate c0af
  ```
- 实际输出：
  ```text
  validate(c0 af) = ok
  ```
- 违反的保证：第 1 条、第 3 条

## 结论 3：缺少码点上界与代理区检查，代理编码 ed a0 80 被判合法

长度分派后只检查了续字节形状和 `MIN_VALUE`，没有检查码点是否落在代理区
（U+D800..U+DFFF）或超过 U+10FFFF。`ed a0 80` 编码的是高代理 U+D800，不是合法 Unicode
标量值，严格校验却返回 ok。同类缺陷下，超过 U+10FFFF 的 `f4 90 80 80` 甚至不会抛
`MalformedUtf8`，而是在 `seen.appendCodePoint` 处抛出非约定的
`IllegalArgumentException: Not a valid Unicode code point: 0x110000`。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:92`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate eda080
  ```
- 实际输出：
  ```text
  validate(ed a0 80) = ok
  ```
- 违反的保证：第 1 条、第 3 条

## 结论 4：decode 遇到孤立续字节时静默输出 U+FFFD，不抛 MalformedUtf8

保证要求孤立续字节必须抛 `MalformedUtf8`，不得用替换字符吞掉；但 `decode` 对单独的
续字节 `0x80` 直接产出一个 U+FFFD 码点并正常返回。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:117`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro decode 80
  ```
- 实际输出：
  ```text
  decode(80) = U+FFFD
  count = 1
  ```
- 违反的保证：第 3 条

## 结论 5：decode 遇到截断的多字节序列时静默输出 U+FFFD，不抛 MalformedUtf8

`e4 b8` 是一个被截断的 3 字节序列（缺少最后一个续字节），属于保证明列的非法输入；
`decode` 不抛异常，而是产出两个 U+FFFD（每个残字节一个替换字符）后正常返回。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:128`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro decode e4b8
  ```
- 实际输出：
  ```text
  decode(e4 b8) = U+FFFD U+FFFD
  count = 2
  ```
- 违反的保证：第 3 条

## 结论 6：decode 对形状非法的序列不报错，反而把起始字节伪造为码点输出

`c3 41` 中 `0xC3` 要求后随一个续字节，实际后随 ASCII 字节 `0x41`，形状非法。
`decode` 不抛 `MalformedUtf8`，而是把起始字节的值 `0xC3` 直接当作码点 U+00C3 输出，
再把 `0x41` 当作 U+0041，凭空造出两个码点。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:143`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro decode c341
  ```
- 实际输出：
  ```text
  decode(c3 41) = U+00C3 U+0041
  count = 2
  ```
- 违反的保证：第 3 条

## 结论 7：开头之外的 BOM 不报错，BOM 可以在序列中重复出现

`skipLeadingBom` 只负责跳过开头的 `EF BB BF`，主循环对后续出现的 BOM 没有任何检查。
输入 `EF BB BF 41 EF BB BF`（开头一个 BOM、中间又出现一个 BOM）被严格校验判为 ok，
违反「BOM 只允许出现在开头且只允许一次」。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:181`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate efbbbf41efbbbf
  ```
- 实际输出：
  ```text
  validate(ef bb bf 41 ef bb bf) = ok
  ```
- 违反的保证：第 4 条

## 结论 8：错误位置报的是 UTF-16 code unit 长度，不是字节下标

`malformed` 用「已解出文本」`seen.length()` 作为出错下标，而 `seen` 是按
`append`/`appendCodePoint` 累积的 Java 字符串，长度是 UTF-16 code unit 数。对
`c3 a9 80`，孤立续字节 `0x80` 的真实字节下标是 2，异常却报告 index=1；
错误一旦发生多字节码点之后，报告下标就会与字节偏移不符。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:193`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate c3a980
  ```
- 实际输出：
  ```text
  validate(c3 a9 80) threw MalformedUtf8
  index = 1
  rule = 1
  ```
- 违反的保证：第 5 条

## 结论 9：孤立续字节被错报为违反第 1 条，规则号与「对外保证」编号对不上

孤立续字节是保证第 3 条明列的非法情形（第 1 条约束的是已成形编码的最短形式与续字节
取值范围），但该校验分支把 rule 固定写成 1。输入 `41 80`（ASCII 后跟一个孤立续字节，
字节下标恰好为 1，可排除结论 8 的下标偏差）上报 `rule = 1`，违反第 5 条要求的
「给出违反的规则号」。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:73`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate 4180
  ```
- 实际输出：
  ```text
  validate(41 80) threw MalformedUtf8
  index = 1
  rule = 1
  ```
- 违反的保证：第 5 条

## 源码基线

`src/` 与 `repro/` 下全部文件的 SHA-256（`sha256sum`，审查期间源码未改动）：

```text
6d119fe3ba83a08d59c6e70061bc99af5fe8c17a8149ec85d90b43fa38be20a1  repro/Repro.java
1e605e6143d94a28c7ee6cdbce1ebe8e94bc9b2fc04acd7a9c519efde34f407b  src/main/java/utf8cut/Bytes.java
e6d31dc9bfdabb94c3d57f8bd250cd9000906960943d0f472953517187820acf  src/main/java/utf8cut/Cut.java
e3753c8330df415a3e8b229b391d974811c2e8d3a31c4a1bf64bfae8064d26cf  src/main/java/utf8cut/Utf8.java
5d7e20d0e5416e133d8365937fc8c775609f42f12bfdd8fd876a9f34fc0406f7  src/test/java/utf8cut/CutTest.java
```
