# 审查报告：utf8cut 的 UTF-8 边界切分

本报告只记录违反 README「对外保证」的结论与证据；所有输出均由 `repro/Repro.java` 实跑得到，
命令不依赖默认字符集，输入与输出均为 ASCII 十六进制，可在任意 JVM 上复现。

## 源码基线

```text
6d119fe3ba83a08d59c6e70061bc99af5fe8c17a8149ec85d90b43fa38be20a1  repro/Repro.java
1e605e6143d94a28c7ee6cdbce1ebe8e94bc9b2fc04acd7a9c519efde34f407b  src/main/java/utf8cut/Bytes.java
e6d31dc9bfdabb94c3d57f8bd250cd9000906960943d0f472953517187820acf  src/main/java/utf8cut/Cut.java
e3753c8330df415a3e8b229b391d974811c2e8d3a31c4a1bf64bfae8064d26cf  src/main/java/utf8cut/Utf8.java
5d7e20d0e5416e133d8365937fc8c775609f42f12bfdd8fd876a9f34fc0406f7  src/test/java/utf8cut/CutTest.java
```

## 结论 1：4 字节码点在续字节中段被切开，返回非法 UTF-8（下游报错根因）

回退循环只检查切点前一个字节是不是「起始字节」。`f0 9f 98 80`（U+1F600）以 maxBytes=3
切分时，切点前一个字节是续字节 `98`，循环一次都不执行，切片停在 4 字节序列的中段；
工具自检也承认该切片不是合法 UTF-8。

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

## 结论 2：cut 不校验输入合法性，非法输入被原样切回且不抛异常

孤立续字节 `80` 属于保证 3 列举的非法输入，但 cut 既不抛 `MalformedUtf8`，
返回的切片 `80` 自身也不是合法 UTF-8。

- 文件与行号：`src/main/java/utf8cut/Cut.java:35`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro cut 80 1
  ```
- 实际输出：
  ```text
  cut(80, maxBytes=1) = 80
  bytes = 1
  valid utf8 = no
  ```
- 违反的保证：第 1 条、第 3 条

## 结论 3：isContinuation 把 0xC0..0xFF 都当成续字节，非法序列通过严格校验

`e4 c0 ad` 中第二个字节 `c0` 不是合法续字节（合法续字节只能是 0x80..0xBF），
但严格校验 `validate` 对整段输入返回 ok。

- 文件与行号：`src/main/java/utf8cut/Bytes.java:47`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate e4c0ad
  ```
- 实际输出：
  ```text
  validate(e4 c0 ad) = ok
  ```
- 违反的保证：第 1 条、第 3 条

## 结论 4：最短形式校验失效，overlong 编码 C0 AF 被判为合法

`c0 af` 是 U+002F 的两字节 overlong 编码（且以 0xC0 起始），严格校验应拒绝，
实际返回 ok。

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

## 结论 5：错误位置按已解出文本长度计数，报告的不是字节偏移

输入 `f0 9f 98 80 e4 b8` 中被截断的 3 字节序列起始字节位于字节下标 4，
异常却报告 index=2（恰好是前面 4 字节码点在 UTF-16 下的长度），偏移量口径与字节偏移不符。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:193`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate f09f9880e4b8
  ```
- 实际输出：
  ```text
  validate(f0 9f 98 80 e4 b8) threw MalformedUtf8
  index = 2
  rule = 3
  ```
- 违反的保证：第 5 条

## 结论 6：BOM 出现在非开头位置且重复出现时未被判为非法

`ef bb bf 41 ef bb bf` 在字节 4..6 处又出现一个 BOM，按保证必须算非法输入，
严格校验实际返回 ok。

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

## 结论 7：decode 对截断的多字节序列静默替换为 U+FFFD，不抛异常

`e4 b8` 是被截断的 3 字节序列，decode 不抛 `MalformedUtf8`，而是静默吐出
两个 U+FFFD 替换码点。

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

## 结论 8：孤立续字节报错时违反规则号被写成 1，应为规则 3

孤立续字节 `80` 属于保证 3 列举的非法情形，异常里却报告 rule=1。

- 文件与行号：`src/main/java/utf8cut/Utf8.java:73`
- 复现命令：
  ```bash
  javac -encoding UTF-8 -cp out -d out repro/Repro.java
  java -cp out repro.Repro validate 80
  ```
- 实际输出：
  ```text
  validate(80) threw MalformedUtf8
  index = 0
  rule = 1
  ```
- 违反的保证：第 5 条
