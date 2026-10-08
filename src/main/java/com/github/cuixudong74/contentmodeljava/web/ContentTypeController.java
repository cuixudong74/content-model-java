package com.github.cuixudong74.contentmodeljava.web;

import com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.dto.ContentTypeResponse;
import com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest;
import com.github.cuixudong74.contentmodeljava.model.ContentType;
import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/content-types")
public class ContentTypeController {

    private final ContentTypeService contentTypeService;

    public ContentTypeController(ContentTypeService contentTypeService) {
        this.contentTypeService = contentTypeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContentTypeResponse create(@RequestBody ContentTypeRequest request) {
        return toResponse(contentTypeService.create(request));
    }

    @GetMapping("/{typeId}")
    public ContentTypeResponse get(@PathVariable String typeId) {
        return toResponse(contentTypeService.get(typeId));
    }

    @GetMapping
    public List<ContentTypeResponse> list() {
        return contentTypeService.list().stream().map(this::toResponse).toList();
    }

    @PostMapping("/{typeId}/fields")
    public ContentTypeResponse addField(@PathVariable String typeId,
                                        @RequestBody FieldDefinitionRequest request) {
        return toResponse(contentTypeService.addField(typeId, request));
    }

    @PutMapping("/{typeId}/fields/{fieldId}")
    public ContentTypeResponse updateField(@PathVariable String typeId, @PathVariable String fieldId,
                                           @RequestBody FieldDefinitionRequest request) {
        return toResponse(contentTypeService.updateField(typeId, fieldId, request));
    }

    @DeleteMapping("/{typeId}/fields/{fieldId}")
    public ContentTypeResponse removeField(@PathVariable String typeId, @PathVariable String fieldId) {
        return toResponse(contentTypeService.removeField(typeId, fieldId));
    }

    private ContentTypeResponse toResponse(ContentType type) {
        return new ContentTypeResponse(type.id(), type.displayName(), type.version(), type.fields());
    }
}
