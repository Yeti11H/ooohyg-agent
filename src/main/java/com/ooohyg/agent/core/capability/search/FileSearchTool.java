package com.ooohyg.agent.core.capability.search;

import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用文件检索工具的共享基类。
 *
 * <p>子类（{@link FileGrepTool} / {@link FileGlobTool} / {@link FileReadTool}）
 * 在构造器中必须完成两件事：
 * <ol>
 *   <li>调用 {@code super(context)} 传入检索边界；</li>
 *   <li>给 {@link #definition} 赋值（工具名、描述、参数 schema）。</li>
 * </ol>
 *
 * <p>本类基于 {@link FileSearchScope} 提供三项能力：
 * <ul>
 *   <li>{@link #listFiles()}：按忽略目录 + 扩展名过滤递归收集文件；</li>
 *   <li>{@link #resolveSafe(String)}：相对路径安全解析，防路径穿越；</li>
 *   <li>{@link #extractValue(String, String)}：极简 JSON 参数提取（core 零依赖）。</li>
 * </ul>
 */
public abstract class FileSearchTool implements Tool {

    /** 检索边界，构造时固定。 */
    protected final SearchContext context;

    /**
     * 工具对外描述。子类构造器必须赋值，{@link #definition()} 在未赋值时
     * 抛出 IllegalStateException，避免返回 null 的 definition。
     */
    protected ToolDefinition definition;

    protected FileSearchTool(SearchContext context) {
        this.context = context;
    }

    @Override
    public ToolDefinition definition() {
        if (definition == null) {
            throw new IllegalStateException(
                    "ToolDefinition must be initialized by subclass constructor");
        }
        return definition;
    }

    /**
     * 递归收集 scope 内所有符合扩展名过滤的文件，并跳过忽略目录。
     */
    protected List<Path> listFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(context.root(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (context.ignoredDirs().contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot > 0 && context.includedExtensions().contains(name.substring(dot))) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * 把相对路径安全解析到 scope 根目录下，防止路径穿越逃出检索边界。
     */
    protected Path resolveSafe(String relativePath) {
        Path resolved = context.root().resolve(relativePath).normalize();
        if (!resolved.startsWith(context.root())) {
            throw new IllegalArgumentException("Path escapes scope: " + relativePath);
        }
        return resolved;
    }

    /**
     * 从模型传入的 JSON 参数字符串中提取指定 key 的值（极简实现，core 零依赖）。
     *
     * <p>支持 {@code "key":"value"}、{@code "key":123}、{@code "key":true} 等
     * 扁平 JSON 对象；值中可含逗号、冒号；转义字符按原样去除反斜杠。
     * 未找到或解析异常时返回空串。
     *
     * @param args 模型返回的原始 JSON 字符串
     * @param key  要提取的字段名（不含引号）
     * @return 字段值（字符串不带引号），未找到返回 ""
     */
    public static String extractValue(String args, String key) {
        if (args == null || key == null || key.isBlank()) {
            return "";
        }
        String target = "\"" + key + "\"";
        int idx = args.indexOf(target);
        if (idx < 0) {
            return "";
        }
        int colon = args.indexOf(':', idx + target.length());
        if (colon < 0) {
            return "";
        }
        int i = colon + 1;
        while (i < args.length() && Character.isWhitespace(args.charAt(i))) {
            i++;
        }
        if (i >= args.length()) {
            return "";
        }
        char c = args.charAt(i);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < args.length()) {
                char ch = args.charAt(i);
                if (ch == '\\' && i + 1 < args.length()) {
                    sb.append(args.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (ch == '"') {
                    break;
                }
                sb.append(ch);
                i++;
            }
            return sb.toString();
        }
        // 数字 / 布尔 / 裸值：读到逗号或右括号为止
        StringBuilder sb = new StringBuilder();
        while (i < args.length()) {
            char ch = args.charAt(i);
            if (ch == ',' || ch == '}' || ch == ']') {
                break;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString().trim();
    }
}
