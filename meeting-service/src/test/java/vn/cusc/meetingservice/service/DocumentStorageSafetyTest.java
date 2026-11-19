package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import vn.cusc.meetingservice.config.StorageProperties;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.service.impl.LocalFileStorageServiceImpl;
import vn.cusc.meetingservice.util.*;

class DocumentStorageSafetyTest {
    @TempDir Path directory;
    @Test void forgedMimeCannotTurnHtmlIntoAnInlinePdf() throws Exception {
        var storage = storage(); byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(BusinessValidationException.class, () -> storage.storeMeetingFile(1L, new MockMultipartFile("file", "fake.pdf", "application/pdf", html)));
        assertThrows(BusinessValidationException.class, () -> storage.storeMeetingFile(1L, new MockMultipartFile("file", "active.svg", "image/png", html)));
        try (var files = Files.walk(directory)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }
    @Test void storesCanonicalMimeAndActualByteLengthRatherThanClientHeaders() throws Exception {
        var result = storage().storeMeetingFile(1L, new MockMultipartFile("file", "note.txt", "text/html", "hello".getBytes()));
        assertEquals("text/plain", result.contentType()); assertEquals(5, result.size()); assertTrue(Files.exists(Path.of(result.storagePath())));
    }
    @Test void realPdfCanBeViewedButJavascriptPdfIsAttachmentOnly() throws Exception {
        Path safe = directory.resolve("safe.pdf"), active = directory.resolve("active.pdf");
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(safe.toFile()); }
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('x')")); pdf.save(active.toFile()); }
        assertTrue(DocumentContentPolicy.inspect(safe, "safe.pdf").inlineSafe());
        assertFalse(DocumentContentPolicy.inspect(active, "active.pdf").inlineSafe());
    }
    @Test void malformedAndWrongOfficeArchiveAreRejectedAndMacrosAreNotAcceptedAsDocx() throws Exception {
        Path file = directory.resolve("office.zip");
        try (var archive = new ZipOutputStream(Files.newOutputStream(file))) { archive.putNextEntry(new ZipEntry("hello.txt")); archive.write("hello".getBytes()); archive.closeEntry(); }
        assertThrows(BusinessValidationException.class, () -> DocumentContentPolicy.inspect(file, "fake.docx"));
        try (var archive = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : new String[] {"[Content_Types].xml", "word/document.xml", "word/vbaProject.bin"}) {
                archive.putNextEntry(new ZipEntry(name)); archive.write("value".getBytes()); archive.closeEntry();
            }
        }
        assertThrows(BusinessValidationException.class, () -> DocumentContentPolicy.inspect(file, "macro.docx"));
    }
    @Test void validOfficeIsAvailableAsDownloadWithoutInlineExecution() throws Exception {
        Path file = directory.resolve("office.docx");
        try (var archive = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : new String[] {"[Content_Types].xml", "word/document.xml"}) {
                archive.putNextEntry(new ZipEntry(name)); archive.write("value".getBytes()); archive.closeEntry();
            }
        }
        var type = DocumentContentPolicy.inspect(file, "office.docx"); assertFalse(type.inlineSafe()); assertTrue(type.contentType().contains("wordprocessingml"));
    }
    @Test void refusesOutsideStoragePathAndUnsafeFileName() throws Exception {
        assertThrows(java.io.IOException.class, () -> storage().load(directory.getParent().resolve("outside.txt")));
        assertThrows(BusinessValidationException.class, () -> storage().storeMeetingFile(1L, new MockMultipartFile("file", "a\r\nb.txt", "text/plain", "x".getBytes())));
    }
    @Test void partialCopyFailureDoesNotLeaveAnUnreferencedStagingFile() throws Exception {
        var file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes()) {
            @Override public java.io.InputStream getInputStream() {
                return new java.io.InputStream() { int index; @Override public int read() throws java.io.IOException { if (++index > 2) throw new java.io.IOException("disk read failure"); return 65; } };
            }
        };
        assertThrows(java.io.IOException.class, () -> storage().storeMeetingFile(1L, file));
        try (var files = Files.walk(directory)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }
    private LocalFileStorageServiceImpl storage() { var properties = new StorageProperties(); properties.setUploadDir(directory.toString()); return new LocalFileStorageServiceImpl(properties, new FileNameUtils()); }
}
