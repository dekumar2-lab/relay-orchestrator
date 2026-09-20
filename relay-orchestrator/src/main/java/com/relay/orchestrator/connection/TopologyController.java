package com.relay.orchestrator.connection;

import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.index.IndexRepository;
import com.relay.orchestrator.logging.TokenTrackerService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/topology")
public class TopologyController {

    private final IndexRepository indexRepository;
    private final AppConfigManager configManager;

    private final TokenTrackerService tokenTracker;

    public TopologyController(IndexRepository indexRepository, AppConfigManager configManager,
            TokenTrackerService tokenTracker) {
        this.indexRepository = indexRepository;
        this.configManager = configManager;
        this.tokenTracker = tokenTracker;
    }

    @GetMapping
    public String viewTopologyPage(Model model) {
        model.addAttribute("activeTab", "topology");
        model.addAttribute("viewContent", "topology");
        return "layout";
    }

    @GetMapping("/data")
    @ResponseBody
    public List<Map<String, Object>> getTreeStructureData() {
        // Pull direct structural relations directly from SQLite mappings without an
        // aggressive mapping layer
        return indexRepository.queryTopologyData();
    }

    @GetMapping("/class/{id}/methods")
    @ResponseBody
    public List<Map<String, Object>> getMethodsListForClassNode(@PathVariable long id) {
        return indexRepository.queryMethodsForClass(id);
    }

    @GetMapping("/token-tracker")
    public String viewTokenTrackerDashboard(Model model) {
        model.addAttribute("activeTab", "token-tracker");
        model.addAttribute("viewContent", "token-tracker");
        model.addAttribute("config", configManager);

        model.addAttribute("totalCost", tokenTracker.getTotalCost());
        model.addAttribute("totalTokens", tokenTracker.getTotalTokens());
        model.addAttribute("transactions", tokenTracker.getTransactions());

        return "layout";
    }

    @GetMapping("/token-tracker/table-fragment")
    public String viewTokenTrackerTableFragment(Model model) {
        model.addAttribute("transactions", tokenTracker.getTransactions());
        return "token-tracker :: ledger-rows";
    }

}
