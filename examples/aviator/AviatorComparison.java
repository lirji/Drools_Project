/*
 * Aviator 独立对照程序，用于比较 Aviator 与 Drools 的能力边界。
 *
 * 这是个 standalone 文件，故意放在 Maven 源码根 (src/main/java) 之外，
 * 这样不会被 ./mvnw compile 编译，也不会给平台构建引入 Aviator 依赖。
 *
 * ── 怎么跑 ────────────────────────────────────────────────────────
 * 1. 下一个 Aviator jar（5.x 版本，本示例按 5.4.3 写）：
 *      https://repo1.maven.org/maven2/com/googlecode/aviator/aviator/5.4.3/aviator-5.4.3.jar
 *    Aviator 5.x 还需要 slf4j-api（编译能过，运行只是少日志）。
 * 2. 编译 + 运行：
 *      javac -cp aviator-5.4.3.jar AviatorComparison.java
 *      java  -cp .:aviator-5.4.3.jar AviatorComparison
 *    （Windows 上 classpath 分隔符是 ; 不是 :）
 *
 * 如果哪天真要并进本 Spring Boot 项目，在 pom.xml 加：
 *      <dependency>
 *        <groupId>com.googlecode.aviator</groupId>
 *        <artifactId>aviator</artifactId>
 *        <version>5.4.3</version>
 *      </dependency>
 *
 * ── 对照点（呼应 docs/drools-vs-aviator.md）─────────────────────────
 * Aviator 就是个"表达式求值引擎"：给字符串 + 变量，算一个值。
 * 没有 working memory、没有 agenda、没有规则互相触发——这些是 Drools 的活。
 * 下面 5 段展示 Aviator 各自擅长的：编译缓存 / 变量绑定 / 对象属性 /
 * 自定义函数 / 沙箱安全。最后一段用 Step 2 的折扣场景，直接跟 Drools 对比。
 */

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.EvalMode;
import com.googlecode.aviator.Feature;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.function.FunctionUtils;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorRuntimeJavaType;

import java.util.HashMap;
import java.util.Map;

public class AviatorComparison {

    public static void main(String[] args) {
        example1_basicExecute();
        example2_compileAndCache();
        example3_objectProperty();
        example4_customFunction();
        example5_sandbox();
        example6_discountVsDrools();
    }

    /* ── 1. 最朴素用法：execute(表达式, 变量Map) ───────────────────────
     * 一次性求值，引擎内部自动编译。返回 Object，按表达式实际结果类型。 */
    static void example1_basicExecute() {
        System.out.println("\n=== 1. 基本求值 ===");
        Map<String, Object> env = new HashMap<>();
        env.put("age", 20);
        env.put("vip", true);

        Object r = AviatorEvaluator.execute("age >= 18 && vip", env);
        System.out.println("age>=18 && vip => " + r);              // true

        // 数值默认走 BigDecimal/BigInteger（高精度），算钱不丢精度
        Object money = AviatorEvaluator.execute("1000 * 0.9");
        System.out.println("1000 * 0.9 => " + money + " (" + money.getClass().getSimpleName() + ")");
    }

    /* ── 2. 编译一次、反复执行：Expression 缓存 ───────────────────────
     * 同一表达式要跑很多遍（比如每个请求都算），先 compile 成 Expression
     * 复用，省掉重复解析。compile(expr, true) 还会进 Aviator 全局缓存。
     * 对照 Drools：KieBase 编译贵所以注成单例；Aviator 这里是 Expression 复用。*/
    static void example2_compileAndCache() {
        System.out.println("\n=== 2. 编译缓存 ===");
        Expression compiled = AviatorEvaluator.compile("amount > threshold ? amount * 0.9 : amount", true);

        for (int amount : new int[]{800, 1500}) {
            Map<String, Object> env = new HashMap<>();
            env.put("amount", amount);
            env.put("threshold", 1000);
            System.out.println("amount=" + amount + " => " + compiled.execute(env));
        }
    }

    /* ── 3. 对象属性访问：env 里塞 Java Bean，表达式里用 . 取字段 ──────
     * customer.vipLevel 会反射取 getVipLevel()/字段。嵌套也行。
     * 这点跟 Drools LHS 的 Customer(vipLevel >= 3) 有点像，但 Aviator
     * 只是"取值算表达式"，不会因为字段变化去重新触发任何规则。 */
    static void example3_objectProperty() {
        System.out.println("\n=== 3. 对象属性 ===");
        Map<String, Object> env = new HashMap<>();
        env.put("customer", new Customer("Alice", 3));

        Object r = AviatorEvaluator.execute("customer.vipLevel >= 2 ? '高级会员' : '普通'", env);
        System.out.println("customer.vipLevel=3 => " + r);
    }

    /* ── 4. 自定义函数：把 Java 逻辑暴露给表达式 ──────────────────────
     * 业务方在表达式里写 discount(amount, level)，真正算法在 Java 这边，
     * 既给了配置灵活性、又不让表达式里写复杂逻辑。 */
    static void example4_customFunction() {
        System.out.println("\n=== 4. 自定义函数 ===");
        AviatorEvaluator.addFunction(new DiscountFunction());

        Map<String, Object> env = new HashMap<>();
        env.put("amount", 2000);
        env.put("level", 3);
        Object r = AviatorEvaluator.execute("discount(amount, level)", env);
        System.out.println("discount(2000, level=3) => " + r);
    }

    /* ── 5. 沙箱 / 安全：限制表达式能力，防注入 ──────────────────────
     * 这是 Aviator 相对 Drools 的优势（见 docs/drools-vs-aviator.md）：
     * 当表达式来自用户输入/不可信来源，用受限 feature 集创建实例，
     * 禁掉 new、模块导入等危险能力。Drools 的 RHS 是裸 Java，没这层闸。*/
    static void example5_sandbox() {
        System.out.println("\n=== 5. 沙箱安全 ===");
        // 只放行最基本的运算/三元/if，不含 NewInstance、Module 等
        AviatorEvaluatorInstance sandbox = AviatorEvaluator.newInstance(
                EvalMode.INTERPRETER,
                Feature.asSet(Feature.If, Feature.Return));

        // 正常表达式照跑
        Object ok = sandbox.execute("1 + 2 * 3");
        System.out.println("sandbox: 1 + 2 * 3 => " + ok);

        // 试图用 new 创建对象 —— 受限实例会在编译期就拒绝
        try {
            sandbox.execute("new java.io.File('/etc/passwd')");
            System.out.println("sandbox: 居然放行了 new ?!（该实例 feature 配置有误）");
        } catch (Exception e) {
            System.out.println("sandbox: new 被拦截 => " + e.getClass().getSimpleName());
        }
    }

    /* ── 6. 折扣场景：跟 Drools Step 2 同一道题 ───────────────────────
     * Drools 版：discountKBase 里多条 DRL 规则，引擎按 salience 调度、
     *            规则叠加，改 finalAmount 还要小心 update() 死循环（CLAUDE.md 坑 3）。
     * Aviator 版：一行表达式，纯求值、无副作用、无触发顺序问题。
     *
     * 结论：单条独立折扣规则，Aviator 又短又安全；
     *      但一旦规则要互相叠加/级联/聚合，Aviator 就得自己写编排，该上 Drools。*/
    static void example6_discountVsDrools() {
        System.out.println("\n=== 6. 折扣（对照 Drools Step 2）===");
        // 满 1000 打 9 折；VIP(level>=3) 再叠加 95 折
        String expr = "let base = amount >= 1000 ? amount * 0.9 : amount; "
                    + "level >= 3 ? base * 0.95 : base";
        Expression compiled = AviatorEvaluator.compile(expr, true);

        printDiscount(compiled, 800, 1);    // 不满减、非VIP
        printDiscount(compiled, 1500, 1);   // 满减、非VIP
        printDiscount(compiled, 1500, 3);   // 满减 + VIP 叠加
    }

    static void printDiscount(Expression compiled, int amount, int level) {
        Map<String, Object> env = new HashMap<>();
        env.put("amount", amount);
        env.put("level", level);
        System.out.printf("amount=%-5d level=%d => 实付 %s%n", amount, level, compiled.execute(env));
    }

    /* ── 配套类型 ───────────────────────────────────────────────── */

    /** 给第三个示例反射取属性用的简单 Bean。 */
    public static class Customer {
        private final String name;
        private final int vipLevel;

        public Customer(String name, int vipLevel) {
            this.name = name;
            this.vipLevel = vipLevel;
        }

        public String getName() { return name; }
        public int getVipLevel() { return vipLevel; }
    }

    /** 自定义函数 discount(amount, level)：满 1000 九折，VIP(level>=3) 再 95 折。 */
    public static class DiscountFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "discount";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject arg1, AviatorObject arg2) {
            Number amount = FunctionUtils.getNumberValue(arg1, env);
            Number level = FunctionUtils.getNumberValue(arg2, env);

            double result = amount.doubleValue();
            if (result >= 1000) {
                result *= 0.9;
            }
            if (level.intValue() >= 3) {
                result *= 0.95;
            }
            return AviatorRuntimeJavaType.valueOf(result);
        }
    }
}
