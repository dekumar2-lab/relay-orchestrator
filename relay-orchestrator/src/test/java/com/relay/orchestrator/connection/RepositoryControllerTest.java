package com.relay.orchestrator.connection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RepositoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void repositoryPageLoadsWithHtmxForIndexActions() throws Exception {
        mockMvc.perform(get("/repositories"))
                .andExpect(status().isOk())
                // Log panel + JS-driven resize handle (replaces the old resize-y utility)
                .andExpect(content().string(containsString("id=\"log-panel\"")))
                .andExpect(content().string(containsString("id=\"log-resize-handle\"")))
                .andExpect(content().string(containsString("cursor-ns-resize")))
                // HTMX wiring for indexing endpoints
                .andExpect(content().string(containsString("hx-post=\"/repositories/build-all\"")))
                .andExpect(content().string(containsString("hx-post=\"/repositories/build/backend\"")))
                .andExpect(content().string(containsString("hx-swap=\"none\"")));
    }
}