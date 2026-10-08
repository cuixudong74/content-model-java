package com.github.cuixudong74.contentmodeljava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest;
import com.github.cuixudong74.contentmodeljava.model.Entry;
import com.github.cuixudong74.contentmodeljava.model.EntryView;
import com.github.cuixudong74.contentmodeljava.model.ResolvedValue;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.service.Repositories;
import com.github.cuixudong74.contentmodeljava.service.ValueProjector;
import com.github.cuixudong74.contentmodeljava.service.ValueValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOCALIZED_STRING resolution: exact language first, then the declared
 * fallback chain in order; fallback values are marked FALLBACK with the actual
 * source language so they can be distinguished from original values.
 */
class LanguageFallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ContentTypeService types;
    private EntryService entries;
    private String entryId;

    @BeforeEach
    void setUp() {
        Repositories repositories = new Repositories();
        ValueValidator validator = new ValueValidator();
        types = new ContentTypeService(repositories, validator);
        entries = new EntryService(repositories, types, validator, new ValueProjector());

        types.create(mapper.convertValue(mapper.createObjectNode()
                .put("id", "i18n").put("displayName", "I18n")
                .set("fields", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("id", "title").put("name", "Title")
                                .put("type", "LOCALIZED_STRING").put("required", true))
                        .add(mapper.createObjectNode()
                                .put("id", "greeting").put("name", "Greeting")
                                .put("type", "LOCALIZED_STRING"))),
                ContentTypeRequest.class));

        com.fasterxml.jackson.databind.node.ObjectNode title = mapper.createObjectNode();
        title.put("zh-CN", "你好");
        title.put("en-US", "Hello");
        com.fasterxml.jackson.databind.node.ObjectNode greeting = mapper.createObjectNode();
        greeting.put("en-US", "Hi");
        com.fasterxml.jackson.databind.node.ObjectNode data = mapper.createObjectNode();
        data.set("title", title);
        data.set("greeting", greeting);
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("typeId", "i18n");
        body.set("data", data);
        Entry e = entries.create(mapper.convertValue(body, EntryRequest.class));
        entryId = e.id();
    }

    @Test
    void exactLanguageIsPresent() {
        EntryView view = entries.readDraft(entryId, "zh-CN", List.of("en-US"));
        ResolvedValue title = view.fields().get("title");
        assertEquals("你好", title.value());
        assertEquals(ResolvedValue.ValueState.PRESENT, title.state());
        assertEquals("zh-CN", title.sourceLang());
        assertEquals("zh-CN", view.requestedLanguage());
    }

    @Test
    void missingLanguageUsesFallbackChainAndIsMarkedFallback() {
        EntryView view = entries.readDraft(entryId, "fr-FR", List.of("zh-CN", "en-US"));
        ResolvedValue title = view.fields().get("title");
        assertEquals("你好", title.value());
        assertEquals(ResolvedValue.ValueState.FALLBACK, title.state());
        assertEquals("zh-CN", title.sourceLang(), "first available language in chain wins");
        assertEquals(List.of("zh-CN", "en-US"), view.fallbackLanguages());
    }

    @Test
    void fallbackChainOrderRespected() {
        // zh-CN absent? it is present for title; use greeting which only has en-US
        EntryView view = entries.readDraft(entryId, "fr-FR", List.of("zh-CN", "en-US"));
        ResolvedValue greeting = view.fields().get("greeting");
        assertEquals("Hi", greeting.value());
        assertEquals(ResolvedValue.ValueState.FALLBACK, greeting.state());
        assertEquals("en-US", greeting.sourceLang());
    }

    @Test
    void noTranslationAnywhereFallsBackToDeterministicAvailableLanguage() {
        EntryView view = entries.readDraft(entryId, "fr-FR", List.of("de-DE"));
        ResolvedValue title = view.fields().get("title");
        assertEquals(ResolvedValue.ValueState.FALLBACK, title.state());
        assertEquals(List.of("en-US", "zh-CN").contains(title.sourceLang()), true,
                "some available language is returned, marked as fallback");
    }

    @Test
    void localizedFieldWithNoValueAtAllIsUnset() {
        Entry empty = entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "i18n")
                .set("data", mapper.createObjectNode()
                        .set("title", mapper.createObjectNode()
                                .put("en-US", "Hello"))), EntryRequest.class));
        // greeting entirely absent + optional + no default
        ResolvedValue greeting = entries.readDraft(empty.id(), "en-US", List.of()).fields()
                .get("greeting");
        assertEquals(ResolvedValue.ValueState.UNSET, greeting.state());
        assertNull(greeting.value());
    }

    @Test
    void defaultLocalizedFieldIsMarkedDefaultWithSourceLanguage() {
        types.addField("i18n", mapper.convertValue(mapper.createObjectNode()
                .put("id", "badge").put("name", "Badge")
                .put("type", "LOCALIZED_STRING")
                .set("defaultValue", mapper.createObjectNode().put("en-US", "NEW")),
                FieldDefinitionRequest.class));
        EntryView view = entries.readDraft(entryId, "zh-CN", List.of("en-US"));
        ResolvedValue badge = view.fields().get("badge");
        assertEquals(ResolvedValue.ValueState.DEFAULT, badge.state());
        assertEquals("NEW", badge.value());
        assertEquals("en-US", badge.sourceLang());
    }

    @Test
    void readWithoutLanguageReturnsRawLocalizedMapAsPresent() {
        EntryView view = entries.readDraft(entryId, null, null);
        Object title = view.fields().get("title").value();
        assertTrue(title instanceof java.util.Map<?, ?> map && map.containsKey("zh-CN"));
        assertEquals(ResolvedValue.ValueState.PRESENT, view.fields().get("title").state());
    }

    @Test
    void fallbackAppliesToPublishedReadsToo() {
        entries.publish(entryId,
                new com.github.cuixudong74.contentmodeljava.dto.PublishRequest(1L));
        EntryView view = entries.readPublished(entryId, "fr-FR", List.of("en-US"));
        ResolvedValue title = view.fields().get("title");
        assertEquals("Hello", title.value());
        assertEquals(ResolvedValue.ValueState.FALLBACK, title.state());
        assertEquals("en-US", title.sourceLang());
    }
}
