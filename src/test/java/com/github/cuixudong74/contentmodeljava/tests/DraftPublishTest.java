package com.github.cuixudong74.contentmodeljava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest;
import com.github.cuixudong74.contentmodeljava.dto.PublishRequest;
import com.github.cuixudong74.contentmodeljava.error.ContentModelException;
import com.github.cuixudong74.contentmodeljava.error.ErrorCode;
import com.github.cuixudong74.contentmodeljava.model.Entry;
import com.github.cuixudong74.contentmodeljava.model.EntryView;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.service.Repositories;
import com.github.cuixudong74.contentmodeljava.service.ValueProjector;
import com.github.cuixudong74.contentmodeljava.service.ValueValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Draft edits stay invisible to published readers; publication is pinned to a
 * definite draft version; a failed publication leaves the prior published
 * version untouched (no half-published state).
 */
class DraftPublishTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ContentTypeService types;
    private EntryService entries;

    @BeforeEach
    void setUp() {
        Repositories repositories = new Repositories();
        ValueValidator validator = new ValueValidator();
        types = new ContentTypeService(repositories, validator);
        entries = new EntryService(repositories, types, validator, new ValueProjector());

        types.create(mapper.convertValue(mapper.createObjectNode()
                        .put("id", "article")
                        .put("displayName", "Article")
                        .set("fields", mapper.createArrayNode()
                                .add(mapper.createObjectNode()
                                        .put("id", "title").put("name", "Title")
                                        .put("type", "STRING").put("required", true))),
                ContentTypeRequest.class));
    }

    private Entry createEntry(String title) {
        return entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "article")
                .set("data", mapper.createObjectNode().put("title", title)), EntryRequest.class));
    }

    private EntryRequest updateRequest(long version, String title) {
        return mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "article")
                .put("expectVersion", version)
                .set("data", mapper.createObjectNode().put("title", title)), EntryRequest.class);
    }

    private void publish(Entry e, long expectedDraftVersion) {
        entries.publish(e.id(), new PublishRequest(expectedDraftVersion));
    }

    @Test
    void unpublishedEntryHasNoPublishedView() {
        Entry e = createEntry("v1");
        assertEquals(0, e.publishedVersion());
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.readPublished(e.id(), null, null));
        assertEquals(ErrorCode.ENTRY_NOT_PUBLISHED, ex.code());
    }

    @Test
    void draftEditsAreInvisibleToPublishedReaders() {
        Entry e = createEntry("v1");
        publish(e, 1);

        entries.updateDraft(e.id(), updateRequest(1, "v2-draft"));

        EntryView published = entries.readPublished(e.id(), null, null);
        EntryView draft = entries.readDraft(e.id(), null, null);
        assertEquals("v1", published.fields().get("title").value());
        assertEquals(1, published.publishedVersion());
        assertEquals(2, published.draftVersion(), "published view still reports the newer draft version");
        assertEquals("v2-draft", draft.fields().get("title").value());
        assertEquals(1L, draft.baseDraftVersion());
    }

    @Test
    void publishWithoutPinnedVersionRejected() {
        Entry e = createEntry("v1");
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.publish(e.id(), new PublishRequest(null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.code());
    }

    @Test
    void publishPinnedToStaleDraftVersionRejectedAndDoesNotPublish() {
        Entry e = createEntry("v1");
        entries.updateDraft(e.id(), updateRequest(1, "v2"));
        // someone asked to publish draft v1, but the draft is already at v2
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.publish(e.id(), new PublishRequest(1L)));
        assertEquals(ErrorCode.STAGE_CONFLICT, ex.code());
        assertFalse(entries.getEntry(e.id()).isPublished());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("expectedDraftVersion=1")));
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("actualDraftVersion=2")));

        // retry with the current version succeeds
        assertDoesNotThrow(() -> publish(e, 2));
        assertEquals("v2", entries.readPublished(e.id(), null, null)
                .fields().get("title").value());
    }

    @Test
    void failedPublishLeavesPreviousPublishedVersionUntouched() {
        Entry referenced = createEntry("target");
        // add a REFERENCE field to the article type (safe: optional + default none is allowed)
        types.addField("article", mapper.convertValue(mapper.createObjectNode()
                .put("id", "related").put("name", "Related")
                .put("type", "REFERENCE").put("referencedTypeId", "article"),
                FieldDefinitionRequest.class));

        Entry e = entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "article")
                .set("data", mapper.createObjectNode().put("title", "first")), EntryRequest.class));
        publish(e, 1);
        long publishedVersionBefore = entries.getEntry(e.id()).publishedVersion();

        // draft v2 points at a missing entry -> publish must fail
        entries.updateDraft(e.id(), mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "article").put("expectVersion", 1L)
                .set("data", mapper.createObjectNode().put("title", "second").put("related", "ghost")),
                EntryRequest.class));
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> publish(e, 2));
        assertEquals(ErrorCode.REFERENCE_TARGET_NOT_FOUND, ex.code());

        Entry after = entries.getEntry(e.id());
        assertEquals(publishedVersionBefore, after.publishedVersion(),
                "failed publish must not advance the published version");
        assertEquals("first", entries.readPublished(e.id(), null, null)
                .fields().get("title").value());
        // draft remains intact for the editor to fix and retry
        assertEquals("second", entries.readDraft(e.id(), null, null)
                .fields().get("title").value());
    }

    @Test
    void validationFailureAlsoAbortsPublicationAtomically() {
        Entry e = createEntry("first-draft");
        publish(e, 1);

        // draft at v2 is valid when written (maxLength 20), then the range is
        // tightened to 3: the stored snapshot is now non-compliant and
        // publishing v2 must be rejected while v1 stays published.
        entries.updateDraft(e.id(), updateRequest(1, "way too long"));
        types.updateField("article", "title", mapper.convertValue(mapper.createObjectNode()
                .put("id", "title").put("name", "Title")
                .put("type", "STRING").put("required", true).put("maxLength", 3),
                FieldDefinitionRequest.class));
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> publish(e, 2));
        assertEquals(ErrorCode.FIELD_VALIDATION_FAILED, ex.code());
        assertEquals(1, entries.getEntry(e.id()).publishedVersion());
        assertEquals("first-draft", entries.readPublished(e.id(), null, null)
                .fields().get("title").value());
    }

    @Test
    void publishedSnapshotFrozenUntilNextSuccessfulPublish() {
        Entry e = createEntry("a");
        publish(e, 1);
        entries.updateDraft(e.id(), updateRequest(1, "b"));
        publish(e, 2);
        entries.updateDraft(e.id(), updateRequest(2, "c"));

        EntryView published = entries.readPublished(e.id(), null, null);
        assertEquals("b", published.fields().get("title").value());
        assertEquals(2L, published.baseDraftVersion());
        assertEquals(2, published.publishedVersion());
    }

    @Test
    void publishRejectsDraftReferencingUnpublishedEntry() {
        Entry target = createEntry("target");
        types.addField("article", mapper.convertValue(mapper.createObjectNode()
                .put("id", "related").put("name", "Related")
                .put("type", "REFERENCE").put("referencedTypeId", "article"),
                FieldDefinitionRequest.class));
        Entry holder = entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "article")
                .set("data", mapper.createObjectNode()
                        .put("title", "holder").put("related", target.id())), EntryRequest.class));

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> publish(holder, 1));
        assertEquals(ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED, ex.code());
        assertTrue(ex.details().get(0).contains(target.id()));
    }
}
