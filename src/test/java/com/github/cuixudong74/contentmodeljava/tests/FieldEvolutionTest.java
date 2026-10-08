package com.github.cuixudong74.contentmodeljava.tests;

import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeView;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field definition validation and safe schema evolution.
 */
@SpringBootTest
class FieldEvolutionTest {

    @Autowired
    private ContentTypeService contentTypeService;
    @Autowired
    private EntryService entryService;

    private FieldDefinition string(String key) {
        return FieldDefinition.of(key, FieldType.STRING);
    }

    @Test
    void createsContentTypeWithValidFieldsAndVersionOne() {
        ContentTypeView view = contentTypeService.create(new ContentTypeRequest(
                "article", "Article", List.of(string("title"))));
        assertEquals("article", view.id());
        assertEquals(1L, view.version());
        assertEquals(1, view.fields().size());
    }

    @Test
    void rejectsDuplicateFieldKeys() {
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> contentTypeService.create(new ContentTypeRequest(
                        "dup", "Dup", List.of(string("title"), string("title")))));
        assertEquals(ErrorCode.FIELD_DUPLICATE, ex.getCode());
    }

    @Test
    void rejectsInvalidFieldDefinitionWithExplainableErrors() {
        FieldDefinition bad = new FieldDefinition("age", FieldType.INTEGER, false, false,
                null, null, null, null, 10.0, 0.0, null, null);
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> contentTypeService.create(new ContentTypeRequest(
                        "invalid", "Invalid", List.of(bad))));
        assertEquals(ErrorCode.FIELD_INVALID_DEFINITION, ex.getCode());
        assertTrue(ex.getDetails() instanceof List<?> list && !list.isEmpty());
    }

    @Test
    void rejectsBadRegexPattern() {
        FieldDefinition bad = new FieldDefinition("code", FieldType.STRING, false, false,
                null, null, null, "[unterminated", null, null, null, null);
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> contentTypeService.create(new ContentTypeRequest(
                        "badregex", "BadRegex", List.of(bad))));
        assertEquals(ErrorCode.FIELD_INVALID_DEFINITION, ex.getCode());
    }

    @Test
    void addingOptionalFieldKeepsOldEntryReadableAndMarksUnset() {
        contentTypeService.create(new ContentTypeRequest(
                "blog", "Blog", List.of(string("title"))));
        EntryView created = entryService.create("blog",
                new EntryRequest(null, Map.of("title", "hello")));

        contentTypeService.evolve("blog", new ContentTypeRequest(
                null, null, List.of(string("subtitle"))));

        EntryView reread = entryService.get("blog", created.id(), "DRAFT", null, List.of());
        assertEquals("PRESENT", reread.values().get("title").state());
        assertEquals("UNSET", reread.values().get("subtitle").state());
        // The entry still remembers schema v1, while the type has evolved to v2.
        assertEquals(1L, reread.schemaVersion());
        assertEquals(2L, contentTypeService.get("blog").version());
    }

    @Test
    void addingFieldWithDefaultBackfillsOldEntries() {
        contentTypeService.create(new ContentTypeRequest(
                "shop", "Shop", List.of(string("name"))));
        EntryView created = entryService.create("shop",
                new EntryRequest(null, Map.of("name", "store")));

        FieldDefinition status = new FieldDefinition("status", FieldType.STRING, false, false,
                "draft", null, null, null, null, null, null, null);
        contentTypeService.evolve("shop", new ContentTypeRequest(
                null, null, List.of(status)));

        EntryView reread = entryService.get("shop", created.id(), "DRAFT", null, List.of());
        assertEquals("DEFAULTED", reread.values().get("status").state());
        assertEquals("draft", reread.values().get("status").value());
    }

    @Test
    void newRequiredFieldMustProvideDefaultValue() {
        contentTypeService.create(new ContentTypeRequest(
                "req", "Req", List.of(string("title"))));
        FieldDefinition mandatory = new FieldDefinition("slug", FieldType.STRING, true, false,
                null, null, null, null, null, null, null, null);
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> contentTypeService.evolve("req",
                        new ContentTypeRequest(null, null, List.of(mandatory))));
        assertEquals(ErrorCode.FIELD_EVOLUTION_UNSUPPORTED, ex.getCode());
    }

    @Test
    void changingFieldTypeIsRejected() {
        contentTypeService.create(new ContentTypeRequest(
                "chg", "Chg", List.of(string("v"))));
        FieldDefinition changed = FieldDefinition.of("v", FieldType.INTEGER);
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> contentTypeService.evolve("chg",
                        new ContentTypeRequest(null, null, List.of(changed))));
        assertEquals(ErrorCode.FIELD_EVOLUTION_UNSUPPORTED, ex.getCode());
    }

    @Test
    void tighteningRangeMarksNonConformingLegacyValueAsUnsetInsteadOfBreaking() {
        FieldDefinition loose = new FieldDefinition("count", FieldType.INTEGER, false, false,
                null, null, null, null, 0.0, 100.0, null, null);
        contentTypeService.create(new ContentTypeRequest(
                "tight", "Tight", List.of(loose)));
        EntryView created = entryService.create("tight",
                new EntryRequest(null, Map.of("count", 90)));

        FieldDefinition tight = new FieldDefinition("count", FieldType.INTEGER, false, false,
                null, null, null, null, 0.0, 50.0, null, null);
        contentTypeService.evolve("tight", new ContentTypeRequest(
                null, null, List.of(tight)));

        EntryView reread = entryService.get("tight", created.id(), "DRAFT", null, List.of());
        assertEquals("UNSET", reread.values().get("count").state());
    }
}
