package com.relay.orchestrator.connection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
                .andExpect(content().string(containsString("https://unpkg.com/htmx.org@1.9.12")))
                .andExpect(content().string(containsString("data-busy-label=\"Indexing...\"")))
                .andExpect(content().string(containsString("resize-y")))
                .andExpect(content().string(containsString("Index Repository")));
    }

    @Test
    void buildEndpointRefreshesPageAfterIndexCompletes() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/repositories/build/backend"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Refresh", "true"))
                .andReturn()
                .getResponse();

        org.junit.jupiter.api.Assertions.assertTrue(response.getHeader("HX-Refresh") != null);
    }
}
