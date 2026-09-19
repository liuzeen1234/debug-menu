package com.debugmenu.client;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code /showshaders} 命令：扫描游戏目录下的 {@code shaderpacks} 文件夹，
 * 把识别到的光影包逐条打印到聊天框，并标出当前启用的光影。
 *
 * <p>识别规则：
 * <ul>
 *   <li>目录下的 {@code .zip} 或子文件夹，内部若含 {@code shaders/} 目录则判为光影包，
 *       标注 <b>[光影]</b>；否则标注 <b>[非光影]</b>（可能是误放的其它文件）。</li>
 *   <li>光影包没有统一的元数据名字段，因此显示名取<b>文件名/文件夹名</b>
 *       （{@code .zip} 去掉后缀）。</li>
 * </ul>
 *
 * <p>当前启用的光影从以下配置读取（谁有取谁）：
 * <ul>
 *   <li>Iris：{@code config/iris.properties} 里的 {@code shaderPack=}</li>
 *   <li>OptiFine：{@code optionsshaders.txt} 里的 {@code shaderPack=}</li>
 * </ul>
 * 命中当前启用项时行首标 <b>[启用中]</b>（绿色高亮）。
 *
 * <p>纯客户端命令（{@code fabric-command-api-v2}）。
 */
public final class ShowShadersCommand {

    private ShowShadersCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("showshaders")
                        .executes(ctx -> {
                            printAll(ctx.getSource());
                            return 1;
                        })
        );
    }

    private static void printAll(FabricClientCommandSource source) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path shaderDir = gameDir.resolve("shaderpacks");

        if (!Files.isDirectory(shaderDir)) {
            source.sendFeedback(Text.literal("== 未找到 shaderpacks 目录（可能未安装 Iris/OptiFine 或没放过光影） ==")
                    .formatted(Formatting.YELLOW));
            return;
        }

        String enabled = readEnabledShader(gameDir);

        // 运行时是否存在光影加载器（Iris / OptiFine）。没有任何加载器时，光影不可能生效，
        // 配置文件里残留的 shaderPack= 只是历史值，必须忽略。
        boolean irisLoaded = FabricLoader.getInstance().isModLoaded("iris")
                || FabricLoader.getInstance().isModLoaded("oculus");
        boolean optifineLoaded = FabricLoader.getInstance().isModLoaded("optifine")
                || FabricLoader.getInstance().isModLoaded("optifabric");
        boolean shaderLoaderPresent = irisLoaded || optifineLoaded;

        // 向 Iris 运行时查询"当前是否真的有光影在生效"（含编译失败判定）。
        // 三态：TRUE=在用 / FALSE=没在用 / null=API 不可用（无法判定，仅在 Iris 已加载时才可能）。
        Boolean irisInUse = irisLoaded ? queryIrisShaderInUse() : null;

        List<MutableText> lines = new ArrayList<>();
        int shaderCount = 0;
        int otherCount = 0;
        boolean enabledSeen = false;

        try (Stream<Path> stream = Files.list(shaderDir)) {
            List<Path> entries = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();

            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                boolean isZip = fileName.toLowerCase().endsWith(".zip");

                if (!isDir && !isZip) {
                    continue; // 只关心 zip 和文件夹形式的光影
                }

                boolean isShader = looksLikeShaderPack(entry, isDir, isZip);
                if (isShader) {
                    shaderCount++;
                } else {
                    otherCount++;
                }

                // 当前启用项按 Iris/OptiFine 记录的名字匹配（记录的通常就是文件名/文件夹名）。
                boolean isSelected = enabled != null && enabled.equalsIgnoreCase(fileName);
                if (isSelected) {
                    enabledSeen = true;
                }
                // 被选中的那项：结合"是否有加载器 + Iris 运行时状态"判断真实状态。
                EnabledState state = EnabledState.NORMAL;
                if (isSelected) {
                    if (!shaderLoaderPresent) {
                        // 没有任何光影加载器 → 无论配置写了谁，光影都不可能生效。
                        state = EnabledState.NORMAL;
                    } else if (Boolean.TRUE.equals(irisInUse)) {
                        state = EnabledState.IN_USE;
                    } else if (Boolean.FALSE.equals(irisInUse)) {
                        state = EnabledState.FAILED; // Iris 已加载但报告没在用 → 可能加载/编译失败
                    } else {
                        // 加载器在场但无法查询运行时状态（如 OptiFine，或 Iris API 不可用）：
                        // 退回按配置视为启用。
                        state = EnabledState.IN_USE;
                    }
                }
                lines.add(formatLine(fileName, isDir, isShader, state));
            }
        } catch (Exception e) {
            source.sendFeedback(Text.literal("== shaderpacks 目录扫描失败: " + e.getMessage() + " ==")
                    .formatted(Formatting.RED));
            return;
        }

        source.sendFeedback(Text.literal("== 光影包 (光影 " + shaderCount + " · 其它 " + otherCount + ") ==")
                .formatted(Formatting.AQUA, Formatting.BOLD));

        if (lines.isEmpty()) {
            source.sendFeedback(Text.literal("（shaderpacks 目录为空）").formatted(Formatting.GRAY));
        } else {
            for (MutableText line : lines) {
                source.sendFeedback(line);
            }
        }

        // 当前启用状态汇总。
        if (!shaderLoaderPresent) {
            // 没有光影加载器：即便配置里残留了 shaderPack，也不可能生效。
            MutableText msg = Text.literal("—— 未检测到光影加载器（Iris/OptiFine），光影不会生效");
            if (enabled != null && !enabled.isBlank() && !"(internal)".equalsIgnoreCase(enabled)) {
                msg.append(Text.literal("；配置中残留选择: " + enabled));
            }
            msg.append(Text.literal(" ——"));
            source.sendFeedback(msg.formatted(Formatting.YELLOW));
        } else if (enabled == null || enabled.isBlank() || "(internal)".equalsIgnoreCase(enabled)) {
            source.sendFeedback(Text.literal("—— 当前未启用光影 ——").formatted(Formatting.DARK_AQUA));
        } else if (!enabledSeen) {
            // 配置里写了某个光影，但目录里没找到对应文件（可能已删除/改名）。
            source.sendFeedback(Text.literal("—— 当前启用: " + enabled + "（目录中未找到对应文件） ——")
                    .formatted(Formatting.YELLOW));
        } else if (Boolean.FALSE.equals(irisInUse)) {
            // 配置选了它、文件也在，但 Iris 运行时报告没在用 → 极可能加载/编译失败。
            source.sendFeedback(Text.literal("—— 已选择: " + enabled
                            + "，但 Iris 报告未生效（可能加载/编译失败，建议查 logs/latest.log） ——")
                    .formatted(Formatting.RED));
        } else {
            source.sendFeedback(Text.literal("—— 当前启用: " + enabled + " ——")
                    .formatted(Formatting.GREEN));
        }
    }

    /** 被选中光影的运行时状态。 */
    private enum EnabledState {
        /** 未被选中的普通条目。 */
        NORMAL,
        /** 被选中且确实在生效。 */
        IN_USE,
        /** 被选中但 Iris 报告未生效（可能加载/编译失败）。 */
        FAILED
    }

    /** 组装单行：{@code [启用中]/[未生效] [光影/非光影] 名字}。 */
    private static MutableText formatLine(String fileName, boolean isDir, boolean isShader, EnabledState state) {
        MutableText line = Text.literal("");
        if (state == EnabledState.IN_USE) {
            line.append(Text.literal("[启用中] ").formatted(Formatting.GREEN, Formatting.BOLD));
        } else if (state == EnabledState.FAILED) {
            line.append(Text.literal("[未生效·可能加载失败] ").formatted(Formatting.RED, Formatting.BOLD));
        }
        if (isShader) {
            line.append(Text.literal("[光影] ").formatted(Formatting.LIGHT_PURPLE));
        } else {
            line.append(Text.literal("[非光影] ").formatted(Formatting.GRAY));
        }

        Formatting nameColor = switch (state) {
            case IN_USE -> Formatting.GREEN;
            case FAILED -> Formatting.RED;
            case NORMAL -> isShader ? Formatting.WHITE : Formatting.GRAY;
        };
        line.append(Text.literal(fileName).formatted(nameColor));
        if (isDir) {
            line.append(Text.literal(" (文件夹)").formatted(Formatting.DARK_GRAY));
        }
        return line;
    }

    /**
     * 判断一个 zip 或文件夹是否像光影包：内部是否存在 {@code shaders/} 目录。
     */
    private static boolean looksLikeShaderPack(Path entry, boolean isDir, boolean isZip) {
        if (isDir) {
            return Files.isDirectory(entry.resolve("shaders"));
        }
        if (isZip) {
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                java.util.Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    String name = en.nextElement().getName();
                    // 顶层或任意层级出现 shaders/ 目录即认定为光影包。
                    if (name.equals("shaders/") || name.startsWith("shaders/")
                            || name.contains("/shaders/")) {
                        return true;
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 读取当前启用的光影名。优先 Iris（{@code config/iris.properties}），
     * 其次 OptiFine（{@code optionsshaders.txt}）。
     *
     * <p><b>关键：</b>Iris 里 {@code shaderPack=} 记录的只是"上次选中的光影"，
     * 全局禁用光影时该值<b>不会被清空</b>；真正的总开关是 {@code enableShaders}。
     * 因此这里在读到 shaderPack 后必须再看 {@code enableShaders}：为 {@code false}
     * 则视为未启用（返回 {@code null}），避免"已禁用仍显示启用中"。OptiFine 同理，
     * 关闭时 {@code shaderPack} 会是 {@code (internal)} / {@code OFF}。
     *
     * @return 启用的光影文件名/文件夹名；未启用或读不到返回 {@code null}
     */
    private static String readEnabledShader(Path gameDir) {
        // Iris
        Path irisProps = gameDir.resolve("config").resolve("iris.properties");
        if (Files.isRegularFile(irisProps)) {
            String pack = readKey(irisProps, "shaderPack");
            String enable = readKey(irisProps, "enableShaders");
            // enableShaders 缺省时 Iris 默认按启用处理；显式 false 才算关闭。
            boolean shadersOn = enable == null || !"false".equalsIgnoreCase(enable.trim());
            if (!shadersOn) {
                return null; // 总开关关闭：无论 shaderPack 记着谁都算未启用
            }
            if (pack != null && !isNoneValue(pack)) {
                return pack;
            }
            // Iris 存在但未选/已禁用，直接返回未启用；不再回退 OptiFine。
            return null;
        }

        // OptiFine
        String ofPack = readKey(gameDir.resolve("optionsshaders.txt"), "shaderPack");
        if (ofPack != null && !isNoneValue(ofPack)) {
            return ofPack;
        }
        return null;
    }

    /**
     * 通过反射查询 Iris 运行时状态：当前是否真的有光影在生效。
     *
     * <p>调用 Iris 公开 API {@code IrisApi.getInstance().isShaderPackInUse()}。该方法的语义
     * 明确包含"光影包编译失败因而未在使用"的情况——失败时返回 {@code false}，正是我们要区分的。
     * 用反射避免对 Iris 的硬依赖：没装 Iris（或版本无此 API）时返回 {@code null} 表示"无法判定"。
     *
     * <p>Iris 历史上换过包名：较新版本为 {@code net.irisshaders.iris.api.v0.IrisApi}，
     * 早期为 {@code net.coderbot.iris.api.v0.IrisApi}，这里两者都尝试。
     *
     * @return {@code TRUE}=在用；{@code FALSE}=未在用（含加载失败）；{@code null}=无法判定
     */
    private static Boolean queryIrisShaderInUse() {
        String[] apiClasses = {
                "net.irisshaders.iris.api.v0.IrisApi",
                "net.coderbot.iris.api.v0.IrisApi"
        };
        for (String cls : apiClasses) {
            try {
                Class<?> apiClass = Class.forName(cls);
                Object api = apiClass.getMethod("getInstance").invoke(null);
                if (api == null) {
                    continue;
                }
                Object result = apiClass.getMethod("isShaderPackInUse").invoke(api);
                if (result instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable ignored) {
                // 类不存在 / 方法签名不符 / 调用异常：尝试下一个候选或放弃。
            }
        }
        return null;
    }

    /** 判断值是否表示"无 / 关闭"（OptiFine 用 {@code (internal)} / {@code OFF} 表示不启用光影）。 */
    private static boolean isNoneValue(String value) {
        String v = value.trim();
        return v.isEmpty()
                || "(internal)".equalsIgnoreCase(v)
                || "OFF".equalsIgnoreCase(v)
                || "none".equalsIgnoreCase(v);
    }

    /**
     * 从一个 {@code key=value} 风格的配置文件里取指定键的值。
     * 文件不存在或没有该键返回 {@code null}。
     */
    private static String readKey(Path file, String key) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = trimmed.substring(0, eq).trim();
                if (k.equals(key)) {
                    String value = trimmed.substring(eq + 1).trim();
                    return value.isBlank() ? null : value;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
