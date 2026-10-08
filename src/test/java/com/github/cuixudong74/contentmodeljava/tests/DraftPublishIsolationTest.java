package com.github.cuixudong74.contentmodeljava.tests;

import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryView;
import com.github.cuixudong74.contentmodeljava.web.dto.PublishRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Draft vs published isolation, fixed-version publish and no half-published state on failure.
 */
@SpringBootTest
class DraftPublishIsolationTest {

    @Autowired
    private ContentTypeService contentTypeService;
    @Autowired
    private EntryService entryService;

    private void type(String id, FieldDefinition... fields) {
        contentTypeService.create(new ContentTypeRequest(id, id, List.of(fields)));
    }

    @Test
    void draftChangesAreInvisibleToPublishedReaders() {
        type("page", FieldDefinition.of("title", FieldType.STRING));
        EntryView e = entryService.create("page",
                new EntryRequest(null, Map.of("title", "v1")));
        entryService.publish("page", e.id(), new PublishRequest(1L));

        // Edit the draft; published readers must still see v1.
        entryService.updateDraft("page", e.id(),
                new EntryRequest(1L, Map.of("title", "v2")));

        EntryView published = entryService.get("page", e.id(), "PUBLISHED", null, List.of());
        assertEquals("v1", published.values().get("title").value());
        assertEquals(1L, published.version());

        EntryView draft = entryService.get("page", e.id(), "DRAFT", null, List.of());
        assertEquals("v2", draft.values().get("title").value());
        assertEquals(2L, draft.version());
    }

    @Test
    void readingPublishedBeforeFirstPublishIsAnExplainableError() {
        type("fresh", FieldDefinition.of("title", FieldType.STRING));
        EntryView e = entryService.create("fresh",
                new EntryRequest(null, Map.of("title", "x")));
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.get("fresh", e.id(), "PUBLISHED", null, List.of()));
        assertEquals(ErrorCode.ENTRY_NOT_PUBLISHED, ex.getCode());
    }

    @Test
    void publishMustTargetTheCurrentDraftVersion() {
        type("fixed", FieldDefinition.of("title", FieldType.STRING));
        EntryView e = entryService.create("fixed",
                new EntryRequest(null, Map.of("title", "a")));
        entryService.updateDraft("fixed", e.id(),
                new EntryRequest(1L, Map.of("title", "b")));

        // Attempt to publish stale draft version 1 while the current draft is 2.
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.publish("fixed", e.id(), new PublishRequest(1L)));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getCode());
        // No publish happened.
        assertFalse(entryService.get("fixed", e.id(), "DRAFT", null, List.of())
                .id().isEmpty());
        assertThrows(ContentModelException.class,
                () -> entryService.get("fixed", e.id(), "PUBLISHED", null, List.of()));
    }

    @Test
    void failedPublishKeepsPreviousPublishedVersionIntact() {
        FieldDefinition required = new FieldDefinition("title", FieldType.STRING, true, false,
                null, null, null, null, null, null, null, null);
        type("atomic", required);
        EntryView e = entryService.create("atomic",
                new EntryRequest(null, Map.of("title", "first")));
        entryService.publish("atomic", e.id(), null);

        // Replace draft with an invalid state and attempt to publish: it must fail and the old
        // published snapshot must remain readable.
        entryService.updateDraft("atomic", e.id(),
                new EntryRequest(1L, Map.of()));
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.publish("atomic", e.id(), new PublishRequest(2L)));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getCode());

        EntryView published = entryService.get("atomic", e.id(), "PUBLISHED", null, List.of());
        assertEquals("first", published.values().get("title").value());
        assertEquals(1L, published.version());
    }

    @Test
    void unpublishOnlyRemovesThePublishedSnapshot() {
        type("lifecycle", FieldDefinition.of("title", FieldType.STRING));
        EntryView e = entryService.create("lifecycle",
                new EntryRequest(null, Map.of("title", "keep")));
        entryService.publish("lifecycle", e.id(), null);
        entryService.unpublish("lifecycle", e.id());

        assertThrows(ContentModelException.class,
                () -> entryService.get("lifecycle", e.id(), "PUBLISHED", null, List.of()));
        EntryView draft = entryService.get("lifecycle", e.id(), "DRAFT", null, List.of());
        assertEquals("keep", draft.values().get("title").value());
    }

    @Test
    void listPublishedStageHidesUnpublishedEntries() {
        type("listing", FieldDefinition.of("title", FieldType.STRING));
        EntryView published = entryService.create("listing",
                new EntryRequest(null, Map.of("title", "out")));
        entryService.create("listing", new EntryRequest(null, Map.of("title", "hidden")));
        entryService.publish("listing", published.id(), null);

        List<EntryView> publishedList = entryService.list("listing", "PUBLISHED", null, List.of());
        assertEquals(1, publishedList.size());
        assertEquals(published.id(), publishedList.get(0).id());
        assertEquals(2, entryService.list("listing", "DRAFT", null, List.of()).size());
    }
}
