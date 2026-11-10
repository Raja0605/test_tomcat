package com.shartechnologies.documents;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class DocumentStorageService {
    private final Path storageDirectory;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<PosixFilePermission> DOCUMENT_PERMISSIONS =
            PosixFilePermissions.fromString("rw-r--r--");

    public DocumentStorageService(@Value("${document.storage-directory}") String storageDirectory) {
        this.storageDirectory = Paths.get(storageDirectory);
    }

    @PostConstruct
    public void initializeStorage() {
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException exception) {
            throw new StorageException("Cannot create document storage directory.", exception);
        }
    }

    public DocumentMetadata upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Select a non-empty PDF or TXT file.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("The maximum upload size is 10 MB.");
        }

        String originalName = file.getOriginalFilename();
        String extension = extensionOf(originalName);
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        validateContent(file, extension, contentType);

        String filename = "upload-" + UUID.randomUUID() + "." + extension;
        Path destination = resolveDocumentPath(filename);
        try {
            Files.createFile(destination, PosixFilePermissions.asFileAttribute(DOCUMENT_PERMISSIONS));
            try (InputStream input = file.getInputStream();
                 var output = Files.newOutputStream(destination, StandardOpenOption.WRITE)) {
                input.transferTo(output);
            }
            return metadata(destination);
        } catch (IOException exception) {
            deleteAfterFailedWrite(destination, exception);
            throw new StorageException("Cannot save the uploaded document.", exception);
        }
    }

    public DocumentMetadata createTestDocument() {
        String filename = "test-" + UUID.randomUUID() + ".txt";
        Path destination = resolveDocumentPath(filename);
        String contents = "Timestamp: " + Instant.now() + System.lineSeparator()
                + "Linux process username: " + System.getProperty("user.name") + System.lineSeparator();
        try {
            Files.createFile(destination, PosixFilePermissions.asFileAttribute(DOCUMENT_PERMISSIONS));
            Files.writeString(destination, contents, StandardOpenOption.WRITE);
            return metadata(destination);
        } catch (IOException exception) {
            deleteAfterFailedWrite(destination, exception);
            throw new StorageException("Cannot create the test document.", exception);
        }
    }

    public List<DocumentMetadata> listDocuments() {
        try (Stream<Path> paths = Files.list(storageDirectory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isSupportedFilename(path.getFileName().toString()))
                    .map(this::metadata)
                    .sorted(Comparator.comparing(DocumentMetadata::uploadedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new StorageException("Cannot list documents.", exception);
        }
    }

    public DocumentMetadata getDocument(String filename) {
        Path path = requireExistingDocument(filename);
        return metadata(path);
    }

    public void deleteDocument(String filename) {
        Path path = requireExistingDocument(filename);
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw new StorageException("Cannot delete the requested document.", exception);
        }
    }

    public PermissionCheckResponse runPermissionChecks() {
        List<DocumentMetadata> beforeProbe = listDocuments();
        List<PermissionTestResult> tests = new ArrayList<>();
        DocumentMetadata probe = null;

        try {
            probe = createTestDocument();
            tests.add(result("backend-create", "Backend can create a document", true,
                    "Created " + probe.filename() + " as " + System.getProperty("user.name") + "."));
        } catch (StorageException exception) {
            tests.add(result("backend-create", "Backend can create a document", false, exception.getMessage()));
        }

        if (probe == null) {
            tests.add(result("backend-read", "Backend can read the document", false,
                    "The backend could not create a test document."));
        } else {
            try {
                byte[] contents = Files.readAllBytes(resolveDocumentPath(probe.filename()));
                boolean readable = contents.length > 0;
                tests.add(result("backend-read", "Backend can read the document", readable,
                        readable ? "Read the generated test document successfully." : "The generated document was empty."));
            } catch (IOException exception) {
                tests.add(result("backend-read", "Backend can read the document", false,
                        "Could not read the generated test document: " + exception.getMessage()));
            }
        }

        DocumentMetadata latestUpload = beforeProbe.stream()
                .filter(document -> document.filename().startsWith("upload-"))
                .max(Comparator.comparing(DocumentMetadata::uploadedAt))
                .orElse(null);
        DocumentMetadata existingDocument = beforeProbe.stream().findFirst().orElse(probe);
        if (existingDocument == null) {
            tests.add(result("nginx-read-existing", "Nginx user (www-data) can read an existing document",
                    false, "No document is available to check."));
            tests.add(result("nginx-no-write", "Nginx user (www-data) cannot modify or delete documents",
                    false, "No document is available to check."));
        } else {
            tests.add(checkNginxRead(existingDocument, "nginx-read-existing",
                    "Nginx user (www-data) can read an existing document"));
            tests.add(checkNginxNoWrite(existingDocument));
        }
        tests.add(latestUpload == null
                ? result("nginx-read-new-upload", "Newly uploaded files are readable by Nginx",
                        false, "No uploaded file exists yet. Upload a PDF or TXT file and run the checks again.")
                : checkNginxRead(latestUpload, "nginx-read-new-upload",
                        "Newly uploaded files are readable by Nginx"));
        tests.add(beforeProbe.isEmpty()
                ? result("nginx-read-existing-files", "Existing files are readable by Nginx",
                        false, "No documents existed before this check. Create or upload a document and retry.")
                : checkNginxRead(beforeProbe.get(0), "nginx-read-existing-files",
                        "Existing files are readable by Nginx"));

        return new PermissionCheckResponse(Instant.now(), List.copyOf(tests),
                "Nginx results evaluate POSIX permission bits only; this application never impersonates www-data. "
                        + "An administrator must confirm actual access with the sudo commands in the README.");
    }

    private PermissionTestResult checkNginxRead(DocumentMetadata document, String id, String label) {
        Path path = resolveDocumentPath(document.filename());
        boolean readable = nginxCanReadByMode(path);
        return result(id, label, readable, readable
                ? "File is world-readable and every parent directory is traversable by other users. "
                        + "Confirm actual www-data access with sudo."
                : "POSIX mode bits do not guarantee read/traverse access to www-data. "
                        + "Check the file and every parent directory.");
    }

    private PermissionTestResult checkNginxNoWrite(DocumentMetadata document) {
        Path path = resolveDocumentPath(document.filename());
        boolean protectedFromWrite = hasNoGroupOrOtherWrite(path)
                && hasNoGroupOrOtherWrite(storageDirectory);
        return result("nginx-no-write", "Nginx user (www-data) cannot modify or delete documents",
                protectedFromWrite, protectedFromWrite
                        ? "File and storage directory grant no group/other write permission. "
                                + "Confirm actual www-data access with sudo."
                        : "Group/other write permission is present on the file or storage directory.");
    }

    private boolean nginxCanReadByMode(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !hasPermission(file, PosixFilePermission.OTHERS_READ)) {
            return false;
        }
        for (Path directory = storageDirectory; directory != null; directory = directory.getParent()) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !hasPermission(directory, PosixFilePermission.OTHERS_EXECUTE)) {
                return false;
            }
            if (directory.equals(directory.getRoot())) {
                break;
            }
        }
        return true;
    }

    private boolean hasNoGroupOrOtherWrite(Path path) {
        return !hasPermission(path, PosixFilePermission.GROUP_WRITE)
                && !hasPermission(path, PosixFilePermission.OTHERS_WRITE);
    }

    private boolean hasPermission(Path path, PosixFilePermission permission) {
        try {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).contains(permission);
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }

    private PermissionTestResult result(String id, String label, boolean passed, String details) {
        return new PermissionTestResult(id, label, passed ? "PASS" : "FAIL", details);
    }

    private void validateContent(MultipartFile file, String extension, String contentType) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(8);
            if ("pdf".equals(extension)) {
                if (!"application/pdf".equals(contentType)
                        || header.length < 5
                        || header[0] != '%'
                        || header[1] != 'P'
                        || header[2] != 'D'
                        || header[3] != 'F'
                        || header[4] != '-') {
                    throw new IllegalArgumentException("PDF files must have a .pdf extension, application/pdf type, and PDF signature.");
                }
                return;
            }
            if (!"text/plain".equals(contentType)) {
                throw new IllegalArgumentException("TXT files must use the text/plain content type.");
            }
            if (containsNullByte(header)) {
                throw new IllegalArgumentException("The TXT file contains binary data.");
            }
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (containsNullByte(chunk, count)) {
                    throw new IllegalArgumentException("The TXT file contains binary data.");
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Cannot validate the uploaded document.", exception);
        }
    }

    private boolean containsNullByte(byte[] bytes) {
        return containsNullByte(bytes, bytes.length);
    }

    private boolean containsNullByte(byte[] bytes, int length) {
        for (int index = 0; index < length; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private String extensionOf(String originalName) {
        if (originalName == null) {
            throw new IllegalArgumentException("The uploaded file must have a .pdf or .txt extension.");
        }
        int dot = originalName.lastIndexOf('.');
        String extension = dot < 0 ? "" : originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("pdf") && !extension.equals("txt")) {
            throw new IllegalArgumentException("Only PDF and TXT files are accepted.");
        }
        return extension;
    }

    private boolean isSupportedFilename(String filename) {
        return filename.matches("(upload|test)-[0-9a-fA-F-]{36}\\.(pdf|txt)");
    }

    private Path resolveDocumentPath(String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}")
                || filename.equals(".") || filename.equals("..")) {
            throw new IllegalArgumentException("Invalid document filename.");
        }
        Path path = storageDirectory.resolve(filename).normalize();
        if (!storageDirectory.equals(path.getParent())) {
            throw new IllegalArgumentException("Invalid document filename.");
        }
        return path;
    }

    private Path requireExistingDocument(String filename) {
        Path path = resolveDocumentPath(filename);
        if (!isSupportedFilename(filename) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new DocumentNotFoundException("Document not found.");
        }
        return path;
    }

    private DocumentMetadata metadata(Path path) {
        try {
            return new DocumentMetadata(path.getFileName().toString(), Files.size(path),
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant(),
                    "/PhotoPath/" + path.getFileName());
        } catch (IOException exception) {
            throw new StorageException("Cannot read document metadata.", exception);
        }
    }

    private void deleteAfterFailedWrite(Path path, IOException originalException) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupException) {
            originalException.addSuppressed(cleanupException);
        }
    }

    static class DocumentNotFoundException extends RuntimeException {
        DocumentNotFoundException(String message) {
            super(message);
        }
    }

    static class StorageException extends RuntimeException {
        StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
