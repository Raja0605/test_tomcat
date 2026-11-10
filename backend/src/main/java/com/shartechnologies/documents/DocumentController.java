package com.shartechnologies.documents;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentStorageService storageService;

    public DocumentController(DocumentStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentMetadata> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(storageService.upload(file));
    }

    @GetMapping
    public List<DocumentMetadata> list() {
        return storageService.listDocuments();
    }

    @GetMapping("/permission-check")
    public PermissionCheckResponse permissionCheck() {
        return storageService.runPermissionChecks();
    }

    @GetMapping("/{filename:.+}")
    public DocumentMetadata get(@PathVariable String filename) {
        return storageService.getDocument(filename);
    }

    @PostMapping("/create-test")
    public ResponseEntity<DocumentMetadata> createTest() {
        return ResponseEntity.status(HttpStatus.CREATED).body(storageService.createTestDocument());
    }

    @DeleteMapping("/{filename:.+}")
    public ResponseEntity<Void> delete(@PathVariable String filename) {
        storageService.deleteDocument(filename);
        return ResponseEntity.noContent().build();
    }
}
