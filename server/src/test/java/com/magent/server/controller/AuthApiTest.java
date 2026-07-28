package com.magent.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private String login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = om.readTree(r.getResponse().getContentAsString());
        return node.path("data").path("token").asText();
    }

    @Test
    void adminCanLoginAndGetToken() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = om.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("role").asText()).isEqualTo("admin");
    }

    @Test
    void wrongPasswordGets401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithoutLoginGets401() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanCreateUserButMemberCannot() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(post("/api/users").header("satoken", adminToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"pwd123\",\"role\":\"member\"}"))
                .andExpect(status().isOk());

        String bobToken = login("bob", "pwd123");
        mvc.perform(post("/api/users").header("satoken", bobToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"eve\",\"password\":\"pwd123\",\"role\":\"member\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userListNeverExposesPassword() throws Exception {
        String adminToken = login("admin", "admin123");
        MvcResult r = mvc.perform(get("/api/users").header("satoken", adminToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("password");
    }
}
