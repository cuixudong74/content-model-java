package com.github.cuixudong74.contentmodeljava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryRequest;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two editors that both read draft version N and submit updates: one wins,
 * the other must receive DRAFT_VERSION_CONFLICT with explainable details and
 * be able to retry after re-reading.
 */
class ConcurrencyConflictTest {

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
                .put("id", "doc").put("displayName", "Doc")
                .set("fields", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("id", "title").put("name", "Title")
                                .put("type", "STRING").put("required", true))),
                ContentTypeRequest.class));
    }

    private EntryRequest updateRequest(long version, String title) {
        return mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "doc").put("expectVersion", version)
                .set("data", mapper.createObjectNode().put("title", title)), EntryRequest.class);
    }

    private Entry create(String title) {
        return entries.create(mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "doc")
                .set("data", mapper.createObjectNode().put("title", title)), EntryRequest.class));
    }

    @Test
    void secondCommitterDetectedAndRejectedSequentially() {
        Entry e = create("v0");

        // editor A commits based on v1
        entries.updateDraft(e.id(), updateRequest(1, "A"));

        // editor B still holds v1 -> conflict
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.updateDraft(e.id(), updateRequest(1, "B")));
        assertEquals(ErrorCode.DRAFT_VERSION_CONFLICT, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("expectedVersion=1")));
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("actualVersion=2")));
        assertTrue(ex.details().stream().anyMatch(d -> d.toLowerCase().contains("re-read")));

        // A's change survived; B's overwrite did not
        assertEquals("A", entries.readDraft(e.id(), null, null).fields().get("title").value());
        assertEquals(2, entries.getEntry(e.id()).draftVersion());

        // B retries after re-reading v2 and now succeeds
        entries.updateDraft(e.id(), updateRequest(2, "B-merged"));
        EntryView view = entries.readDraft(e.id(), null, null);
        assertEquals("B-merged", view.fields().get("title").value());
        assertEquals(3, view.draftVersion());
    }

    @Test
    void concurrentUpdatesExactlyOneWins() throws InterruptedException {
        Entry e = create("v0");
        int editors = 16;
        ExecutorService pool = Executors.newFixedThreadPool(editors);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger wins = new AtomicInteger();

        for (int i = 0; i < editors; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // every editor optimistically updates from the same base version 1,
                    // retrying after conflict by re-reading the current version
                    long seen = 1;
                    while (true) {
                        try {
                            entries.updateDraft(e.id(), updateRequest(seen, "editor-" + idx));
                            wins.incrementAndGet();
                            return;
                        } catch (ContentModelException ex) {
                            if (ex.code() == ErrorCode.DRAFT_VERSION_CONFLICT) {
                                conflicts.incrementAndGet();
                                seen = entries.getEntry(e.id()).draftVersion();
                            } else {
                                throw ex;
                            }
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        // initial version 1 + one increment per editor -> no lost updates
        assertEquals(editors, wins.get());
        assertTrue(conflicts.get() > 0, "contention must produce at least one conflict");
        assertEquals(1L + editors, entries.getEntry(e.id()).draftVersion());
    }

    @Test
    void updateWithoutExpectVersionRejected() {
        Entry e = create("v0");
        EntryRequest req = mapper.convertValue(mapper.createObjectNode()
                .put("typeId", "doc")
                .set("data", mapper.createObjectNode().put("title", "x")), EntryRequest.class);
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entries.updateDraft(e.id(), req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.code());
    }

    @Test
    void draftConflictDoesNotAffectPublishedVersion() {
        Entry e = create("v0");
        entries.publish(e.id(),
                new com.github.cuixudong74.contentmodeljava.dto.PublishRequest(1L));
        entries.updateDraft(e.id(), updateRequest(1, "A"));
        assertThrows(ContentModelException.class,
                () -> entries.updateDraft(e.id(), updateRequest(1, "B")));
        // published snapshot and version are untouched by the rejected draft write
        assertEquals("v0", entries.readPublished(e.id(), null, null)
                .fields().get("title").value());
        assertEquals(1, entries.getEntry(e.id()).publishedVersion());
    }
}
