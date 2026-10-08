package com.github.cuixudong74.contentmodeljava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end HTTP semantics: status codes, stable error codes and envelope shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullDraftPublishReadFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/content-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"httpdoc","displayName":"Doc","fields":[
                                  {"key":"title","type":"STRING","required":false,"localized":false}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        String entryId = mockMvc.perform(post("/api/content-types/httpdoc/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":{"title":"hello"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values.title.state").value("PRESENT"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"(entry-\\d+)\".*", "$1");

        mockMvc.perform(post("/api/content-types/httpdoc/entries/" + entryId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("PUBLISHED"));

        mockMvc.perform(get("/api/content-types/httpdoc/entries/" + entryId)
                        .param("stage", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.title.value").value("hello"));
    }

    @Test
    void unknownTypeReturns404WithStableCode() throws Exception {
        mockMvc.perform(get("/api/content-types/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TYPE_NOT_FOUND"));
    }

    @Test
    void duplicateFieldReturns422WithStableCode() throws Exception {
        mockMvc.perform(post("/api/content-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"httpdup","fields":[
                                  {"key":"a","type":"STRING","required":false,"localized":false},
                                  {"key":"a","type":"STRING","required":false,"localized":false}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_DUPLICATE"));
    }

    @Test
    void optimisticConflictReturns409WithRetryableDetails() throws Exception {
        mockMvc.perform(post("/api/content-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"id":"httpconf","fields":[
                          {"key":"title","type":"STRING","required":false,"localized":false}
                        ]}
                        """)).andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/content-types/httpconf/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":{"title":"v0"}}
                                """))
                .andReturn().getResponse().getContentAsString();
        String entryId = body.replaceAll(".*\"id\":\"(entry-\\d+)\".*", "$1");

        mockMvc.perform(put("/api/content-types/httpconf/entries/" + entryId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,"values":{"title":"v1"}}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/content-types/httpconf/entries/" + entryId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,"values":{"title":"stale"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.currentVersion").value(2))
                .andExpect(jsonPath("$.details.retry").isString());
    }
}
