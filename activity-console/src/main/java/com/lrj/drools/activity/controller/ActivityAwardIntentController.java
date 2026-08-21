package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.service.ActivityAwardIntentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/activity-awards/v1")
public class ActivityAwardIntentController {
    private final ActivityAwardIntentService intents;
    public ActivityAwardIntentController(ActivityAwardIntentService intents) { this.intents = intents; }

    /** Internal post-decision trigger; its decisionFacts must come from the same server-side decision transaction. */
    @PostMapping("/intents")
    public ActivityAwardIntentService.AssembleResult create(
            @RequestBody ActivityAwardIntentService.AssembleCommand command) {
        return intents.assemble(command);
    }
}
