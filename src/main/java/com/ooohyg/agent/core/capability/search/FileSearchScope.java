package com.ooohyg.agent.core.capability.search;

import java.nio.file.Path;
import java.util.Set;

/**
 * 一次文件检索的范围边界。
 *
 * <p>把"检索哪些位置、忽略哪些目录、包含哪些扩展名、最多返回多少"等
 * 边界参数抽象成独立契约，使同一套 {@link FileGrepTool} /
 * {@link FileGlobTool} / {@link FileReadTool} 可以复用于不同场景
 * （记忆库、项目源码、外部文档），由使用方按场景配置不同的 scope。
 */
public interface FileSearchScope {

    /** 检索根目录，绝对路径。 */
    Path root();

    /** 需要跳过的目录名集合（如 .git、target），按目录名匹配，不递归展开。 */
    Set<String> ignoredDirs();

    /** 仅检索这些扩展名的文件（扩展名含点，如 ".md"）。 */
    Set<String> includedExtensions();

    /** 单次检索最多返回的文件数，防止上下文爆炸。 */
    int maxFiles();

    /** 单文件默认最多读取的行数。 */
    int maxLinesPerFile();

    /** 单文件内容大小上限（字节），超出视为超限文件。 */
    int maxBytesPerFile();
}
