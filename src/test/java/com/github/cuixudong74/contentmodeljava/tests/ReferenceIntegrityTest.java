package com.github.cuixudong74.contentmodeljava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.dto.DeleteEntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest;
import com.github.cuixudong74.contentmodeljava.dto.PublishRequest;
import com.github.cuixudong74.contentmodeljava.dto.UnpublishRequest;
import com.github.cuixudong74.contentmodeljava.error.ContentModelException;
import com.github.cuixudong74.contentmodeljava.error.ErrorCode;
import com.github.cuixudong74.contentmodeljava.model.Entry;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.service.Repositories;
import com.github.cuixudong74.contentmodeljava.service.ValueProjector;
import com.github.cuixudong74.contentmodeljava.service.ValueValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publication-time reference checks (missing vs unpublished targets, wrong
 * target type), and BLOCK vs CASCADE_UNPUBLISH behaviour on
 * unpublish/delete of referenced published entries.
 */
class ReferenceIntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ContentTypeService types;
    private EntryService entries;

    @BeforeEach
    void setUp() {
        Repositories repositories = new Repositories();
        ValueValidator validator = new ValueValidator();
        types = new ContentTypeService(repositories, validator);
        entries = new EntryService(repositories, types, validator, new ValueProjector());

        // type "page" with title and an optional list-like single REFERENCE to page
        types.create(mapper.convertValue(mapper.createObjectNode()
                .put("id", "page").put("displayName", "Page")
                .set("fields", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("id", "title").put("name", "Title")
                                .put("type", "STRING").put("required", true))),
                ContentTypeRequest.class));
        types.create(mapper.convertValue(mapper.createObjectNode()
                .put("id", "author").put("displayName", "Author")
                .set("fields", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("id", "name").put("name", "Name")
                                .put("type", "STRING").put("required", true))),
                ContentTypeRequest.class));

        types.addField("page", mapper.convertValue(mapper.createObjectNode()
                .put("id", "authorRef").put("name", "Author")
                .put("type", "REFERENCE").put("referencedTypeId", "author"),
                FieldDefinitionRequest.class));
        types.addField("page", mapper.convertValue(mapper.createObjectNode()
                .put("id", "relatedPages").put("name", "Related")
                .put("type", "REFERENCE").put("referencedTypeId", "page"),
                FieldDefinitionRequest.class));
    }

    private Entry newPage(String title) {
        ObjectNode data = mapper.createObjectNode().put("title", title);
        return entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "page").set("data", data), EntryRequest.class));
    }

    private Entry newAuthor(String name) {
        return entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "author")
                .set("data", mapper.createObjectNode().put("name", name)), EntryRequest.class));
    }

    private Entry pageWithRefs(String title, String authorId, String... relatedIds) {
        ObjectNode data = mapper.createObjectNode().put("title", title);
        if (authorId != null) data.put("authorRef", authorId);
        if (relatedIds.length > 0) data.set("relatedPages",
                mapper.createArrayNode().add(relatedIds[0]));
        return entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "page").set("data", data), EntryRequest.class));
    }

    private void publish(String id) {
        entries.publish(id, new PublishRequest(entries.getEntry(id).draftVersion()));
    }

    @Test
    void publishRejectsMissingTargetWithDistinctCode() {
        Entry page = pageWithRefs("p", null);
        // point draft at a ghost entry
        entries.updateDraft(page.id(), mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "page").put("expectVersion", 1L)
                .set("data", mapper.createObjectNode().put("title", "p").put("authorRef", "ghost-id")),
                EntryRequest.class));
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> publish(page.id()));
        assertEquals(ErrorCode.REFERENCE_TARGET_NOT_FOUND, ex.code());
        assertTrue(ex.details().get(0).contains("ghost-id"));
    }

    @Test
    void publishRejectsUnpublishedTargetWithDistinctCode() {
        Entry author = newAuthor("A");
        Entry page = pageWithRefs("p", author.id());

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> publish(page.id()));
        assertEquals(ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED, ex.code());
        assertTrue(ex.details().get(0).contains(author.id()));
        assertFalse(entries.getEntry(page.id()).isPublished());
    }

    @Test
    void publishSucceedsWhenAllTargetsPublished() {
        Entry author = newAuthor("A");
        publish(author.id());
        Entry page = pageWithRefs("p", author.id());
        assertDoesNotThrow(() -> publish(page.id()));
        assertTrue(entries.getEntry(page.id()).isPublished());
    }

    @Test
    void referenceToWrongTypeRejected() {
        // authorRef declared as -> author, but reference a page id
        Entry otherPage = newPage("other");
        publish(otherPage.id());
        Entry page = pageWithRefs("p", otherPage.id());
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> publish(page.id()));
        assertEquals(ErrorCode.FIELD_VALIDATION_FAILED, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("expected type 'author'")));
    }

    @Test
    void unpublishReferencedEntryBlockedByDefault() {
        Entry author = newAuthor("A");
        publish(author.id());
        Entry page = pageWithRefs("p", author.id());
        publish(page.id());

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.unpublish(author.id(), new UnpublishRequest(null)));
        assertEquals(ErrorCode.REFERENCE_INTEGRITY_VIOLATION, ex.code());
        assertTrue(entries.getEntry(author.id()).isPublished(), "author must stay published");
        assertTrue(entries.getEntry(page.id()).isPublished());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains(page.id())));
    }

    @Test
    void deleteReferencedEntryBlockedByDefault() {
        Entry author = newAuthor("A");
        publish(author.id());
        Entry page = pageWithRefs("p", author.id());
        publish(page.id());

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.delete(author.id(), new DeleteEntryRequest(null)));
        assertEquals(ErrorCode.REFERENCE_INTEGRITY_VIOLATION, ex.code());
        assertNotNull(entries.getEntry(author.id()));
    }

    @Test
    void cascadeUnpublishUnpublishesTransitiveReferrers() {
        // chain: a (published) <- b <- c, all published via page references
        Entry a = newPage("a");
        publish(a.id());
        Entry b = pageWithRefs("b", null, a.id());
        publish(b.id());
        Entry c = pageWithRefs("c", null, b.id());
        publish(c.id());

        entries.unpublish(a.id(), new UnpublishRequest("CASCADE_UNPUBLISH"));

        assertFalse(entries.getEntry(a.id()).isPublished());
        assertFalse(entries.getEntry(b.id()).isPublished());
        assertFalse(entries.getEntry(c.id()).isPublished());
        // drafts are retained
        assertEquals("a", entries.readDraft(a.id(), null, null).fields().get("title").value());
    }

    @Test
    void cascadeDeleteRemovesEntryAndUnpublishesReferrers() {
        Entry a = newPage("a");
        publish(a.id());
        Entry b = pageWithRefs("b", null, a.id());
        publish(b.id());

        entries.delete(a.id(), new DeleteEntryRequest("CASCADE_UNPUBLISH"));

        assertThrows(ContentModelException.class, () -> entries.getEntry(a.id()));
        assertFalse(entries.getEntry(b.id()).isPublished());
    }

    @Test
    void removingReferenceThenUnpublishingTargetIsAllowed() {
        Entry a = newPage("a");
        publish(a.id());
        Entry b = pageWithRefs("b", null, a.id());
        publish(b.id());

        // b publishes a new draft without the reference, then a can be unpublished
        entries.updateDraft(b.id(), mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "page").put("expectVersion", 1L)
                .set("data", mapper.createObjectNode().put("title", "b")), EntryRequest.class));
        publish(b.id());

        assertDoesNotThrow(() -> entries.unpublish(a.id(), new UnpublishRequest("BLOCK")));
        assertFalse(entries.getEntry(a.id()).isPublished());
    }

    @Test
    void unpublishedReferrerDoesNotBlockUnpublishOfTarget() {
        Entry a = newPage("a");
        publish(a.id());
        Entry b = pageWithRefs("b", null, a.id());
        publish(b.id());
        // unpublish b with cascade first (only b in closure), then a must be free
        entries.unpublish(b.id(), new UnpublishRequest("BLOCK"));
        assertDoesNotThrow(() -> entries.unpublish(a.id(), new UnpublishRequest(null)));
    }
}
