package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.DistrictRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 把随包的行政区划字典 {@code data/china-district.csv} 落进 {@code sys_district}。
 *
 * <p><b>为什么是启动期 seeder 而不是一份 .sql</b>：本仓库 console 是唯一 DDL 执行者，表由
 * {@link com.lrj.drools.activity.persistence.DistrictEntity} 经 {@code ddl-auto} 生成——
 * 建表时机在应用启动之后，而 {@code deploy/mysql-init/} 里的脚本只在 MySQL 容器<b>首次初始化</b>时执行，
 * 那会儿表还不存在。再加上本地还有 h2 profile 与内存库测试，落库入口放在应用侧才能一条路覆盖全部环境。
 *
 * <p><b>数据出处</b>（换源前请先读 {@code examples/district-data/build-district-csv.py} 的头注释，
 * 那里有完整的选型记录）：结构取
 * <a href="https://github.com/xihan123/gb2260">xihan123/gb2260</a> 的 {@code active} 行（民政部/GB-T 2260 沿革口径，
 * 覆盖到 2025 年，CC0），拼音与简称取
 * <a href="https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov">xiangyuecn/AreaCity-JsSpider-StatsGov</a>
 * （2026-04-03 采集，MIT）按 6 位代码左连接补齐。
 *
 * <p><b>最常被引用的那份（modood，国家统计局 2023-06-30）刻意没用</b>：它停更三年，
 * 今天对重庆是**事实错误**——2025-11-06 国务院批复撤销江北区、渝北区设立两江新区，
 * 民政部随即废止 {@code 500105} / {@code 500112} 并编制 {@code 500157}，而那份数据里那两个区还在。
 * 字典比现实晚三年，运营就会把活动投到已经不存在的行政区上。
 * {@code DistrictSeederTest} 的金丝雀是**双向**的：{@code 500157} 必须在、{@code 500105/500112} 必须不在——
 * 只查"该有的在不在"照不出"该没的还在"这一类陈旧。
 *
 * <p>幂等：仅当 {@code sys_district} 为空时落库；已有数据但行数与随包数据集对不上时打 warn
 * （那通常意味着上一次只落了一半，或者有人换了数据集却没清表）。
 * 整批插入包在一个事务里——半张字典比空字典更难发现。
 *
 * <p>开关 {@code activity.marketing.seed-district-data=true}（console 的 application.yml 里开着）。
 * 测试默认不开，省掉每个 Spring 上下文 3000+ 行的插入。
 */
@Component
@ConditionalOnProperty(name = "activity.marketing.seed-district-data", havingValue = "true")
public class DistrictSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DistrictSeeder.class);

    /**
     * 随包数据集。生成方式见 {@code examples/district-data/build-district-csv.py}。
     *
     * <p>路径<b>刻意不叫 {@code data/}</b>：仓库根 {@code .gitignore} 里有一条无路径前缀的 {@code data/}
     * （本意是忽略 Step 10 的 H2 文件库目录），它会连带吃掉<b>任意层级</b>叫 data 的目录——
     * 数据集放进去后本机一切正常、测试全绿，但它根本没进版本库，别人 clone 下来启动即
     * {@code FileNotFoundException}。放在 {@code district/} 下绕开这条规则。
     */
    static final String RESOURCE = "district/china-district.csv";

    /** CSV 列序，与下面的 INSERT 一一对应。改这里必须同步改生成脚本。 */
    private static final String[] COLUMNS = {
            "code", "name", "short_name", "district_level", "parent_code",
            "province_code", "city_code", "full_name", "pinyin", "pinyin_initial", "sort_no"
    };

    private static final String INSERT = "insert into sys_district("
            + String.join(",", COLUMNS) + ") values (?,?,?,?,?,?,?,?,?,?,?)";

    /** 每批行数。3212 行一次性 addBatch 也扛得住，分批只是别让单条 SQL 报文无谓地大。 */
    private static final int BATCH_SIZE = 500;

    private final DistrictRepository repo;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DistrictSeeder(DistrictRepository repo, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(String... args) throws IOException {
        // 本表没有 @TenantId（行政区划是国家标准不是租户数据），所以这里**不需要**像
        // CatalogDataSeeder 那样套 TenantContext——套了也不会被写进任何谓词。
        List<Object[]> rows = parse();

        long existing = repo.count();
        if (existing > 0) {
            if (existing != rows.size()) {
                log.warn("[DistrictSeeder] sys_district 已有 {} 行，与随包数据集的 {} 行对不上；"
                        + "若是换了数据集，请清空该表后重启（本次跳过落库）。", existing, rows.size());
            }
            return;
        }

        long t0 = System.currentTimeMillis();
        tx.executeWithoutResult(status -> {
            for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
                jdbc.batchUpdate(INSERT, rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())));
            }
        });
        log.info("[DistrictSeeder] 已落库行政区划字典 {} 行（耗时 {} ms）。省/市/区县三级，6 位代码即 userDistrictId 的取值域。",
                rows.size(), System.currentTimeMillis() - t0);
    }

    /**
     * 解析随包 CSV。
     *
     * <p>刻意<b>没有</b>引入 CSV 库，也没做引号/转义处理：生成脚本已经断言过任何字段都不含逗号、
     * 引号与换行（行政区划名称里本来就不该有），所以这里 {@code split(",", -1)} 是安全的。
     * 代价是这个假设必须留在两侧——脚本里那条 assert 一旦被删掉，这里就会静默错位，
     * 因此下面用**列数校验**兜底：对不上就抛，绝不半懂不懂地插进去。
     */
    private List<Object[]> parse() throws IOException {
        List<Object[]> rows = new ArrayList<>();
        ClassPathResource res = new ClassPathResource(RESOURCE);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) throw new IllegalStateException(RESOURCE + " 是空文件");
            String[] headerCols = header.split(",", -1);
            if (headerCols.length != COLUMNS.length) {
                throw new IllegalStateException(RESOURCE + " 表头列数 " + headerCols.length
                        + " ≠ 预期 " + COLUMNS.length + "：" + header);
            }
            String line;
            for (int no = 2; (line = r.readLine()) != null; no++) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length != COLUMNS.length) {
                    throw new IllegalStateException(RESOURCE + " 第 " + no + " 行列数 "
                            + f.length + " ≠ 预期 " + COLUMNS.length + "：" + line);
                }
                rows.add(new Object[]{
                        f[0], f[1], f[2], Integer.valueOf(f[3]), blankToNull(f[4]),
                        f[5], blankToNull(f[6]), f[7], blankToNull(f[8]), blankToNull(f[9]),
                        Integer.valueOf(f[10])
                });
            }
        }
        if (rows.isEmpty()) throw new IllegalStateException(RESOURCE + " 没有数据行");
        return rows;
    }

    /** 省级行没有 parent_code / city_code：必须落 NULL 而不是空串，否则「省级」在 SQL 里判不出来。 */
    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
