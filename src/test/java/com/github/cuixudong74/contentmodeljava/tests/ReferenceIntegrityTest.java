package com.github.cuixudong74.contentmodeljava.tests;

import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.domain.ReferencePolicy;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference validation at publish time and RESTRICT/CASCADE policies on unpublish/delete.
 */
@SpringBootTest
class ReferenceIntegrityTest {

    @Autowired
    private ContentTypeService contentTypeService;
    @Autowired
    private EntryService entryService;

    private void type(String id, FieldDefinition... fields) {
        contentTypeService.create(new ContentTypeRequest(id, id, List.of(fields)));
    }

    private FieldDefinition ref(String key, String refType, ReferencePolicy policy) {
        return new FieldDefinition(key, FieldType.REFERENCE, false, false,
                null, null, null, null, null, null, refType, policy);
    }

    @Test
    void publishRejectsReferenceToMissingEntryWithDistinctCode() {
        type("author", FieldDefinition.of("name", FieldType.STRING));
        type("post1", ref("author", "author", ReferencePolicy.RESTRICT));
        EntryView post = entryService.create("post1",
                new EntryRequest(null, Map.of("author", "entry-999")));

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.publish("post1", post.id(), null));
        assertEquals(ErrorCode.REFERENCE_TARGET_NOT_FOUND, ex.getCode());
        assertNotNull(ex.getDetails());
    }

    @Test
    void publishRejectsReferenceToUnpublishedEntryWithDistinctCode() {
        type("cat", FieldDefinition.of("name", FieldType.STRING));
        type("article1", ref("category", "cat", ReferencePolicy.RESTRICT));
        EntryView cat = entryService.create("cat",
                new EntryRequest(null, Map.of("name", "news")));
        EntryView article = entryService.create("article1",
                new EntryRequest(null, Map.of("category", cat.id())));

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.publish("article1", article.id(), null));
        assertEquals(ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED, ex.getCode());
    }

    @Test
    void publishSucceedsWhenTargetIsPublished() {
        type("tag", FieldDefinition.of("label", FieldType.STRING));
        type("post2", ref("tag", "tag", ReferencePolicy.RESTRICT));
        EntryView tag = entryService.create("tag",
                new EntryRequest(null, Map.of("label", "java")));
        entryService.publish("tag", tag.id(), null);
        EntryView post = entryService.create("post2",
                new EntryRequest(null, Map.of("tag", tag.id())));
        EntryView published = entryService.publish("post2", post.id(), null);
        assertEquals(tag.id(), published.values().get("tag").value());
    }

    @Test
    void referenceTypeMismatchIsRejected() {
        type("person", FieldDefinition.of("name", FieldType.STRING));
        type("thing", FieldDefinition.of("name", FieldType.STRING));
        type("story1", ref("person", "person", ReferencePolicy.RESTRICT));
        EntryView thing = entryService.create("thing",
                new EntryRequest(null, Map.of("name", "widget")));
        entryService.publish("thing", thing.id(), null);
        EntryView story = entryService.create("story1",
                new EntryRequest(null, Map.of("person", thing.id())));

        ContentModelException ex = assertThrows(ContentModelException.class,
                () -> entryService.publish("story1", story.id(), null));
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.getCode());
    }

    @Test
    void restrictPolicyBlocksUnpublishAndDeleteOfReferencedTarget() {
        type("brand", FieldDefinition.of("name", FieldType.STRING));
        type("product", ref("brand", "brand", ReferencePolicy.RESTRICT));
        EntryView brand = entryService.create("brand",
                new EntryRequest(null, Map.of("name", "acme")));
        entryService.publish("brand", brand.id(), null);
        EntryView product = entryService.create("product",
                new EntryRequest(null, Map.of("brand", brand.id())));
        entryService.publish("product", product.id(), null);

        ContentModelException unpub = assertThrows(ContentModelException.class,
                () -> entryService.unpublish("brand", brand.id()));
        assertEquals(ErrorCode.REFERENCE_INTEGRITY_VIOLATION, unpub.getCode());
        ContentModelException del = assertThrows(ContentModelException.class,
                () -> entryService.delete("brand", brand.id()));
        assertEquals(ErrorCode.REFERENCE_INTEGRITY_VIOLATION, del.getCode());
        // Target is still in place and published.
        EntryView stillThere = entryService.get("brand", brand.id(), "PUBLISHED", null, List.of());
        assertEquals("acme", stillThere.values().get("name").value());
    }

    @Test
    void cascadePolicyUnpublishesReferrerChain() {
        // parent <- child (cascade) <- grandchild (cascade)
        type("parent", FieldDefinition.of("name", FieldType.STRING));
        type("child", ref("parent", "parent", ReferencePolicy.CASCADE));
        type("grandchild", ref("child", "child", ReferencePolicy.CASCADE));

        EntryView parent = entryService.create("parent",
                new EntryRequest(null, Map.of("name", "p")));
        entryService.publish("parent", parent.id(), null);
        EntryView child = entryService.create("child",
                new EntryRequest(null, Map.of("parent", parent.id())));
        entryService.publish("child", child.id(), null);
        EntryView grandchild = entryService.create("grandchild",
                new EntryRequest(null, Map.of("child", child.id())));
        entryService.publish("grandchild", grandchild.id(), null);

        entryService.unpublish("parent", parent.id());

        assertThrows(ContentModelException.class,
                () -> entryService.get("child", child.id(), "PUBLISHED", null, List.of()));
        assertThrows(ContentModelException.class,
                () -> entryService.get("grandchild", grandchild.id(), "PUBLISHED", null, List.of()));
        // Drafts survive an unpublish cascade.
        assertEquals("p", entryService.get("parent", parent.id(), "DRAFT", null, List.of())
                .values().get("name").value());
    }

    @Test
    void cascadePolicyDeletesReferrerChain() {
        type("root", FieldDefinition.of("name", FieldType.STRING));
        type("leaf", ref("root", "root", ReferencePolicy.CASCADE));
        EntryView root = entryService.create("root",
                new EntryRequest(null, Map.of("name", "r")));
        entryService.publish("root", root.id(), null);
        EntryView leaf = entryService.create("leaf",
                new EntryRequest(null, Map.of("root", root.id())));
        entryService.publish("leaf", leaf.id(), null);

        entryService.delete("root", root.id());

        assertThrows(ContentModelException.class,
                () -> entryService.get("leaf", leaf.id(), "DRAFT", null, List.of()));
        assertThrows(ContentModelException.class,
                () -> entryService.get("root", root.id(), "DRAFT", null, List.of()));
    }

    @Test
    void draftReferenceToUnpublishedEntryDoesNotBlockSavingDraft() {
        type("drafttarget", FieldDefinition.of("name", FieldType.STRING));
        type("draftholder", ref("drafttarget", "drafttarget", ReferencePolicy.RESTRICT));
        EntryView target = entryService.create("drafttarget",
                new EntryRequest(null, Map.of("name", "t")));
        EntryView holder = entryService.create("draftholder",
                new EntryRequest(null, Map.of("drafttarget", target.id())));
        // No exception: drafts may reference unpublished entries.
        EntryView reread = entryService.get("draftholder", holder.id(), "DRAFT", null, List.of());
        assertEquals(target.id(), reread.values().get("drafttarget").value());
    }
}
