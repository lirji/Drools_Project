package com.lrj.drools.service;

import com.lrj.drools.domain.Applicant;
import com.lrj.drools.domain.TraitFinding;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 21: traits 运行入口。
 *
 * 插一批 Applicant，fireAllRules 后：高收入的被 `don` 上 PremiumApplicant（规则 1），
 * 再由规则 2 用 trait 类型匹配到并 insert TraitFinding。service 从 working memory 捞回 findings。
 */
@Service
public class TraitsService {

    private final KieContainer kieContainer;

    public TraitsService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public List<TraitFinding> evaluate(List<Applicant> applicants) {
        KieSession session = kieContainer.newKieSession("traitsSession");
        try {
            for (Applicant a : applicants) {
                session.insert(a);
            }
            int fired = session.fireAllRules();
            System.out.println("[TraitsService] 触发了 " + fired + " 条规则");

            List<TraitFinding> findings = new ArrayList<>();
            for (Object obj : session.getObjects()) {
                if (obj instanceof TraitFinding f) {
                    findings.add(f);
                }
            }
            return findings;
        } finally {
            session.dispose();
        }
    }
}
