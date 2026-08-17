package com.lrj.drools.service;

import com.lrj.drools.domain.Cart;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieBuilderImpl;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieScanner;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.event.kiescanner.KieScannerEventListener;
import org.kie.api.event.kiescanner.KieScannerStatusChangeEvent;
import org.kie.api.event.kiescanner.KieScannerUpdateResultsEvent;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.scanner.KieMavenRepository;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Step 16: KieScanner + KJAR —— Step 9 热加载的"生产正解版"。
 *
 * ───────────── 跟 Step 9 (HotReloadService) 的本质区别 ─────────────
 *
 * Step 9: DRL 字符串 → KieHelper 直接编译成 KieBase 缓存进 Map。规则没有版本、没有
 *          产物、跟 Maven 无关; 应用自己决定何时 upsert。胜在简单, 但规则发布是"应用内
 *          的临时态", 重启即丢, 也没有版本回滚 / 多实例一致性。
 *
 * Step 16: DRL → 打成 **KJAR** (一个带 kmodule.xml + pom 的标准 Maven 构件) → 装进
 *          Maven 仓库 (这里用本地 ~/.m2)。KieContainer 不再绑 classpath, 而是绑一个
 *          **ReleaseId** (group:artifact:version)。KieScanner 轮询仓库, 发现同 GAV 有
 *          新内容就**热替换** KieBase —— 应用零改动、零重启。
 *
 * 这才是"规则跟代码独立发版"的工业路径: 规则团队 mvn deploy 新 KJAR → 所有跑着的服务
 * 实例的 KieScanner 自动拉到 → 统一切换。CLAUDE.md 里 Step 9 那条注释说的
 * "@Scheduled 轮询 = KieScanner 等价物", 这一步就是那个真家伙。
 *
 * ───────────── 为什么用 SNAPSHOT 版本 ─────────────
 *
 * KieScanner 的"发现新版本"对 release 版本 (1.0.0) 是不触发的 (固定版本内容不可变是
 * Maven 的契约)。SNAPSHOT (1.0.0-SNAPSHOT) 允许同 GAV 内容滚动更新, scanNow() 会重新
 * 解析并比对时间戳 → 命中替换。所以教学 demo 固定用一个 SNAPSHOT GAV, 反复 install
 * 新内容到它上面。生产里规则发版会用递增 release 版本 + KieContainer.updateToVersion()。
 */
@Service
public class ScannerService {

    // 固定 GAV: 反复往这个 SNAPSHOT 上 install 新内容, 让 KieScanner 滚动替换
    private static final String GROUP_ID = "com.lrj.rules";
    private static final String ARTIFACT_ID = "scanner-cart-rules";
    private static final String VERSION = "1.0.0-SNAPSHOT";

    // KJAR 内部 kmodule 的拓扑 (跟主项目 META-INF/kmodule.xml 无关, 完全独立)
    private static final String KBASE = "scannerKBase";
    private static final String KSESSION = "scannerSession";
    private static final String PACKAGE = "rules.scanner";

    private final KieServices ks = KieServices.get();
    private final ReleaseId releaseId = ks.newReleaseId(GROUP_ID, ARTIFACT_ID, VERSION);

    // 首次 deploy 时懒创建 (newKieContainer 要求构件已存在于仓库)
    private KieContainer container;
    private KieScanner scanner;
    private int generation = 0;          // 每次 deploy / updateToVersion +1, 标记当前 live 的内容代次
    private boolean polling = false;     // 是否开了 KieScanner 自动轮询
    private int explicitVersion = 0;     // updateToVersion 走的固定 release 计数 (1.0.1 / 1.0.2 ...)

    // KieScannerEventListener 攒到的热替换事件 (section 8 的 ⬜️: 监听规则热替换)。
    // 只有走 scanner 的路径 (scanNow / 自动轮询) 会触发; updateToVersion 是手动切换, 不经 scanner。
    private final List<ScanEvent> scanEvents = new CopyOnWriteArrayList<>();

    /**
     * 把一段 DRL 打成 KJAR、装进本地 Maven 仓库, 并让运行中的 KieContainer 切到新内容。
     *
     *   1. KieFileSystem + KieModuleModel 程序化构建 KJAR (kmodule.xml + pom + DRL)
     *   2. buildAll 编译; 有 ERROR 直接抛 (带行号), KJAR 不落地
     *   3. KieMavenRepository.installArtifact 装进 ~/.m2
     *   4. 首次: newKieContainer(releaseId) + newKieScanner; 之后: scanner.scanNow() 热替换
     */
    public synchronized DeployResult deploy(String drl) {
        // 1~3. 编译 + install 到固定 SNAPSHOT GAV
        buildAndInstall(drl, releaseId);
        generation++;

        // 4. 首次创建 container + scanner (顺带挂 KieScannerEventListener); 之后 scanNow 热替换
        String action;
        if (container == null) {
            container = ks.newKieContainer(releaseId);
            scanner = ks.newKieScanner(container);
            scanner.addListener(new RecordingScannerListener());   // 监听后续 scanNow/轮询的热替换事件
            action = "container 首次创建 (绑定 " + releaseId + ")";
        } else {
            scanner.scanNow();   // ← KieScanner 重新解析 SNAPSHOT, 命中新内容则替换 KieBase (触发 listener)
            action = "scanner.scanNow() 热替换 KieBase (运行中 container 无需重建)";
        }
        return new DeployResult(releaseId.toString(), generation, polling, action);
    }

    /**
     * Step 16 扩展: 显式版本切换 (KieContainer.updateToVersion)。
     *
     * 跟 deploy 的 SNAPSHOT + scanNow 路径对照:
     *   - deploy: 反复往同一个 1.0.0-SNAPSHOT 上 install, 靠 scanner 比对时间戳滚动替换 (自动)
     *   - updateToVersion: 每次 install 到一个**新的固定 release** (1.0.1 / 1.0.2 ...),
     *     再 container.updateToVersion(newReleaseId) **手动切**过去
     *
     * 语义差别: 固定 release 内容不可变、可精确回滚到任一历史版本; updateToVersion 是"显式指定
     * 切到哪一版", 不经 scanner (所以**不触发 KieScannerEventListener**), 适合"规则发版有版本号
     * 治理、要能回退"的场景。返回的 Results 带编译期消息 (这里编译已在 buildAndInstall 校验过)。
     */
    public synchronized UpdateResult updateToVersion(String drl) {
        if (container == null) {
            throw new IllegalStateException("还没 deploy 任何 KJAR, 先 POST /scanner/deploy 建 container");
        }
        explicitVersion++;
        ReleaseId newId = ks.newReleaseId(GROUP_ID, ARTIFACT_ID, "1.0." + explicitVersion);
        buildAndInstall(drl, newId);

        Results results = container.updateToVersion(newId);   // 手动切到新固定版本, 不经 scanner
        generation++;
        boolean hasError = results != null && results.hasMessages(Message.Level.ERROR);
        return new UpdateResult(newId.toString(), generation, hasError,
                "container.updateToVersion(" + newId + ") 显式切换 (不经 scanner)");
    }

    /** 编译一段 DRL 成 KJAR 并 install 到指定 releaseId; 编译错误抛 IllegalArgumentException (带行号)。 */
    private void buildAndInstall(String drl, ReleaseId rid) {
        // 1. 程序化构建 KJAR
        KieFileSystem kfs = ks.newKieFileSystem();

        KieModuleModel kmm = ks.newKieModuleModel();
        KieBaseModel kbm = kmm.newKieBaseModel(KBASE).addPackage(PACKAGE);
        kbm.newKieSessionModel(KSESSION);
        kfs.writeKModuleXML(kmm.toXML());

        kfs.generateAndWritePomXML(rid);   // KJAR 自带 pom.xml + pom.properties
        kfs.write("src/main/resources/" + PACKAGE.replace('.', '/') + "/scanner.drl",
                ks.getResources().newByteArrayResource(drl.getBytes(StandardCharsets.UTF_8)));

        // 2. 编译校验
        KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
        if (kb.getResults().hasMessages(Message.Level.ERROR)) {
            String detail = kb.getResults().getMessages(Message.Level.ERROR).stream()
                    .map(m -> "line " + m.getLine() + ": " + m.getText())
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("KJAR 编译失败:\n" + detail);
        }
        InternalKieModule kieModule = (InternalKieModule) kb.getKieModule();

        // 3. 装进本地 Maven 仓库 (~/.m2/repository/com/lrj/rules/...)
        KieMavenRepository repo = KieMavenRepository.getKieMavenRepository();
        repo.installArtifact(rid, kieModule, pomFile(rid));
    }

    /** KieScannerEventListener: 把 scanner 的状态变化 / 更新结果攒进 scanEvents (section 8 ⬜️)。 */
    private final class RecordingScannerListener implements KieScannerEventListener {
        @Override
        public void onKieScannerStatusChangeEvent(KieScannerStatusChangeEvent event) {
            scanEvents.add(new ScanEvent("STATUS_CHANGE", String.valueOf(event.getStatus())));
        }

        @Override
        public void onKieScannerUpdateResultsEvent(KieScannerUpdateResultsEvent event) {
            boolean hasError = event.getResults() != null && event.getResults().hasMessages(Message.Level.ERROR);
            scanEvents.add(new ScanEvent("UPDATE_RESULTS", hasError ? "编译有错误" : "更新成功"));
        }
    }

    /** 返回 KieScannerEventListener 攒到的热替换事件快照 (最新在后)。 */
    public List<ScanEvent> scanEvents() {
        return List.copyOf(scanEvents);
    }

    /** 用当前 live 的 KieBase 跑一个 cart。container 关联的是 scanner 最近一次替换后的 KieBase。 */
    public RunResult run(Cart cart) {
        if (container == null) {
            throw new IllegalStateException("还没 deploy 任何 KJAR, 先 POST /scanner/deploy");
        }
        KieSession session = container.newKieSession(KSESSION);
        try {
            session.insert(cart);
            int fired = session.fireAllRules();
            return new RunResult(cart, fired, generation);
        } finally {
            session.dispose();
        }
    }

    /**
     * 开启 KieScanner 自动轮询。这才是生产形态: 规则团队 deploy 新 KJAR 后, 不需要任何人
     * 调 scanNow, 引擎自己在 intervalMillis 周期内拉到并替换。intervalMillis 必须 > 0。
     */
    public synchronized String startPolling(long intervalMillis) {
        if (container == null) {
            throw new IllegalStateException("还没 deploy 任何 KJAR, 无法开轮询");
        }
        scanner.start(intervalMillis);
        polling = true;
        return "KieScanner 已开始每 " + intervalMillis + "ms 轮询 " + releaseId;
    }

    public synchronized String stopPolling() {
        if (scanner != null && polling) {
            scanner.stop();
            polling = false;
            return "KieScanner 轮询已停止";
        }
        return "轮询本来就没开";
    }

    public Status status() {
        return new Status(releaseId.toString(), container != null, generation, polling);
    }

    /** installArtifact 需要一个 pom 文件; 用 KieBuilderImpl 生成标准 pom 文本写临时文件。 */
    private File pomFile(ReleaseId rid) {
        try {
            File f = File.createTempFile("pom-" + ARTIFACT_ID + "-", ".xml");
            f.deleteOnExit();
            Files.writeString(f.toPath(), KieBuilderImpl.generatePomXml(rid));
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException("生成 pom 临时文件失败", e);
        }
    }

    public record DeployResult(String releaseId, int generation, boolean polling, String action) {}
    public record RunResult(Cart cart, int firedCount, int generation) {}
    public record Status(String releaseId, boolean containerReady, int generation, boolean polling) {}
    public record UpdateResult(String releaseId, int generation, boolean hasError, String action) {}
    public record ScanEvent(String type, String detail) {}
}
