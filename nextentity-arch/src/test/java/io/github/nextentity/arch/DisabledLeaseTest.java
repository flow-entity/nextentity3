package io.github.nextentity.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 测试纪律门禁：`@Disabled` 必须带租约（`issue=` + `expires=`），过期即红。
///
/// 守的是什么：AGENTS「测试编写红线」——`@Disabled("issue=<链接或编号> expires=<yyyy-MM-dd>")`，
/// 两个字段缺一即违规；过了 `expires` 仍在禁用同样违规。没有租约的禁用会永久留在代码里，
/// 变成没人认领的 skip；有租约的禁用在到期那天自己变红，逼人续期或删用例。
///
/// 为什么不写成 `@ArchTest`（这不是偷懒，是 ArchUnit 到不了这里）：
///
/// - 本模块的 `@AnalyzeClasses` 带 `DoNotIncludeTests`，测试类根本不在导入集合里；
/// - 换成不带该选项的导入也没用：classpath 上只有 `nextentity-arch` 自己的测试产物，
///   `nextentity-core` / `nextentity-jpa` / `nextentity-spring` / `nextentity-tests-jpa` /
///   `nextentity-tests-legacy` 的测试类既不在 classpath（本模块 pom 不依赖它们的 test-jar），
///   也不该靠读它们的 `target/test-classes`——那是「不读陈旧产物」红线明令禁止的做法。
///
/// 所以这条纪律只能对**源码**执法：扫描各模块 `src/test/java`，只用 `java.nio.file`，不新增任何依赖。
/// 代价是路径解析必须自己兜底：解析失败时 `repositoryRoot()` 抛异常、扫描数量不足时断言失败，
/// 绝不允许「扫到 0 个文件 → 0 条违规 → 静默变绿」。
///
/// 门禁自己也要有测试：租约判定抽成纯函数 [#leaseViolation(String, LocalDate)]（入参是注解括号里的
/// 原文，外加「今天」），扫描+抽取抽成 [#violationsIn(String, LocalDate)]（入参是源码文本，不碰文件系统）。
/// 正则写错、`@DisabledOnOs` 被误排除、`expires` 静默解析失败——这些都不会让一个没有违规
/// `@Disabled` 的仓库变红，只能靠下方自测钉住；其中「合规必须返回 `null`」的反向用例保证门禁
/// 不是「永远违规」。
@DisplayName("禁用租约门禁")
class DisabledLeaseTest {

    /// 只认 `@Disabled` 本身：`@DisabledOnOs` / `@DisabledIf` 这类兄弟注解靠标识符边界排除。
    private static final String DISABLED = "@Disabled";

    /// 扫描下限：远低于实际测试源文件数，既能挡住路径解析失败，又不会因增删测试文件误红。
    private static final int MIN_SOURCES = 40;

    /// 自测专用「今天」：钉死日历，过期判定不随真实日期漂移。
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);

    private static final Pattern ISSUE = Pattern.compile("\\bissue\\s*=\\s*\\S");

    private static final Pattern EXPIRES = Pattern.compile("\\bexpires\\s*=\\s*(\\d{4}-\\d{2}-\\d{2})");

    private static final Pattern EXPIRES_KEY = Pattern.compile("\\bexpires\\s*=");

    @Test
    @DisplayName("扫描各模块测试源码：所有 @Disabled 都必须带租约")
    void scanEveryTestSourceForDisabledLeases() {
        Path root = repositoryRoot();
        List<Path> sources = testSources(root);
        assertThat(sources).as("扫描到的测试源文件数（路径解析出错会静默扫空，这里兜底）").hasSizeGreaterThan(MIN_SOURCES);

        // 整个扫描用同一个「今天」：跨午夜时不会出现前后半程判定不一致。
        LocalDate today = LocalDate.now();
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            for (String violation : violationsIn(read(source), today)) {
                violations.add(root.relativize(source) + violation);
            }
        }
        assertThat(violations)
                .as("AGENTS「禁用要有租约」：@Disabled(\"issue=<链接或编号> expires=<yyyy-MM-dd>\")")
                .isEmpty();
    }

    @Test
    @DisplayName("裸 @Disabled 与 @Disabled() 判违规：issue 与 expires 全缺")
    void rejectBareDisabled() {
        assertThat(leaseViolation(null, TODAY)).contains("裸 @Disabled", "issue=", "expires=");
        assertThat(leaseViolation("", TODAY)).contains("裸 @Disabled", "issue=", "expires=");
        assertThat(leaseViolation("   ", TODAY)).contains("裸 @Disabled");
    }

    /// 钉住「参数非空、但 issue= 与 expires= 都没写」这条合并报错分支（例：`@Disabled("flaky")`）。
    ///
    /// 它与 `rejectBareDisabled` 走的不是同一条路：那条命中的是「参数为空」的早返回；
    /// 这里参数有值（`"flaky"`），必须落到「两者都缺 → 一次报全两个字段」的分支上。
    /// 若有人把该分支删掉、让消息退化成只报 `缺少 issue=`，这条用例会红。
    @Test
    @DisplayName("有参数但 issue= 与 expires= 都缺判违规：两个字段一起报")
    void rejectBothLeaseFieldsMissing() {
        assertThat(leaseViolation("\"flaky\"", TODAY)).contains("issue=").contains("expires=");
    }

    @Test
    @DisplayName("只有 issue= 判违规：缺 expires=")
    void rejectIssueWithoutExpiry() {
        assertThat(leaseViolation("\"issue=#12\"", TODAY)).contains("缺少 expires=");
    }

    @Test
    @DisplayName("只有 expires= 判违规：缺 issue=")
    void rejectExpiryWithoutIssue() {
        assertThat(leaseViolation("\"expires=2099-01-01\"", TODAY)).contains("缺少 issue=");
    }

    @Test
    @DisplayName("租约过期判违规")
    void rejectExpiredLease() {
        assertThat(leaseViolation("\"issue=#12 expires=2020-01-01\"", LocalDate.of(2020, 6, 1)))
                .contains("已过期", "2020-01-01");
    }

    @Test
    @DisplayName("租约未过期判合规（否则「永远违规」也能骗过上面几条）")
    void acceptLiveLease() {
        assertThat(leaseViolation("\"issue=#12 expires=2099-01-01\"", TODAY)).isNull();
        // 到期当天仍算有效：租约写的是「到这天为止」。
        assertThat(leaseViolation("\"issue=#12 expires=2026-01-01\"", TODAY)).isNull();
    }

    @Test
    @DisplayName("expires 不是 yyyy-MM-dd 判违规，不得静默放行")
    void rejectUnparsableExpiry() {
        assertThat(leaseViolation("\"issue=#12 expires=soon\"", TODAY)).contains("不是 yyyy-MM-dd");
    }

    @Test
    @DisplayName("源码里的裸 @Disabled 报得出违规，行号指到注解那一行")
    void reportBareDisabledInSource() {
        List<String> violations = violationsIn("class A {\n    @Disabled\n    void bare() {}\n}\n", TODAY);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst()).startsWith(":2 ").contains("裸 @Disabled");
    }

    @Test
    @DisplayName("@DisabledOnOs / @DisabledIf 这类兄弟注解不算违规")
    void ignoreDisabledSiblingAnnotations() {
        String source = "class A {\n    @DisabledOnOs(OS.LINUX)\n    void a() {}\n}\n";

        assertThat(violationsIn(source, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("注释里提到 @Disabled 不算违规（含本类的 /// 文档）")
    void ignoreDisabledMentionsInComments() {
        String source = "class A {\n    /// 例子：@Disabled 必须带租约\n    // @Disabled\n    void a() {}\n}\n";

        assertThat(violationsIn(source, TODAY)).isEmpty();
    }

    /// 租约判定的唯一出处：入参是注解括号里的原文（含引号，`null` 表示没写括号），外加「今天」。
    ///
    /// @param arguments 注解参数原文；`null` / 空白表示裸注解
    /// @param today 判定基准日
    /// @return 违规原因，`null` 表示租约合规
    private static String leaseViolation(String arguments, LocalDate today) {
        if (arguments == null || arguments.isBlank()) {
            return "裸 @Disabled：必须写明 issue= 与 expires=";
        }
        boolean hasIssue = ISSUE.matcher(arguments).find();
        boolean hasExpires = EXPIRES_KEY.matcher(arguments).find();
        if (!hasIssue && !hasExpires) {
            return "@Disabled 缺少 issue=<链接或编号> 与 expires=<yyyy-MM-dd>：" + arguments.strip();
        }
        if (!hasIssue) {
            return "@Disabled 缺少 issue=<链接或编号>：" + arguments.strip();
        }
        if (!hasExpires) {
            return "@Disabled 缺少 expires=<yyyy-MM-dd>：" + arguments.strip();
        }
        Matcher expires = EXPIRES.matcher(arguments);
        if (!expires.find()) {
            return "@Disabled 的 expires 不是 yyyy-MM-dd：" + arguments.strip();
        }
        String deadline = expires.group(1);
        try {
            if (LocalDate.parse(deadline).isBefore(today)) {
                return "@Disabled 租约已过期（expires=" + deadline + "）：续期或删掉这个用例";
            }
        } catch (DateTimeParseException e) {
            return "@Disabled 的 expires 无法解析：" + deadline;
        }
        return null;
    }

    /// 从一段源码文本里挑出全部违规的 `@Disabled`；不碰文件系统，便于自测。
    ///
    /// @param source 源码文本
    /// @param today 判定基准日
    /// @return 每条形如 `:行号 原因`
    private static List<String> violationsIn(String source, LocalDate today) {
        List<String> violations = new ArrayList<>();
        String withoutComments = mask(source, false);
        String withoutLiterals = mask(source, true);
        int from = 0;
        while (true) {
            int at = withoutComments.indexOf(DISABLED, from);
            if (at < 0) {
                return violations;
            }
            from = at + DISABLED.length();
            if (from < withoutComments.length() && Character.isJavaIdentifierPart(withoutComments.charAt(from))) {
                continue;
            }
            if (withoutLiterals.charAt(at) != '@') {
                continue;
            }
            String reason = leaseViolation(arguments(withoutComments, from), today);
            if (reason != null) {
                violations.add(":" + lineOf(withoutComments, at) + " " + reason);
            }
        }
    }

    /// 仓库根：从当前工作目录（surefire 下是本模块目录）向上找带 `nextentity-core/src/test/java` 的目录。
    private static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath();
        Path dir = start;
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            if (Files.isDirectory(dir.resolve("nextentity-core/src/test/java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根：从 " + start + " 向上 4 层都没有 nextentity-core/src/test/java，禁用租约门禁无法执法");
    }

    private static List<Path> testSources(Path root) {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("nextentity-"))
                    .toList()) {
                Path testRoot = module.resolve("src/test/java");
                if (!Files.isDirectory(testRoot)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(testRoot)) {
                    walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                            .forEach(sources::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("遍历测试源码失败：" + root, e);
        }
        return sources;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了测试源文件：" + source, e);
        }
    }

    /// 把注释（以及可选的字符串 / 字符字面量内容）抹成空格，长度与换行不变，偏移可直接复用。
    private static String mask(String source, boolean blankLiterals) {
        char[] out = source.toCharArray();
        int index = 0;
        while (index < out.length) {
            char current = out[index];
            if (current == '/' && index + 1 < out.length && out[index + 1] == '/') {
                while (index < out.length && out[index] != '\n') {
                    out[index++] = ' ';
                }
            } else if (current == '/' && index + 1 < out.length && out[index + 1] == '*') {
                out[index++] = ' ';
                out[index++] = ' ';
                while (index < out.length && !(out[index] == '*' && index + 1 < out.length && out[index + 1] == '/')) {
                    if (out[index] != '\n') {
                        out[index] = ' ';
                    }
                    index++;
                }
                if (index < out.length) {
                    out[index++] = ' ';
                    if (index < out.length) {
                        out[index++] = ' ';
                    }
                }
            } else if (current == '"' || current == '\'') {
                int start = index;
                index++;
                while (index < out.length) {
                    char inner = out[index];
                    if (inner == '\\') {
                        index += 2;
                        continue;
                    }
                    index++;
                    if (inner == current || inner == '\n') {
                        break;
                    }
                }
                if (blankLiterals) {
                    for (int position = start + 1; position < index - 1; position++) {
                        if (out[position] != '\n') {
                            out[position] = ' ';
                        }
                    }
                }
            } else {
                index++;
            }
        }
        return new String(out);
    }

    /// 取出注解参数文本（含引号、不含外层括号）；没有括号返回 `null`。
    private static String arguments(String code, int afterName) {
        int index = afterName;
        while (index < code.length() && Character.isWhitespace(code.charAt(index))) {
            index++;
        }
        if (index >= code.length() || code.charAt(index) != '(') {
            return null;
        }
        int start = index + 1;
        int depth = 0;
        while (index < code.length()) {
            char current = code.charAt(index);
            if (current == '"') {
                index++;
                while (index < code.length() && code.charAt(index) != '"') {
                    if (code.charAt(index) == '\\') {
                        index++;
                    }
                    index++;
                }
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(start, index);
                }
            }
            index++;
        }
        return code.substring(start);
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
