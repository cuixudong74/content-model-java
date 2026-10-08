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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimistic concurrency: the second committer based on a stale version is rejected with
 * explainable, retryable conflict details.
 */
@SpringBootTest
class ConcurrencyConflictTest {

    @Autowired
    private ContentTypeService contentTypeService;
    @Autowired
    private EntryService entryService;

    @Test
    void staleUpdateIsRejectedWithCurrentVersionAndRetryHint() {
        contentTypeService.create(new ContentTypeRequest("doc", "Doc",
                List.of(FieldDefinition.of("title", FieldType.STRING))));
        EntryView e = entryService.create("doc",
                new EntryRequest(null, Map.of("title", "base")));

        // Both editors read version 1; the first commits successfully.
        entryService.updateDraft("doc", e.id(),
                new EntryRequest(1L, Map.of("title", "editor-a")));

        // The second commits against the same stale version 1.
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.updateDraft("doc", e.id(),
                        new EntryRequest(1L, Map.of("title", "editor-b"))));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("2"));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) ex.getDetails();
        assertEquals(2L, details.get("currentVersion"));
        assertEquals(1L, details.get("suppliedVersion"));
        assertNotNull(details.get("retry"));

        // Retrying with the fresh version succeeds.
        EntryView retried = entryService.updateDraft("doc", e.id(),
                new EntryRequest(2L, Map.of("title", "editor-b-retry")));
        assertEquals(3L, retried.version());
        assertEquals("editor-b-retry", retried.values().get("title").value());
    }

    @Test
    void concurrentEditorsDoNotOverwriteEachOther() throws Exception {
        contentTypeService.create(new ContentTypeRequest("race", "Race",
                List.of(FieldDefinition.of("v", FieldType.STRING))));
        EntryView e = entryService.create("race",
                new EntryRequest(null, Map.of("v", "0")));

        int editors = 12;
        ExecutorService pool = Executors.newFixedThreadPool(editors);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (int i = 0; i < editors; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // Every editor races on the same base version 1.
                    entryService.updateDraft("race", e.id(),
                            new EntryRequest(1L, Map.of("v", Thread.currentThread().getName())));
                    successes.incrementAndGet();
                } catch (ContentModelException ex) {
                    if (ex.getCode() == ErrorCode.VERSION_CONFLICT) {
                        conflicts.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertEquals(editors - 1, conflicts.get());
        assertEquals(2L,
                entryService.get("race", e.id(), "DRAFT", null, List.of()).version());
    }

    @Test
    void updateWithoutExpectedVersionIsRejected() {
        contentTypeService.create(new ContentTypeRequest("noversion", "NoVersion",
                List.of(FieldDefinition.of("title", FieldType.STRING))));
        EntryView e = entryService.create("noversion",
                new EntryRequest(null, Map.of("title", "x")));
        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.updateDraft("noversion", e.id(),
                        new EntryRequest(null, Map.of("title", "y"))));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getCode());
    }
}
