package com.github.cuixudong74.contentmodeljava.web;

import com.github.cuixudong74.contentmodeljava.dto.DeleteEntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryResponse;
import com.github.cuixudong74.contentmodeljava.dto.PublishRequest;
import com.github.cuixudong74.contentmodeljava.dto.PublishResponse;
import com.github.cuixudong74.contentmodeljava.dto.UnpublishRequest;
import com.github.cuixudong74.contentmodeljava.model.Entry;
import com.github.cuixudong74.contentmodeljava.model.EntryView;
import com.github.cuixudong74.contentmodeljava.service.EntryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/entries")
public class EntryController {

    private final EntryService entryService;

    public EntryController(EntryService entryService) {
        this.entryService = entryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse create(@RequestBody EntryRequest request) {
        Entry entry = entryService.create(request);
        return toResponse(entryService.readDraft(entry.id(), null, null));
    }

    @GetMapping("/{entryId}/draft")
    public EntryResponse readDraft(@PathVariable String entryId,
                                   @RequestParam(required = false) String lang,
                                   @RequestParam(required = false) List<String> fallback) {
        return toResponse(entryService.readDraft(entryId, lang, fallback));
    }

    @GetMapping("/{entryId}/published")
    public EntryResponse readPublished(@PathVariable String entryId,
                                       @RequestParam(required = false) String lang,
                                       @RequestParam(required = false) List<String> fallback) {
        return toResponse(entryService.readPublished(entryId, lang, fallback));
    }

    @PutMapping("/{entryId}/draft")
    public EntryResponse updateDraft(@PathVariable String entryId, @RequestBody EntryRequest request) {
        Entry entry = entryService.updateDraft(entryId, request);
        return toResponse(entryService.readDraft(entry.id(), null, null));
    }

    @PostMapping("/{entryId}/publish")
    public PublishResponse publish(@PathVariable String entryId, @RequestBody PublishRequest request) {
        Entry entry = entryService.publish(entryId, request);
        return new PublishResponse(entry.id(), entry.publishedVersion(),
                entry.publishedBaseDraftVersion());
    }

    @PostMapping("/{entryId}/unpublish")
    public EntryResponse unpublish(@PathVariable String entryId,
                                   @RequestBody(required = false) UnpublishRequest request) {
        Entry entry = entryService.unpublish(entryId, request);
        return toResponse(entryService.readDraft(entry.id(), null, null));
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String entryId,
                       @RequestBody(required = false) DeleteEntryRequest request) {
        entryService.delete(entryId, request);
    }

    private EntryResponse toResponse(EntryView view) {
        return new EntryResponse(view.id(), view.typeId(), view.typeVersion(), view.stage(),
                view.draftVersion(), view.publishedVersion(), view.baseDraftVersion(),
                view.requestedLanguage(), view.fallbackLanguages(), view.fields());
    }
}
