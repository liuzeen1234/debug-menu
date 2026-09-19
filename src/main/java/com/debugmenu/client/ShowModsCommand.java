package com.debugmenu.client;

import com.debugmenu.api.DebugMenuApi;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code /showmods} 命令：把当前实例加载的所有 Mod 逐条打印到聊天框。
 *
 * <p>展示规则：
 * <ul>
 *   <li>显示名：优先用通过 {@link DebugMenuApi#setModDisplayName} 在 API 中注册的名字；
 *       未注册时回退到 Fabric 识别出的 Mod 名（{@link ModMetadata#getName()}）。
 *       末尾始终附上 Mod id 便于精确辨识。</li>
 *   <li>分类标注：
 *     <ul>
 *       <li><b>功能</b> —— 普通功能 Mod，正常颜色。</li>
 *       <li><b>库</b> —— 加载器 / Java / Minecraft / Fabric API 系列等基础库，灰色标注。</li>
 *       <li><b>未生效</b> —— 元数据声明的运行端（{@link ModEnvironment}）与当前端不匹配，
 *           即该 Mod 虽被列出但在当前端不起作用，用醒目颜色标注，与已加载功能 Mod 区分。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>纯客户端命令（{@code fabric-command-api-v2}），单机与联机均可用，不需要服务端配合。
 */
public final class ShowModsCommand {

    private ShowModsCommand() {}

    /** Mod 分类。 */
    private enum Category {
        FEATURE, LIBRARY, INACTIVE
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("showmods")
                        // /showmods —— 只显示功能 Mod + 未生效，隐藏库/内置
                        .executes(ctx -> {
                            printAll(ctx.getSource(), false);
                            return 1;
                        })
                        // /showmods all —— 显示全部（含库/内置）
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("all")
                                .executes(ctx -> {
                                    printAll(ctx.getSource(), true);
                                    return 1;
                                }))
        );
    }

    /**
     * @param includeLibraries 是否包含库/内置项（{@code /showmods all} 为 true）
     */
    private static void printAll(FabricClientCommandSource source, boolean includeLibraries) {
        EnvType currentEnv = FabricLoader.getInstance().getEnvironmentType();

        // 收集并按 (分类, id) 排序：功能在前、库居中、未生效在后，同类按 id 字典序。
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        List<ModContainer> sorted = new ArrayList<>(mods);
        sorted.sort(Comparator
                .comparingInt((ModContainer m) -> classify(m.getMetadata(), currentEnv).ordinal())
                .thenComparing(m -> m.getMetadata().getId()));

        int feature = 0, library = 0, inactive = 0;
        int shown = 0;

        // 标题先按已显示条目数占位，遍历后无法回改，这里直接统计将要显示的数量。
        for (ModContainer mod : sorted) {
            Category category = classify(mod.getMetadata(), currentEnv);
            if (category == Category.LIBRARY && !includeLibraries) {
                continue;
            }
            shown++;
        }

        String titleSuffix = includeLibraries ? "全部" : "功能/未生效";
        source.sendFeedback(Text.literal("== 已加载 Mod · " + titleSuffix + " (共 " + shown + ") ==")
                .formatted(Formatting.AQUA, Formatting.BOLD));

        for (ModContainer mod : sorted) {
            ModMetadata meta = mod.getMetadata();
            Category category = classify(meta, currentEnv);
            switch (category) {
                case FEATURE -> feature++;
                case LIBRARY -> library++;
                case INACTIVE -> inactive++;
            }
            // 默认隐藏库/内置；仅在 /showmods all 时显示。
            if (category == Category.LIBRARY && !includeLibraries) {
                continue;
            }
            source.sendFeedback(formatLine(meta, category, currentEnv));
        }

        MutableText summary = Text.literal("—— 功能 " + feature + " · 未生效 " + inactive);
        if (includeLibraries) {
            summary.append(Text.literal(" · 库 " + library));
        } else {
            summary.append(Text.literal(" · 库 " + library + "(已隐藏，/showmods all 查看)"));
        }
        summary.append(Text.literal(" ——"));
        source.sendFeedback(summary.formatted(Formatting.DARK_AQUA));

        // 追加：扫描 mods 目录，报告"已禁用 / 未加载"的 jar（运行时列表看不到这些）。
        scanModsFolder(source, loadedIds(mods));
    }

    /** 收集运行时已加载的全部 mod id，供磁盘扫描去重。 */
    private static Set<String> loadedIds(List<ModContainer> mods) {
        Set<String> ids = new HashSet<>();
        for (ModContainer mod : mods) {
            ids.add(mod.getMetadata().getId());
        }
        return ids;
    }

    /**
     * 扫描游戏目录下的 {@code mods} 文件夹，报告磁盘上存在但未生效的 jar：
     * <ul>
     *   <li><b>[已禁用]</b> —— 文件名以 {@code .disabled} 结尾，被手动禁用。</li>
     *   <li><b>[未加载]</b> —— 普通 {@code .jar} 但其 id 不在运行时已加载集合里
     *       （加载失败 / 依赖缺失 / 非 Fabric jar 等）。</li>
     * </ul>
     * 只读取 jar 内 {@code fabric.mod.json} 拿 id/name/version，不引额外依赖。
     *
     * @param loadedIds 运行时已加载的 mod id 集合，用于判断某 jar 是否已生效
     */
    private static void scanModsFolder(FabricClientCommandSource source, Set<String> loadedIds) {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return;
        }

        List<MutableText> lines = new ArrayList<>();
        int disabled = 0;
        int unloaded = 0;

        try (Stream<Path> stream = Files.list(modsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();

            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String lower = fileName.toLowerCase();

                boolean isDisabled = lower.endsWith(".jar.disabled") || lower.endsWith(".disabled");
                boolean isJar = lower.endsWith(".jar");

                if (!isDisabled && !isJar) {
                    continue; // 非 jar、非禁用的 jar，跳过
                }

                JarInfo info = readJarInfo(file);

                if (isDisabled) {
                    disabled++;
                    lines.add(formatScanLine("[已禁用] ", Formatting.LIGHT_PURPLE, info, fileName, file));
                } else {
                    // 普通 .jar：若其 id 已在运行时加载集合里，说明已生效，跳过。
                    if (info != null && info.id != null && loadedIds.contains(info.id)) {
                        continue;
                    }
                    unloaded++;
                    lines.add(formatScanLine("[未加载] ", Formatting.RED, info, fileName, file));
                }
            }
        } catch (Exception e) {
            source.sendFeedback(Text.literal("== mods 目录扫描失败: " + e.getMessage() + " ==")
                    .formatted(Formatting.RED));
            return;
        }

        if (lines.isEmpty()) {
            return; // 没有禁用/未加载项就不打扰
        }

        source.sendFeedback(Text.literal("== mods 目录·未生效 jar (禁用 " + disabled
                        + " · 未加载 " + unloaded + ") ==")
                .formatted(Formatting.RED, Formatting.BOLD));
        for (MutableText line : lines) {
            source.sendFeedback(line);
        }
    }

    /**
     * 组装一行磁盘扫描结果：{@code [标签] 显示名 [id] (文件名)}。
     *
     * <p>无法解析出 mod 名时（非 Fabric jar / 无 {@code fabric.mod.json} / 解析失败），
     * 退化为显示从 jar 内 {@code .class} 推断出的顶层 Java 包名（如 {@code com.example.mymod}），
     * 并在末尾附文件名；连包名都推断不出时才只显示文件名。
     */
    private static MutableText formatScanLine(String tag, Formatting tagColor, JarInfo info,
                                              String fileName, Path jarPath) {
        MutableText line = Text.literal(tag).formatted(tagColor, Formatting.BOLD);
        if (info != null && info.name != null && !info.name.isBlank()) {
            line.append(Text.literal(info.name).formatted(Formatting.GRAY));
            if (info.version != null && !info.version.isBlank()) {
                line.append(Text.literal(" " + info.version).formatted(Formatting.DARK_GRAY));
            }
            String id = (info.id != null) ? info.id : "?";
            line.append(Text.literal(" [" + id + "]").formatted(Formatting.DARK_GRAY));
            line.append(Text.literal(" (" + fileName + ")").formatted(Formatting.DARK_GRAY));
        } else {
            // 非 Fabric jar 或无法解析：优先用推断出的顶层包名，否则只给文件名。
            String pkg = guessTopPackage(jarPath);
            if (pkg != null) {
                line.append(Text.literal(pkg).formatted(Formatting.GRAY));
                line.append(Text.literal(" (包名) (" + fileName + ")").formatted(Formatting.DARK_GRAY));
            } else {
                line.append(Text.literal(fileName).formatted(Formatting.GRAY));
            }
        }
        return line;
    }

    /**
     * 从 jar 内的 {@code .class} 条目推断最可能的顶层 Java 包名。
     *
     * <p>做法：遍历所有 {@code .class} 条目，取其包路径的前两段（如 {@code com/example}）计数，
     * 选出现次数最多的一组作为代表，转成点分包名返回。跳过常见的通用/元数据前缀
     * （{@code META-INF}、{@code assets}、{@code data} 等）。全部无法归纳时返回 {@code null}。
     */
    private static String guessTopPackage(Path jarPath) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                // 顶层单文件类（无包）忽略；模块信息忽略。
                if (name.equals("module-info.class") || !name.contains("/")) {
                    continue;
                }
                String[] parts = name.split("/");
                String first = parts[0];
                if (first.equals("META-INF") || first.equals("assets")
                        || first.equals("data") || first.isEmpty()) {
                    continue;
                }
                // 取前两段作为代表（够区分 com.xxx / net.xxx / dev.xxx 这类）。
                String key = (parts.length >= 3) ? (parts[0] + "." + parts[1]) : parts[0];
                counts.merge(key, 1, Integer::sum);
            }
        } catch (Exception e) {
            return null;
        }
        if (counts.isEmpty()) {
            return null;
        }
        return counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    /** jar 内 fabric.mod.json 解析出的关键字段。 */
    private static final class JarInfo {
        final String id;
        final String name;
        final String version;

        JarInfo(String id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    /**
     * 读取 jar（含 {@code .disabled} 后缀）内的 {@code fabric.mod.json}，解析 id/name/version。
     * 读取失败或非 Fabric jar 返回 {@code null}。
     */
    private static JarInfo readJarInfo(Path jarPath) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    return null;
                }
                JsonObject obj = root.getAsJsonObject();
                String id = optString(obj, "id");
                String name = optString(obj, "name");
                String version = optString(obj, "version");
                if (name == null && id != null) {
                    name = id; // name 缺省时用 id 兜底
                }
                return new JarInfo(id, name, version);
            }
        } catch (Exception e) {
            return null; // 损坏 / 加密 / 非 zip 等，静默忽略
        }
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            String s = el.getAsString();
            return s.isBlank() ? null : s;
        }
        return null;
    }

    /**
     * 组装单行显示：{@code [标签] 显示名 [id]}。
     */
    private static MutableText formatLine(ModMetadata meta, Category category, EnvType currentEnv) {
        String id = meta.getId();
        String displayName = resolveDisplayName(meta);

        MutableText line = Text.literal("");

        switch (category) {
            case LIBRARY -> line.append(Text.literal("[库] ").formatted(Formatting.DARK_GRAY));
            case INACTIVE -> {
                String want = envLabel(meta.getEnvironment());
                line.append(Text.literal("[未生效·仅" + want + "端] ").formatted(Formatting.YELLOW, Formatting.BOLD));
            }
            case FEATURE -> { /* 无前缀标签 */ }
        }

        Formatting nameColor = switch (category) {
            case FEATURE -> Formatting.GREEN;
            case LIBRARY -> Formatting.GRAY;
            case INACTIVE -> Formatting.GOLD;
        };
        line.append(Text.literal(displayName).formatted(nameColor));
        line.append(Text.literal(" [" + id + "]").formatted(Formatting.DARK_GRAY));
        return line;
    }

    /**
     * 显示名：优先用 API 注册名，否则回退 Fabric 识别出的 Mod 名。
     *
     * <p>{@link DebugMenuApi#getModDisplayName} 在未注册时会回退返回传入的 key（即 id）；
     * 因此这里先判断是否与 id 相同——相同说明没有注册名，改用 {@link ModMetadata#getName()}。
     */
    private static String resolveDisplayName(ModMetadata meta) {
        String id = meta.getId();
        String registered = DebugMenuApi.getModDisplayName(id);
        if (registered != null && !registered.equals(id) && !registered.isBlank()) {
            return registered;
        }
        String name = meta.getName();
        return (name != null && !name.isBlank()) ? name : id;
    }

    /**
     * 分类判定：先判"未生效"（端侧不匹配），再判"库"，其余为功能 Mod。
     */
    private static Category classify(ModMetadata meta, EnvType currentEnv) {
        if (!environmentMatches(meta.getEnvironment(), currentEnv)) {
            return Category.INACTIVE;
        }
        if (isLibrary(meta.getId())) {
            return Category.LIBRARY;
        }
        return Category.FEATURE;
    }

    /**
     * 当前端是否满足该 Mod 声明的运行端。
     *
     * <p>{@link ModEnvironment#UNIVERSAL} 两端通用；{@link ModEnvironment#CLIENT} 仅客户端有效；
     * {@link ModEnvironment#SERVER} 仅服务端有效。
     */
    private static boolean environmentMatches(ModEnvironment env, EnvType currentEnv) {
        if (env == null || env == ModEnvironment.UNIVERSAL) {
            return true;
        }
        return switch (env) {
            case CLIENT -> currentEnv == EnvType.CLIENT;
            case SERVER -> currentEnv == EnvType.SERVER;
            default -> true;
        };
    }

    /** 端侧标签，用于"未生效"提示。 */
    private static String envLabel(ModEnvironment env) {
        if (env == ModEnvironment.CLIENT) {
            return "客户";
        }
        if (env == ModEnvironment.SERVER) {
            return "服务";
        }
        return "?";
    }

    /**
     * 基础库/内置项判定：加载器、Java、Minecraft，以及 Fabric API 系列子模块。
     */
    private static boolean isLibrary(String id) {
        if (id == null) {
            return false;
        }
        switch (id) {
            case "java":
            case "minecraft":
            case "fabricloader":
            case "fabric-api":
            case "fabric":
            case "mixinextras":
                return true;
            default:
                // Fabric API 拆分出的一大批 fabric-xxx-vN 子模块
                return id.startsWith("fabric-");
        }
    }
}
