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
 * End-to-end HTTP behaviour: happy paths plus the documented error codes and
 * HTTP status mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // create content type
        mockMvc.perform(post("/api/content-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"cat","displayName":"Category","fields":[
                                  {"id":"name","name":"Name","type":"STRING","required":true}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fields[0].id").value("name"));

        // duplicate field id rejected 422 with stable code
        mockMvc.perform(post("/api/content-types/cat/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"name","name":"Name2","type":"INTEGER"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_DUPLICATE_ID"));

        // invalid field definition
        mockMvc.perform(post("/api/content-types/cat/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"x","name":"X","type":"WEIRD"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_INVALID_DEFINITION"));

        // create entry
        String createResp = mockMvc.perform(post("/api/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeId":"cat","data":{"name":"books"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("draft"))
                .andExpect(jsonPath("$.draftVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String entryId = com.jayway.jsonpath.JsonPath.read(createResp, "$.id");

        // published read before publish -> 409
        mockMvc.perform(get("/api/entries/" + entryId + "/published"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_NOT_PUBLISHED"));

        // publish draft v1
        mockMvc.perform(post("/api/entries/" + entryId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectDraftVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.baseDraftVersion").value(1));

        // published read works; draft edit invisible
        mockMvc.perform(get("/api/entries/" + entryId + "/published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.name.value").value("books"))
                .andExpect(jsonPath("$.fields.name.state").value("PRESENT"));

        // optimistic update v1 -> v2 succeeds
        mockMvc.perform(put("/api/entries/" + entryId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectVersion\":1,\"data\":{\"name\":\"music\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftVersion").value(2));

        // stale update v1 -> 409 DRAFT_VERSION_CONFLICT with explainable details
        mockMvc.perform(put("/api/entries/" + entryId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectVersion\":1,\"data\":{\"name\":\"games\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("actualVersion=2")))
                );

        // stale publish (v1 while draft at v2) -> 409 STAGE_CONFLICT
        mockMvc.perform(post("/api/entries/" + entryId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectDraftVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAGE_CONFLICT"));

        // validation failure on publish: required field missing
        mockMvc.perform(put("/api/entries/" + entryId + "/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectVersion\":2,\"data\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_VALIDATION_FAILED"));

        // type not found / entry not found
        mockMvc.perform(get("/api/content-types/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TYPE_NOT_FOUND"));
        mockMvc.perform(get("/api/entries/nope/draft"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTRY_NOT_FOUND"));

        // blocked delete: published entry, no references -> allowed (204)
        mockMvc.perform(delete("/api/entries/" + entryId))
                .andExpect(status().isNoContent());
    }

    @Test
    void languageFallbackMetadataOverHttp() throws Exception {
        mockMvc.perform(post("/api/content-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"id":"loc","fields":[
                          {"id":"title","name":"Title","type":"LOCALIZED_STRING","required":true}
                        ]}
                        """)).andExpect(status().isCreated());

        String resp = mockMvc.perform(post("/api/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeId":"loc","data":{"title":{"en-US":"Hello","zh-CN":"你好"}}}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(resp, "$.id");

        mockMvc.perform(get("/api/entries/" + id + "/draft")
                        .param("lang", "fr-FR")
                        .param("fallback", "zh-CN", "en-US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.title.value").value("你好"))
                .andExpect(jsonPath("$.fields.title.state").value("FALLBACK"))
                .andExpect(jsonPath("$.fields.title.sourceLang").value("zh-CN"));
    }
}
