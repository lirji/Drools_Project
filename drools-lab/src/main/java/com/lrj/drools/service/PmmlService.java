package com.lrj.drools.service;

import org.kie.api.pmml.PMML4Result;
import org.kie.efesto.common.api.model.GeneratedResources;
import org.kie.efesto.compilationmanager.api.model.EfestoFileResource;
import org.kie.efesto.compilationmanager.api.service.CompilationManager;
import org.kie.efesto.compilationmanager.api.utils.SPIUtils;
import org.kie.efesto.runtimemanager.core.model.EfestoRuntimeContextImpl;
import org.kie.memorycompiler.KieMemoryCompiler;
import org.kie.pmml.api.runtime.PMMLRuntime;
import org.kie.pmml.api.runtime.PMMLRuntimeContext;
import org.kie.pmml.compiler.PMMLCompilationContextImpl;
import org.kie.pmml.evaluator.core.PMMLRuntimeContextImpl;
import org.kie.pmml.evaluator.core.service.PMMLRuntimeInternalImpl;
import org.kie.pmml.evaluator.core.utils.PMMLRequestDataBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Step 24: PMML（Predictive Model Markup Language）—— 规则里嵌 ML 模型评分 + 评分卡（Scorecard）。
 *
 * PMML 是 DMG 的跨厂商标准，把训练好的模型（评分卡 / 回归 / 决策树 / 混合模型…）写成 XML，
 * 用**独立的求值引擎**跑，跟 DRL/RETE（Step 1–16）和 DMN（Step 17）都不是一套东西。8.44.2 走
 * "trusty"（efesto）实现：`.pmml` 在运行时被编译成 Java 类，再喂输入求值。
 *
 * ───────────── 8.44.2 trusty PMML 的接法（踩过的坑都在这） ─────────────
 *
 * 依赖：`org.kie:kie-pmml-dependencies`(pom 聚合，无单构件 kie-pmml-trusty) + javax JAXB 2.3
 * （trusty 编译器用 `javax.xml.bind` 解析 XML，Java 21 已从 JDK 移除，必须显式补）。
 *
 * 求值：官方"便捷工厂" `PMMLRuntimeFactory.getPMMLRuntimeFromFile/Classpath` **在 standalone 下不好用**：
 *   - getPMMLRuntimeFromClasspath 要 `.pmml` 是磁盘上真实 File（fat-jar 内会失败）；
 *   - 它编译进一个**内部新建**的 classloader 后就丢掉，返回的 runtime 与你另建的求值 context
 *     不共享 GeneratedResources → evaluate 报 "Failed to retrieve EfestoOutput"。
 * 所以这里手动串同一个 classloader（本类 register/score 两段）：
 *   ① 编译进一个**自己持有**的 `MemoryCompilerClassLoader`（生成的类字节码进它）；
 *   ② 求值时用**同一个** classloader 建 `PMMLRuntimeContextImpl`；
 *   ③ 编译产物的 `generatedResourcesMap` 只存在编译 context 对象里、不会写进 classloader，
 *      而求值 context 是靠 scan classloader 的 IndexFile 来填这张表的（standalone 下扫不到），
 *      于是把编译产物的 map **直接灌进求值 context**（`EfestoRuntimeContextImpl.generatedResourcesMap`
 *      是 protected final 的可变 Map，没有公开注入口，只能反射 putAll）。
 * 反射仅此一处、锁死 8.44.2，是 trusty standalone API 缺一个"用刚编译的产物建 context"公开口子的补丁。
 *
 * 模型编译一次（构造时），`MemoryCompilerClassLoader` + 产物 map 只读共享、跨请求复用；每次请求只
 * 新建轻量的 requestData + context 求值，线程安全。
 */
@Service
public class PmmlService {

    /** 反射注入点：EfestoRuntimeContextImpl.generatedResourcesMap（protected final 可变 Map）。 */
    private static final Field RESOURCES_FIELD = resourcesField();

    private final Map<String, CompiledModel> registry = new LinkedHashMap<>();

    public PmmlService() {
        // 构造时编译两个模型：一个评分卡（Scorecard）、一个线性回归（PMML 通用模型的代表）
        register("credit-scorecard", "credit-scorecard.pmml", "CreditScorecard");
        register("risk-regression", "risk-regression.pmml", "RiskScore");
    }

    /** 编译一个 classpath 上的 .pmml：拷临时文件（jar-safe）→ 编译进独立 classloader → 缓存产物。 */
    private void register(String key, String resource, String modelName) {
        try {
            Path tmp = Files.createTempDirectory("pmml-" + key).resolve(resource);
            try (InputStream in = getClass().getResourceAsStream("/pmml/" + resource)) {
                if (in == null) {
                    throw new IllegalStateException("找不到 PMML 资源 /pmml/" + resource);
                }
                Files.copy(in, tmp);
            }
            KieMemoryCompiler.MemoryCompilerClassLoader classLoader =
                    new KieMemoryCompiler.MemoryCompilerClassLoader(Thread.currentThread().getContextClassLoader());
            CompilationManager compilationManager = SPIUtils.getCompilationManager(true).orElseThrow();
            PMMLCompilationContextImpl compileCtx = new PMMLCompilationContextImpl(resource, classLoader);
            compilationManager.processResource(compileCtx, new EfestoFileResource(tmp.toFile()));
            registry.put(key, new CompiledModel(modelName, resource, classLoader, compileCtx.getGeneratedResourcesMap()));
            System.out.println("[PmmlService] 编译 PMML 模型 '" + key + "' (" + modelName + ") OK");
        } catch (IOException e) {
            throw new UncheckedIOException("编译 PMML 模型 " + key + " 失败", e);
        }
    }

    public Set<String> models() {
        return registry.keySet();
    }

    /** 用指定模型对一组输入求值，返回 resultCode + 结果变量（含 predictedValue / reasonCode 等 OutputField）。 */
    public ScoreResult score(String modelKey, Map<String, Object> inputs) {
        CompiledModel model = registry.get(modelKey);
        if (model == null) {
            throw new IllegalArgumentException("未知 PMML 模型: " + modelKey + "（可用: " + registry.keySet() + "）");
        }
        PMMLRequestDataBuilder request = new PMMLRequestDataBuilder("req-" + modelKey, model.modelName());
        inputs.forEach((k, v) -> {
            // PMML continuous 字段是 double；数字统一转 Double，其余按字符串（categorical）
            if (v instanceof Number n) {
                request.addParameter(k, n.doubleValue(), Double.class);
            } else if (v != null) {
                request.addParameter(k, v.toString(), String.class);
            }
        });

        PMMLRuntime runtime = new PMMLRuntimeInternalImpl();
        PMMLRuntimeContext ctx = new PMMLRuntimeContextImpl(request.build(), model.fileName(), model.classLoader());
        seedGeneratedResources(ctx, model.generatedResources());

        PMML4Result result = runtime.evaluate(model.modelName(), ctx);
        return new ScoreResult(model.modelName(), result.getResultCode(), result.getResultVariables());
    }

    /** 把编译产物的 generatedResourcesMap 灌进求值 context（standalone 下 context 自己 scan 不到）。 */
    @SuppressWarnings("unchecked")
    private static void seedGeneratedResources(PMMLRuntimeContext ctx, Map<String, GeneratedResources> compiled) {
        try {
            ((Map<String, GeneratedResources>) RESOURCES_FIELD.get(ctx)).putAll(compiled);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("注入 PMML generatedResourcesMap 失败", e);
        }
    }

    private static Field resourcesField() {
        try {
            Field f = EfestoRuntimeContextImpl.class.getDeclaredField("generatedResourcesMap");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record CompiledModel(String modelName, String fileName,
                                 KieMemoryCompiler.MemoryCompilerClassLoader classLoader,
                                 Map<String, GeneratedResources> generatedResources) {}

    public record ScoreResult(String model, String resultCode, Map<String, Object> variables) {}
}
