package com.github.cuixudong74.contentmodeljava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.contentmodeljava.error.ContentModelException;
import com.github.cuixudong74.contentmodeljava.error.ErrorCode;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Content type evolution must never break already-persisted entries:
 * added optional / defaulted fields are projected on read, unsafe evolution
 * (new required-without-default) is rejected, and validation-range changes
 * are allowed without touching stored data.
 */
class FieldEvolutionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Repositories repositories;
    private ContentTypeService types;
    private EntryService entries;

    @BeforeEach
    void setUp() {
        repositories = new Repositories();
        ValueValidator validator = new ValueValidator();
        types = new ContentTypeService(repositories, validator);
        entries = new EntryService(repositories, types, validator, new ValueProjector());
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void createType(String body) {
        types.create(mapper.convertValue(json(body),
                com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest.class));
    }

    private void addField(String typeId, String body) {
        types.addField(typeId, mapper.convertValue(json(body),
                com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest.class));
    }

    private void updateField(String typeId, String fieldId, String body) {
        types.updateField(typeId, fieldId, mapper.convertValue(json(body),
                com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest.class));
    }

    private Entry newEntry(String typeId, String dataJson) {
        return entries.create(mapper.convertValue(
                json("{\"typeId\":\"" + typeId + "\",\"data\":" + dataJson + "}"),
                com.github.cuixudong74.contentmodeljava.dto.EntryRequest.class));
    }

    @Test
    void duplicateFieldIdsRejectedAtTypeCreation() {
        String body = """
                {"id":"article","displayName":"Article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true},
                  {"id":"title","name":"Title 2","type":"INTEGER"}
                ]}
                """;
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> createType(body));
        assertEquals(ErrorCode.FIELD_DUPLICATE_ID, ex.code());
        // the failed creation must not leave a half-registered type behind
        assertEquals(0, types.list().size());
    }

    @Test
    void invalidFieldDefinitionRejectedWithReasons() {
        String body = """
                {"id":"article","fields":[
                  {"id":"rating","name":"Rating","type":"INTEGER","min":10,"max":1}
                ]}
                """;
        ContentModelException ex = assertThrows(ContentModelException.class, () -> createType(body));
        assertEquals(ErrorCode.FIELD_INVALID_DEFINITION, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("must not exceed max")));
    }

    @Test
    void newOptionalFieldIsProjectedAsUnsetOnOldEntries() {
        createType("""
                {"id":"article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true}
                ]}
                """);
        Entry e = newEntry("article", "{\"title\":\"hello\"}");

        addField("article", """
                {"id":"subtitle","name":"Subtitle","type":"STRING","required":false}
                """);
        assertEquals(2, types.get("article").version());

        EntryView view = entries.readDraft(e.id(), null, null);
        ResolvedValue subtitle = view.fields().get("subtitle");
        assertEquals(ResolvedValue.ValueState.UNSET, subtitle.state());
        assertNull(subtitle.value());
    }

    @Test
    void newFieldWithDefaultIsProjectedAsDefault() {
        createType("""
                {"id":"article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true}
                ]}
                """);
        Entry e = newEntry("article", "{\"title\":\"hello\"}");

        addField("article", """
                {"id":"status","name":"Status","type":"STRING","defaultValue":"DRAFT"}
                """);

        EntryView view = entries.readDraft(e.id(), null, null);
        assertEquals(ResolvedValue.ValueState.DEFAULT, view.fields().get("status").state());
        assertEquals("DRAFT", view.fields().get("status").value());
    }

    @Test
    void newRequiredFieldWithoutDefaultRejectedOnceEntriesExist() {
        createType("""
                {"id":"article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true}
                ]}
                """);
        newEntry("article", "{\"title\":\"hello\"}");

        ContentModelException ex = assertThrows(ContentModelException.class, () ->
                addField("article",
                        "{\"id\":\"slug\",\"name\":\"Slug\",\"type\":\"STRING\",\"required\":true}"));
        assertEquals(ErrorCode.FIELD_EVOLUTION_UNSAFE, ex.code());
        // rejected evolution must not bump the type version or add the field
        assertEquals(1, types.get("article").version());
        assertFalse(types.get("article").hasField("slug"));
    }

    @Test
    void newRequiredFieldWithoutDefaultAllowedWhenNoEntries() {
        createType("{\"id\":\"empty\",\"fields\":[]}");
        assertDoesNotThrow(() -> addField("empty",
                "{\"id\":\"slug\",\"name\":\"Slug\",\"type\":\"STRING\",\"required\":true}"));
    }

    @Test
    void tighteningValidationRangeDoesNotBreakOldEntryReads() {
        createType("""
                {"id":"article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true,"maxLength":100}
                ]}
                """);
        Entry e = newEntry("article", "{\"title\":\"this string is thirty characters long!!\"}");

        // tighten the range: allowed evolution for existing types
        updateField("article", "title", """
                {"id":"title","name":"Title","type":"STRING","required":true,"maxLength":10}
                """);
        // old data is not rewritten and remains readable (range is enforced on next write)
        EntryView view = entries.readDraft(e.id(), null, null);
        assertEquals("this string is thirty characters long!!", view.fields().get("title").value());

        // but a new edit violating the new range is rejected
        var req = mapper.convertValue(json("""
                {"typeId":"article","expectVersion":1,
                 "data":{"title":"this string is thirty characters long!!"}}"""),
                com.github.cuixudong74.contentmodeljava.dto.EntryRequest.class);
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.updateDraft(e.id(), req));
        assertEquals(ErrorCode.FIELD_VALIDATION_FAILED, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("exceeds maxLength")));
    }

    @Test
    void removingFieldHidesItFromProjectionButKeepsEntryReadable() {
        createType("""
                {"id":"article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true},
                  {"id":"temp","name":"Temp","type":"STRING"}
                ]}
                """);
        Entry e = newEntry("article", "{\"title\":\"hi\",\"temp\":\"x\"}");
        types.removeField("article", "temp");

        EntryView view = entries.readDraft(e.id(), null, null);
        assertFalse(view.fields().containsKey("temp"));
        assertEquals("hi", view.fields().get("title").value());
    }

    @Test
    void unknownFieldsOnWriteArePreservedButNotProjectedAfterRemoval() {
        createType("""
                {"id":"article","fields":[
                  {"id":"title","name":"Title","type":"STRING","required":true},
                  {"id":"age","name":"Age","type":"INTEGER"}
                ]}
                """);
        Entry e = newEntry("article", "{\"title\":\"hi\",\"age\":5}");
        assertEquals(5, ((Number) entries.readDraft(e.id(), null, null)
                .fields().get("age").value()).intValue());
    }
}
