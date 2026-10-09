package com.ooohyg.agent.core.capability.search;

import java.nio.file.Path;
import java.util.Set;

/**
 * {@link FileSearchScope} 的默认实现：以 record 形式承载一次检索的全部边界参数。
 *
 * <p>通过静态工厂快速创建常见场景的 scope：
 * <ul>
 *   <li>{@link #of(Path)}：通用文档（md/txt/json）；</li>
 *   <li>{@link #forCode(Path)}：项目源码；</li>
 *   <li>{@link #forMemory(Path)}：记忆库（仅 md，上限更保守）；</li>
 *   <li>{@link #forDocuments(Path)}：外部知识文档。</li>
 * </ul>
 */
public record SearchContext(
        Path root,
        Set<String> ignoredDirs,
        Set<String> includedExtensions,
        int maxFiles,
        int maxLinesPerFile,
        int maxBytesPerFile
) implements FileSearchScope {

    /** 默认忽略的目录：编译产物、依赖、IDE 配置、VCS 元数据。 */
    public static final Set<String> DEFAULT_IGNORED = Set.of(
            ".git", "target", "build", "node_modules", ".idea", ".vscode", "__pycache__"
    );

    /** 通用文档 scope：md / txt / json，最多 100 个文件、单文件 2000 行 / 25KB。 */
    public static SearchContext of(Path root) {
        return new SearchContext(root, DEFAULT_IGNORED, Set.of(".md", ".txt", ".json"),
                100, 2000, 25_000);
    }

    /** 源码 scope：常见编程语言扩展名。 */
    public static SearchContext forCode(Path root) {
        return new SearchContext(root, DEFAULT_IGNORED,
                Set.of(".java", ".py", ".ts", ".tsx", ".js", ".go", ".rs"),
                100, 2000, 25_000);
    }

    /** 记忆库 scope：仅 md，限制更严格（50 个文件 / 10KB），降低注入噪音。 */
    public static SearchContext forMemory(Path root) {
        return new SearchContext(root, DEFAULT_IGNORED, Set.of(".md"),
                50, 2000, 10_000);
    }

    /** 外部知识文档 scope。 */
    public static SearchContext forDocuments(Path root) {
        return new SearchContext(root, DEFAULT_IGNORED, Set.of(".md", ".txt", ".json"),
                100, 2000, 25_000);
    }
}
