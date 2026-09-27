这套切分器上线后，下游偶尔报「收到的片段不是合法 UTF-8」，我怀疑问题在边界处理上。
utf8cut 是 Java 17 的 UTF-8 字节切片工具，只用 JDK 标准库，
构建与自检走 `scripts/*.sh`（`scripts/check.sh` 是固定验收程序，别改）。

README 有「对外保证」8 条和「审查交付物」一节；`src/main/java` 下是完整实现（本次不要改它），
`src/test/java` 的 14 个用例当前全绿，但它们都恰好切在码点边界上。
`repro/Repro.java` 是下游抓下来的最小输入。

任务：审一遍这份实现，把违反「对外保证」的地方找出来，结论写进 review/REVIEW.md。
每条结论必须给出：涉及的文件与行号、可执行的最小复现命令、实际观察到的输出（逐字节或十六进制）、
违反了第几条保证；少一样就不算结论。

验收（scripts/check.sh 是固定验收程序，别改）：
- bash scripts/check.sh 退出码 0，10 个场景全过（format 3 + evidence 3 + coverage 2 + integrity 2）。

约束：
1. 不改 src/、repro/、scripts/；不新增源码文件；只写 review/REVIEW.md。
2. 不引入第三方依赖，不用 Maven / Gradle，只用 JDK 标准库。
3. 不改默认字符集，不依赖 `file.encoding` 环境；证据要能在任意 JVM 上复现。
4. 只写「违反哪条保证」与证据，不要写你打算怎么改（本轮只做审查）。
