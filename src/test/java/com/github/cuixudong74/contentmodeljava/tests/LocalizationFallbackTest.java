package com.github.cuixudong74.contentmodeljava.tests;

import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryView;
import com.github.cuixudong74.contentmodeljava.web.dto.FieldValueView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Localized field resolution and explicit fallback ordering, including distinguishing an
 * original value from a served fallback.
 */
@SpringBootTest
class LocalizationFallbackTest {

    @Autowired
    private ContentTypeService contentTypeService;
    @Autowired
    private EntryService entryService;

    private FieldDefinition localized(String key) {
        return new FieldDefinition(key, FieldType.STRING, false, true,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void servesExactLocaleAndFlagsItAsOriginal() {
        contentTypeService.create(new ContentTypeRequest("i18n1", "i18n1",
                List.of(localized("title"))));
        EntryView e = entryService.create("i18n1", new EntryRequest(null,
                Map.of("title", Map.of("zh-CN", "你好", "en", "hello"))));

        EntryView view = entryService.get("i18n1", e.id(), "DRAFT", "zh-CN", List.of("en"));
        FieldValueView v = view.values().get("title");
        assertEquals("你好", v.value());
        assertEquals("zh-CN", v.locale());
        assertFalse(v.fallback());
        assertEquals("PRESENT", v.state());
    }

    @Test
    void fallsBackInGivenOrderAndMarksFallback() {
        contentTypeService.create(new ContentTypeRequest("i18n2", "i18n2",
                List.of(localized("title"))));
        EntryView e = entryService.create("i18n2", new EntryRequest(null,
                Map.of("title", Map.of("en", "hello", "fr", "bonjour"))));

        // No zh-CN translation; fallback order en first.
        EntryView view = entryService.get("i18n2", e.id(), "DRAFT", "zh-CN", List.of("en", "fr"));
        FieldValueView v = view.values().get("title");
        assertEquals("hello", v.value());
        assertEquals("en", v.locale());
        assertTrue(v.fallback());
    }

    @Test
    void fallbackOrderIsRespectedPositionally() {
        contentTypeService.create(new ContentTypeRequest("i18n3", "i18n3",
                List.of(localized("title"))));
        EntryView e = entryService.create("i18n3", new EntryRequest(null,
                Map.of("title", Map.of("en", "hello", "fr", "bonjour"))));

        EntryView view = entryService.get("i18n3", e.id(), "DRAFT", "de", List.of("fr", "en"));
        assertEquals("bonjour", view.values().get("title").value());
        assertEquals("fr", view.values().get("title").locale());
        assertTrue(view.values().get("title").fallback());
    }

    @Test
    void noTranslationAnywhereIsUnsetNotFallback() {
        contentTypeService.create(new ContentTypeRequest("i18n4", "i18n4",
                List.of(localized("title"))));
        EntryView e = entryService.create("i18n4", new EntryRequest(null,
                Map.of("title", Map.of("en", "hello"))));

        EntryView view = entryService.get("i18n4", e.id(), "DRAFT", "ja", List.of("ko"));
        FieldValueView v = view.values().get("title");
        assertEquals("UNSET", v.state());
        assertNull(v.value());
        assertFalse(v.fallback());
    }

    @Test
    void localizedDefaultIsUsedWhenNothingTranslated() {
        FieldDefinition withDefault = new FieldDefinition("title", FieldType.STRING, false, true,
                "untitled", null, null, null, null, null, null, null);
        contentTypeService.create(new ContentTypeRequest("i18n5", "i18n5",
                List.of(withDefault)));
        EntryView e = entryService.create("i18n5", new EntryRequest(null, Map.of()));

        EntryView view = entryService.get("i18n5", e.id(), "DRAFT", "en", List.of());
        FieldValueView v = view.values().get("title");
        assertEquals("DEFAULTED", v.state());
        assertEquals("untitled", v.value());
        assertFalse(v.fallback());
    }

    @Test
    void fallbackAppliesToPublishedReadsAsWell() {
        contentTypeService.create(new ContentTypeRequest("i18n6", "i18n6",
                List.of(localized("title"))));
        EntryView e = entryService.create("i18n6", new EntryRequest(null,
                Map.of("title", Map.of("en", "published-hello"))));
        entryService.publish("i18n6", e.id(), null);

        EntryView view = entryService.get("i18n6", e.id(), "PUBLISHED", "zh", List.of("en"));
        assertEquals("published-hello", view.values().get("title").value());
        assertTrue(view.values().get("title").fallback());
    }
}
