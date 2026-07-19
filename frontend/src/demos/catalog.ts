// 演示台目录 —— 由 static/assets/examples.js 机械转写（F2）。纯数据，无逻辑。
// 结构见下方类型；examples[].body 为 null 表示无请求体。改后端端点时同步此文件 + dev proxy 前缀清单。
export interface DemoExample { label: string; pathParams?: Record<string, string>; body: unknown | null }
export interface DemoDef {
  id: string; group: string; step: number; title: string; method: string; path: string;
  desc: string; summary: string; responseType?: string;
  pathParams?: Array<{ name: string; label: string; placeholder?: string }>;
  examples: DemoExample[];
}
export interface DemoGroup { id: string; title: string; subtitle: string; external?: boolean }

export const GROUPS: DemoGroup[] = [
  {
    "id": "basics",
    "title": "入门 · 折扣",
    "subtitle": "Step 1–3 · facts / salience / accumulate"
  },
  {
    "id": "reasoning",
    "title": "推理 · 流水线",
    "subtitle": "Step 4–6 · not/exists / agenda-group / 审计"
  },
  {
    "id": "table",
    "title": "决策表 · 无状态",
    "subtitle": "Step 7 · 11 · 决策表 / StatelessKieSession"
  },
  {
    "id": "event",
    "title": "事件 · 后向链",
    "subtitle": "Step 8 · 13 · CEP 滑窗 / query"
  },
  {
    "id": "hot",
    "title": "热加载 · 版本发布",
    "subtitle": "Step 9 · 16 · 运行时编译 / KieScanner"
  },
  {
    "id": "ops",
    "title": "持久化 · 护栏 · 指标",
    "subtitle": "Step 10 · 12 · 14 · 15"
  },
  {
    "id": "model",
    "title": "DMN · 营销活动",
    "subtitle": "Step 17 · 18 · FEEL 决策链 / 资格判定"
  },
  {
    "id": "activity",
    "title": "活动营销",
    "subtitle": "报表式配置 · 条件树 · 优惠验证",
    "external": true
  }
];

export const DEMOS: DemoDef[] = [
  {
    "id": "hello",
    "group": "basics",
    "step": 1,
    "title": "Hello World",
    "method": "POST",
    "path": "/hello",
    "summary": "generic",
    "desc": "插一个 Customer，跑 helloKBase，返回命中的规则条数。一个 fact 命中多条规则会全部触发。",
    "examples": [
      {
        "label": "成年新用户",
        "body": {
          "name": "Alice",
          "age": 20,
          "vipLevel": 0,
          "yearsSinceRegistration": 0
        }
      },
      {
        "label": "老年 VIP",
        "body": {
          "name": "Bob",
          "age": 65,
          "vipLevel": 1,
          "yearsSinceRegistration": 5
        }
      }
    ]
  },
  {
    "id": "discount-calculate",
    "group": "basics",
    "step": 2,
    "title": "订单折扣",
    "method": "POST",
    "path": "/discount/calculate",
    "summary": "order",
    "desc": "Customer + Order 跑 discountKBase，salience 排序下逐层叠加折扣，discountReasons 是命中账本。",
    "examples": [
      {
        "label": "VIP2+老用户+大单",
        "body": {
          "customer": {
            "name": "Alice",
            "age": 30,
            "vipLevel": 2,
            "yearsSinceRegistration": 4
          },
          "items": [
            {
              "name": "Laptop",
              "quantity": 1,
              "unitPrice": 600
            },
            {
              "name": "Mouse",
              "quantity": 2,
              "unitPrice": 30
            }
          ]
        }
      },
      {
        "label": "非会员小额（0 命中）",
        "body": {
          "customer": {
            "name": "Charlie",
            "age": 25,
            "vipLevel": 0,
            "yearsSinceRegistration": 0
          },
          "items": [
            {
              "name": "Pen",
              "quantity": 1,
              "unitPrice": 10
            }
          ]
        }
      },
      {
        "label": "VIP3 大客户",
        "body": {
          "customer": {
            "name": "Diana",
            "age": 40,
            "vipLevel": 3,
            "yearsSinceRegistration": 5
          },
          "items": [
            {
              "name": "Server",
              "quantity": 1,
              "unitPrice": 3000
            }
          ]
        }
      }
    ]
  },
  {
    "id": "cart-checkout",
    "group": "basics",
    "step": 3,
    "title": "购物车 (accumulate + modify)",
    "method": "POST",
    "path": "/cart/checkout",
    "summary": "cart",
    "desc": "按品类 accumulate 聚合；totalAmount>5000 时 modify 触发金卡级联，goldStatus 变 true 后再 9 折。",
    "examples": [
      {
        "label": "大额触发金卡级联",
        "body": {
          "customer": {
            "name": "Diana",
            "age": 40,
            "vipLevel": 2,
            "yearsSinceRegistration": 5
          },
          "items": [
            {
              "name": "Server",
              "quantity": 1,
              "unitPrice": 6000,
              "category": "ELECTRONICS"
            }
          ]
        }
      },
      {
        "label": "满5本书 + 电子3件",
        "body": {
          "customer": {
            "name": "Alice",
            "age": 30,
            "vipLevel": 1,
            "yearsSinceRegistration": 3
          },
          "items": [
            {
              "name": "小说A",
              "quantity": 3,
              "unitPrice": 40,
              "category": "BOOK"
            },
            {
              "name": "小说B",
              "quantity": 2,
              "unitPrice": 50,
              "category": "BOOK"
            },
            {
              "name": "耳机",
              "quantity": 1,
              "unitPrice": 400,
              "category": "ELECTRONICS"
            },
            {
              "name": "键盘",
              "quantity": 1,
              "unitPrice": 300,
              "category": "ELECTRONICS"
            },
            {
              "name": "鼠标",
              "quantity": 1,
              "unitPrice": 350,
              "category": "ELECTRONICS"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "risk-evaluate",
    "group": "reasoning",
    "step": 4,
    "title": "风控推荐 (not / exists)",
    "method": "POST",
    "path": "/risk/evaluate",
    "summary": "cart",
    "desc": "用 not/exists 做否定与存在判断；规则 insert Promotion 标记 fact，汇总进 recommendations。",
    "examples": [
      {
        "label": "电子无保险 → 推荐保险",
        "body": {
          "customer": {
            "name": "Alice",
            "age": 30,
            "vipLevel": 2,
            "yearsSinceRegistration": 4
          },
          "items": [
            {
              "name": "Laptop",
              "quantity": 1,
              "unitPrice": 600,
              "category": "ELECTRONICS"
            },
            {
              "name": "Mouse",
              "quantity": 1,
              "unitPrice": 30,
              "category": "ELECTRONICS"
            }
          ]
        }
      },
      {
        "label": "已含保险 → not 失配",
        "body": {
          "customer": {
            "name": "Bob",
            "age": 30,
            "vipLevel": 0,
            "yearsSinceRegistration": 2
          },
          "items": [
            {
              "name": "Phone",
              "quantity": 1,
              "unitPrice": 3000,
              "category": "ELECTRONICS"
            },
            {
              "name": "AppleCare+",
              "quantity": 1,
              "unitPrice": 299,
              "category": "INSURANCE"
            }
          ]
        }
      },
      {
        "label": "3 本书 + 新人 → exists 一次",
        "body": {
          "customer": {
            "name": "Charlie",
            "age": 22,
            "vipLevel": 0,
            "yearsSinceRegistration": 0
          },
          "items": [
            {
              "name": "Book A",
              "quantity": 1,
              "unitPrice": 40,
              "category": "BOOK"
            },
            {
              "name": "Book B",
              "quantity": 1,
              "unitPrice": 50,
              "category": "BOOK"
            },
            {
              "name": "Book C",
              "quantity": 1,
              "unitPrice": 60,
              "category": "BOOK"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "pipeline-run",
    "group": "reasoning",
    "step": 5,
    "title": "agenda-group 流水线",
    "method": "POST",
    "path": "/pipeline/run",
    "summary": "cart",
    "desc": "validate→discount→risk→notify 四阶段；setFocus 反向压栈 (LIFO)，notify 用 auto-focus 自动挂载。",
    "examples": [
      {
        "label": "VIP1 + 电子",
        "body": {
          "customer": {
            "name": "Alice",
            "age": 30,
            "vipLevel": 1,
            "yearsSinceRegistration": 2
          },
          "items": [
            {
              "name": "Laptop",
              "quantity": 1,
              "unitPrice": 600,
              "category": "ELECTRONICS"
            }
          ]
        }
      },
      {
        "label": "空购物车 → validate 拒单",
        "body": {
          "customer": {
            "name": "Bob",
            "age": 30,
            "vipLevel": 0,
            "yearsSinceRegistration": 0
          },
          "items": []
        }
      },
      {
        "label": "大额 → notify auto-focus",
        "body": {
          "customer": {
            "name": "Diana",
            "age": 40,
            "vipLevel": 3,
            "yearsSinceRegistration": 5
          },
          "items": [
            {
              "name": "Server",
              "quantity": 1,
              "unitPrice": 6000,
              "category": "ELECTRONICS"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "pipeline-audit",
    "group": "reasoning",
    "step": 6,
    "title": "规则可观测性 (auditTrail)",
    "method": "POST",
    "path": "/pipeline/audit",
    "summary": "audit",
    "desc": "跟流水线同逻辑，但响应带 auditTrail：从 fact insert 到 group 压栈/弹栈的完整事件时间线。",
    "examples": [
      {
        "label": "大额 VIP3（看栈时序）",
        "body": {
          "customer": {
            "name": "Diana",
            "age": 40,
            "vipLevel": 3,
            "yearsSinceRegistration": 5
          },
          "items": [
            {
              "name": "Server",
              "quantity": 1,
              "unitPrice": 6000,
              "category": "ELECTRONICS"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "decision-calculate",
    "group": "table",
    "step": 7,
    "title": "决策表 (Excel 维护)",
    "method": "POST",
    "path": "/decision/calculate",
    "summary": "cart",
    "desc": "VIP 折扣档位维护在 vip-discount.xls，业务方直接改表；启动时编译成 DRL 进 decisionKBase。",
    "examples": [
      {
        "label": "VIP2 → 9 折",
        "body": {
          "customer": {
            "name": "Alice",
            "vipLevel": 2,
            "age": 30,
            "yearsSinceRegistration": 0
          },
          "items": [
            {
              "name": "X",
              "quantity": 1,
              "unitPrice": 1000,
              "category": "ELECTRONICS"
            }
          ]
        }
      },
      {
        "label": "VIP4 → 8 折（表里新档位）",
        "body": {
          "customer": {
            "name": "Eve",
            "vipLevel": 4,
            "age": 50,
            "yearsSinceRegistration": 10
          },
          "items": [
            {
              "name": "Y",
              "quantity": 1,
              "unitPrice": 1000,
              "category": "ELECTRONICS"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "stateless-calculate",
    "group": "table",
    "step": 11,
    "title": "StatelessKieSession",
    "method": "POST",
    "path": "/stateless/calculate",
    "summary": "order",
    "desc": "复用 discountKBase 但走无状态 ksession，同输入结果跟 stateful 完全等价；API 一行 execute。",
    "examples": [
      {
        "label": "VIP2 老用户（同 Step 2）",
        "body": {
          "orderId": "o1",
          "customer": {
            "name": "Alice",
            "vipLevel": 2,
            "age": 35,
            "yearsSinceRegistration": 5
          },
          "items": [
            {
              "name": "x",
              "quantity": 1,
              "unitPrice": 1000,
              "category": "X"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "stateless-batch",
    "group": "table",
    "step": 11,
    "title": "无状态批处理",
    "method": "POST",
    "path": "/stateless/batch",
    "summary": "orderBatch",
    "desc": "一次提交 N 个 Order，stateless 单实例反复 execute，单笔间完全隔离、零清空成本。",
    "examples": [
      {
        "label": "3 单独立计算",
        "body": {
          "orders": [
            {
              "orderId": "b1",
              "customer": {
                "name": "Alice",
                "vipLevel": 3,
                "age": 35,
                "yearsSinceRegistration": 1
              },
              "items": [
                {
                  "name": "x",
                  "quantity": 1,
                  "unitPrice": 600,
                  "category": "X"
                }
              ]
            },
            {
              "orderId": "b2",
              "customer": {
                "name": "Bob",
                "vipLevel": 0,
                "age": 40,
                "yearsSinceRegistration": 5
              },
              "items": [
                {
                  "name": "y",
                  "quantity": 1,
                  "unitPrice": 300,
                  "category": "Y"
                }
              ]
            },
            {
              "orderId": "b3",
              "customer": {
                "name": "Cathy",
                "vipLevel": 1,
                "age": 28,
                "yearsSinceRegistration": 0
              },
              "items": [
                {
                  "name": "z",
                  "quantity": 1,
                  "unitPrice": 2500,
                  "category": "Z"
                }
              ]
            }
          ]
        }
      }
    ]
  },
  {
    "id": "fraud-check",
    "group": "event",
    "step": 8,
    "title": "CEP 滑窗风控",
    "method": "POST",
    "path": "/fraud/check",
    "summary": "fraud",
    "desc": "按 timestamp 排序推进 pseudo clock；同一 customer 5 分钟滑窗内 ≥3 单 → BurstAlert。",
    "examples": [
      {
        "label": "A: 3 单 5 分钟内 → 告警",
        "body": {
          "events": [
            {
              "orderId": "a1",
              "customerName": "Alice",
              "amount": 100,
              "timestamp": 0
            },
            {
              "orderId": "a2",
              "customerName": "Alice",
              "amount": 200,
              "timestamp": 60000
            },
            {
              "orderId": "a3",
              "customerName": "Alice",
              "amount": 300,
              "timestamp": 120000
            }
          ]
        }
      },
      {
        "label": "B: 第 3 单滑出窗口 → 无告警",
        "body": {
          "events": [
            {
              "orderId": "b1",
              "customerName": "Alice",
              "amount": 100,
              "timestamp": 0
            },
            {
              "orderId": "b2",
              "customerName": "Alice",
              "amount": 200,
              "timestamp": 60000
            },
            {
              "orderId": "b3",
              "customerName": "Alice",
              "amount": 300,
              "timestamp": 400000
            }
          ]
        }
      },
      {
        "label": "C: Alice 4 单 + Bob 2 单",
        "body": {
          "events": [
            {
              "orderId": "c1",
              "customerName": "Alice",
              "amount": 100,
              "timestamp": 0
            },
            {
              "orderId": "c2",
              "customerName": "Bob",
              "amount": 150,
              "timestamp": 30000
            },
            {
              "orderId": "c3",
              "customerName": "Alice",
              "amount": 200,
              "timestamp": 60000
            },
            {
              "orderId": "c4",
              "customerName": "Bob",
              "amount": 250,
              "timestamp": 90000
            },
            {
              "orderId": "c5",
              "customerName": "Alice",
              "amount": 300,
              "timestamp": 120000
            },
            {
              "orderId": "c6",
              "customerName": "Alice",
              "amount": 400,
              "timestamp": 180000
            }
          ]
        }
      }
    ]
  },
  {
    "id": "backward-contains",
    "group": "event",
    "step": 13,
    "title": "后向链 + query",
    "method": "POST",
    "path": "/backward/contains",
    "summary": "backward",
    "desc": "递归 query isContainedIn 反向证明包含关系（goal-driven pull），不走 fireAllRules。",
    "examples": [
      {
        "label": "Office→House→City→Country→Continent",
        "body": {
          "locations": [
            {
              "thing": "Office",
              "container": "House"
            },
            {
              "thing": "House",
              "container": "City"
            },
            {
              "thing": "City",
              "container": "Country"
            },
            {
              "thing": "Country",
              "container": "Continent"
            }
          ],
          "queries": [
            {
              "thing": "Office",
              "container": "Country"
            },
            {
              "thing": "Office",
              "container": "Continent"
            },
            {
              "thing": "House",
              "container": "Office"
            },
            {
              "thing": "City",
              "container": "House"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "hot-upsert",
    "group": "hot",
    "step": 9,
    "title": "规则热加载 · 编译上线",
    "method": "POST",
    "path": "/hot/upsert",
    "summary": "hotUpsert",
    "desc": "推 DRL 字符串运行时编译成 KieBase 缓存进 registry，同名替换；编译错误返回 400 + 行号。",
    "examples": [
      {
        "label": "v1: 任何 cart 打 7 折",
        "body": {
          "name": "flatDiscount",
          "drl": "package hot.flat;\nimport com.lrj.drools.domain.Cart;\nrule \"Flat 70%\"\n    when\n        $c: Cart()\n    then\n        $c.applyRatioDiscount(0.7, \"v1: flat 30 off\");\nend"
        }
      },
      {
        "label": "v2: 同名替换成 8 折",
        "body": {
          "name": "flatDiscount",
          "drl": "package hot.flat;\nimport com.lrj.drools.domain.Cart;\nrule \"Flat 80%\"\n    when\n        $c: Cart()\n    then\n        $c.applyRatioDiscount(0.8, \"v2: flat 20 off\");\nend"
        }
      },
      {
        "label": "编译错误 → 400",
        "body": {
          "name": "broken",
          "drl": "rule THIS IS SYNTAX BROKEN end"
        }
      }
    ]
  },
  {
    "id": "hot-run",
    "group": "hot",
    "step": 9,
    "title": "运行热加载规则",
    "method": "POST",
    "path": "/hot/run/{name}",
    "summary": "hotRun",
    "pathParams": [
      {
        "name": "name",
        "label": "规则名",
        "placeholder": "flatDiscount"
      }
    ],
    "desc": "用 registry 里 name 对应的 KieBase 跑 cart（先在上面 upsert 一个同名规则）。",
    "examples": [
      {
        "label": "跑 flatDiscount",
        "pathParams": {
          "name": "flatDiscount"
        },
        "body": {
          "customer": {
            "name": "A",
            "vipLevel": 0,
            "age": 30,
            "yearsSinceRegistration": 0
          },
          "items": [
            {
              "name": "x",
              "quantity": 1,
              "unitPrice": 1000,
              "category": "X"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "hot-list",
    "group": "hot",
    "step": 9,
    "title": "已注册规则名",
    "method": "GET",
    "path": "/hot/list",
    "summary": "generic",
    "desc": "列出当前 registry 里已编译缓存的规则名。",
    "examples": [
      {
        "label": "查询",
        "body": null
      }
    ]
  },
  {
    "id": "scanner-deploy",
    "group": "hot",
    "step": 16,
    "title": "KieScanner · 部署 KJAR",
    "method": "POST",
    "path": "/scanner/deploy",
    "summary": "scannerDeploy",
    "desc": "DRL 打成 KJAR 装进本地 ~/.m2，绑固定 SNAPSHOT ReleaseId；首次创建 container，否则 scanNow 热替换。",
    "examples": [
      {
        "label": "v1: 满 100 打 9 折",
        "body": {
          "drl": "package rules.scanner\nimport com.lrj.drools.domain.Cart;\nrule \"v1 9 fold\"\n when $c: Cart(totalAmount >= 100)\n then $c.applyRatioDiscount(0.9, \"v1: 9 fold\");\nend"
        }
      },
      {
        "label": "v2: 同 GAV 改成 8 折",
        "body": {
          "drl": "package rules.scanner\nimport com.lrj.drools.domain.Cart;\nrule \"v2 8 fold\"\n when $c: Cart(totalAmount >= 100)\n then $c.applyRatioDiscount(0.8, \"v2: 8 fold\");\nend"
        }
      },
      {
        "label": "编译错误 → 400",
        "body": {
          "drl": "this is not valid drl"
        }
      }
    ]
  },
  {
    "id": "scanner-run",
    "group": "hot",
    "step": 16,
    "title": "跑当前 live 规则",
    "method": "POST",
    "path": "/scanner/run",
    "summary": "scannerRun",
    "desc": "用当前 live KieBase 跑 cart，返回 cart + generation（内容代次，热替换后自增）。",
    "examples": [
      {
        "label": "跑 cart（先 deploy）",
        "body": {
          "customer": {
            "name": "A",
            "age": 30,
            "vipLevel": 0,
            "yearsSinceRegistration": 0
          },
          "items": [
            {
              "name": "x",
              "quantity": 1,
              "unitPrice": 200,
              "category": "X"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "scanner-poll-start",
    "group": "hot",
    "step": 16,
    "title": "开启自动轮询",
    "method": "POST",
    "path": "/scanner/poll/start",
    "summary": "generic",
    "desc": "开 KieScanner 后台周期轮询（生产形态，deploy 后无人值守自动生效）。",
    "examples": [
      {
        "label": "每 3 秒轮询",
        "body": {
          "intervalMillis": 3000
        }
      }
    ]
  },
  {
    "id": "scanner-poll-stop",
    "group": "hot",
    "step": 16,
    "title": "停止自动轮询",
    "method": "POST",
    "path": "/scanner/poll/stop",
    "summary": "generic",
    "desc": "停掉 KieScanner 的后台轮询线程。",
    "examples": [
      {
        "label": "停止",
        "body": null
      }
    ]
  },
  {
    "id": "scanner-status",
    "group": "hot",
    "step": 16,
    "title": "Scanner 状态",
    "method": "GET",
    "path": "/scanner/status",
    "summary": "scannerStatus",
    "desc": "查 releaseId / container 是否就绪 / 当前 generation / 是否在轮询。",
    "examples": [
      {
        "label": "查询",
        "body": null
      }
    ]
  },
  {
    "id": "loyalty-start",
    "group": "ops",
    "step": 10,
    "title": "持久会话 · 新建",
    "method": "POST",
    "path": "/loyalty/start",
    "summary": "loyalty",
    "desc": "新建会话注入空 LoyaltyState，marshall 成 byte[] 落库；同 sessionId 跨请求跨重启累积积分。",
    "examples": [
      {
        "label": "起会话 alice",
        "body": {
          "sessionId": "alice"
        }
      }
    ]
  },
  {
    "id": "loyalty-purchase",
    "group": "ops",
    "step": 10,
    "title": "持久会话 · 购买累积",
    "method": "POST",
    "path": "/loyalty/{id}/purchase",
    "summary": "loyalty",
    "pathParams": [
      {
        "name": "id",
        "label": "会话 ID",
        "placeholder": "alice"
      }
    ],
    "desc": "恢复会话插入 PurchaseEvent，fire 触发积分+链式升级 (BRONZE→SILVER→GOLD)，再 marshall 落盘。",
    "examples": [
      {
        "label": "买 50（不够 BRONZE）",
        "pathParams": {
          "id": "alice"
        },
        "body": {
          "amount": 50
        }
      },
      {
        "label": "再买 60 → 解锁 BRONZE",
        "pathParams": {
          "id": "alice"
        },
        "body": {
          "amount": 60
        }
      },
      {
        "label": "大单 1000 → 链式到 GOLD",
        "pathParams": {
          "id": "alice"
        },
        "body": {
          "amount": 1000
        }
      }
    ]
  },
  {
    "id": "loyalty-get",
    "group": "ops",
    "step": 10,
    "title": "持久会话 · 只读 peek",
    "method": "GET",
    "path": "/loyalty/{id}",
    "summary": "loyalty",
    "pathParams": [
      {
        "name": "id",
        "label": "会话 ID",
        "placeholder": "alice"
      }
    ],
    "desc": "只读当前会话状态（不 fire、不写回）。未知 session 返回 404。",
    "examples": [
      {
        "label": "查 alice",
        "pathParams": {
          "id": "alice"
        },
        "body": null
      },
      {
        "label": "查未知 ghost → 404",
        "pathParams": {
          "id": "ghost"
        },
        "body": null
      }
    ]
  },
  {
    "id": "tms-compare",
    "group": "ops",
    "step": 12,
    "title": "TMS 真值维护对比",
    "method": "POST",
    "path": "/tms/compare",
    "summary": "tms",
    "desc": "同 Sensor 在 logical / regular 两 kbase 各跑两阶段 fire：logical 的衍生 Alert 随前提失配被自动 retract。",
    "examples": [
      {
        "label": "95 → 50（logical 撤销）",
        "body": {
          "sensorName": "boiler-a",
          "hotValue": 95,
          "coolValue": 50
        }
      },
      {
        "label": "95 → 80（只撤 CRITICAL）",
        "body": {
          "sensorName": "boiler-b",
          "hotValue": 95,
          "coolValue": 80
        }
      }
    ]
  },
  {
    "id": "guard-runaway",
    "group": "ops",
    "step": 14,
    "title": "护栏 · 硬上限熔断",
    "method": "POST",
    "path": "/guard/runaway",
    "summary": "guardRunaway",
    "desc": "失控自增规则被 fireAllRules(maxFires) 硬上限截断，请求线程不挂死。",
    "examples": [
      {
        "label": "截断在 50",
        "body": {
          "startValue": 0,
          "maxFires": 50
        }
      }
    ]
  },
  {
    "id": "guard-timeout",
    "group": "ops",
    "step": 14,
    "title": "护栏 · 超时打断",
    "method": "POST",
    "path": "/guard/timeout",
    "summary": "guardRunaway",
    "desc": "失控规则裸跑，watchdog 线程在 timeoutMillis 后 session.halt() 优雅打断（非 kill）。",
    "examples": [
      {
        "label": "200ms 后打断",
        "body": {
          "startValue": 0,
          "timeoutMillis": 200
        }
      }
    ]
  },
  {
    "id": "guard-canary",
    "group": "ops",
    "step": 14,
    "title": "护栏 · 灰度放行 (AgendaFilter)",
    "method": "POST",
    "path": "/guard/canary",
    "summary": "guardCanary",
    "desc": "带 @release 标记的规则按 allowedReleases 白名单放行；被拦的规则出现在 skipped，不重编译不重启。",
    "examples": [
      {
        "label": "只放 stable（canary 被拦）",
        "body": {
          "customer": {
            "name": "Tom",
            "age": 30,
            "vipLevel": 0,
            "yearsSinceRegistration": 1
          },
          "items": [
            {
              "name": "book",
              "quantity": 1,
              "unitPrice": 200,
              "category": "BOOKS"
            }
          ]
        }
      },
      {
        "label": "放量 stable+canary",
        "body": {
          "customer": {
            "name": "Tom",
            "age": 30,
            "vipLevel": 0,
            "yearsSinceRegistration": 1
          },
          "items": [
            {
              "name": "book",
              "quantity": 1,
              "unitPrice": 200,
              "category": "BOOKS"
            }
          ],
          "allowedReleases": [
            "stable",
            "canary"
          ]
        }
      }
    ]
  },
  {
    "id": "metrics-discount",
    "group": "ops",
    "step": 15,
    "title": "指标 · 打点折扣",
    "method": "POST",
    "path": "/metrics/discount",
    "summary": "metrics",
    "desc": "跟 Step 2 同折扣逻辑，但挂 MeteredRuleListener + Timer，把 fire/match/fact/耗时打进 Micrometer。",
    "examples": [
      {
        "label": "打一次点（可多打几次）",
        "body": {
          "customer": {
            "name": "Alice",
            "age": 35,
            "vipLevel": 2,
            "yearsSinceRegistration": 5
          },
          "items": [
            {
              "name": "Laptop",
              "quantity": 1,
              "unitPrice": 1000,
              "category": "X"
            }
          ]
        }
      }
    ]
  },
  {
    "id": "metrics-prometheus",
    "group": "ops",
    "step": 15,
    "title": "Prometheus 抓取端点",
    "method": "GET",
    "path": "/actuator/prometheus",
    "summary": "generic",
    "responseType": "text",
    "desc": "文本格式指标端点。先多打几次 /metrics/discount，再来这里看 drools_ 指标随调用累积。",
    "examples": [
      {
        "label": "抓取（文本）",
        "body": null
      }
    ]
  },
  {
    "id": "dmn-price",
    "group": "model",
    "step": 17,
    "title": "DMN 决策链 (FEEL)",
    "method": "POST",
    "path": "/dmn/price",
    "summary": "dmn",
    "desc": "非 DRL 引擎：DMN 模型按 DRG 拓扑求值 —— Discount Rate(原生决策表) → Final Price(FEEL) + Membership Tier。",
    "examples": [
      {
        "label": "VIP0 → 普通",
        "body": {
          "customer": {
            "name": "Tom",
            "age": 30,
            "vipLevel": 0,
            "yearsSinceRegistration": 1
          },
          "orderAmount": 1000
        }
      },
      {
        "label": "VIP2 → 会员 9 折",
        "body": {
          "customer": {
            "name": "Amy",
            "age": 40,
            "vipLevel": 2,
            "yearsSinceRegistration": 3
          },
          "orderAmount": 1000
        }
      },
      {
        "label": "VIP4 → 钻石 8 折",
        "body": {
          "customer": {
            "name": "Max",
            "age": 50,
            "vipLevel": 4,
            "yearsSinceRegistration": 8
          },
          "orderAmount": 1000
        }
      }
    ]
  },
  {
    "id": "campaign-create",
    "group": "model",
    "step": 18,
    "title": "营销活动 · 创建绑规则",
    "method": "POST",
    "path": "/campaign/create",
    "summary": "campaignCreate",
    "desc": "运营创建活动并绑定资格规则(DRL)，KieHelper 编译成功才落库；编译失败 400 + 行号。同 campaignId 覆盖。",
    "examples": [
      {
        "label": "新人专享活动",
        "body": {
          "campaignId": "newuser-2026",
          "name": "新人专享活动",
          "eligibilityDrl": "package campaign.newuser;\nimport com.lrj.drools.domain.UserProfile;\nimport com.lrj.drools.domain.Eligibility;\n\nrule \"新人专享: 注册<30天 且 未消费过\"\nwhen\n    UserProfile(registrationDays < 30, totalSpent == 0)\nthen\n    insert(new Eligibility(true, \"新用户且未消费过\"));\nend\n\nrule \"一线城市新人加码\"\nwhen\n    UserProfile(registrationDays < 30, city in (\"北京\",\"上海\",\"广州\",\"深圳\"))\nthen\n    insert(new Eligibility(true, \"一线城市新人\"));\nend"
        }
      }
    ]
  },
  {
    "id": "campaign-check",
    "group": "model",
    "step": 18,
    "title": "营销活动 · 资格判定",
    "method": "POST",
    "path": "/campaign/{id}/check",
    "summary": "campaignCheck",
    "pathParams": [
      {
        "name": "id",
        "label": "活动 ID",
        "placeholder": "newuser-2026"
      }
    ],
    "desc": "插 UserProfile 判定够不够格（白名单式：命中规则才 insert Eligibility）。活动已结束返回 409。",
    "examples": [
      {
        "label": "够格：注册10天+未消费+上海",
        "pathParams": {
          "id": "newuser-2026"
        },
        "body": {
          "userId": "u1",
          "age": 25,
          "vipLevel": 0,
          "registrationDays": 10,
          "totalSpent": 0,
          "city": "上海"
        }
      },
      {
        "label": "不够格：老用户+已消费+杭州",
        "pathParams": {
          "id": "newuser-2026"
        },
        "body": {
          "userId": "u2",
          "age": 40,
          "vipLevel": 2,
          "registrationDays": 400,
          "totalSpent": 5000,
          "city": "杭州"
        }
      }
    ]
  },
  {
    "id": "campaign-end",
    "group": "model",
    "step": 18,
    "title": "营销活动 · 结束",
    "method": "POST",
    "path": "/campaign/{id}/end",
    "summary": "generic",
    "pathParams": [
      {
        "name": "id",
        "label": "活动 ID",
        "placeholder": "newuser-2026"
      }
    ],
    "desc": "结束活动 (status→ENDED)，清掉内存 KieBase 缓存；之后 check 返回 409。",
    "examples": [
      {
        "label": "结束 newuser-2026",
        "pathParams": {
          "id": "newuser-2026"
        },
        "body": null
      }
    ]
  },
  {
    "id": "campaign-list",
    "group": "model",
    "step": 18,
    "title": "营销活动 · 列表",
    "method": "GET",
    "path": "/campaign/list",
    "summary": "campaignList",
    "desc": "列出所有活动（含 status + 是否已编译进内存缓存 cached）。重启后首次 check 前 cached=false。",
    "examples": [
      {
        "label": "查询",
        "body": null
      }
    ]
  }
];
