package vn.cusc.meetingservice.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import vn.cusc.meetingservice.exception.BusinessValidationException;

/** MIME comes from inspected bytes, never the upload's Content-Type header. */
public final class DocumentContentPolicy {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Map<String, String> OFFICE = Map.of("docx", "word/document.xml", "xlsx", "xl/workbook.xml", "pptx", "ppt/presentation.xml");
    private static final Set<String> INLINE_TYPES = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "text/plain");
    public record Inspection(String contentType, boolean inlineSafe) { }
    private DocumentContentPolicy() { }

    public static String fileName(String value) {
        if (value == null) throw invalid();
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.length() > 255 || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return name;
    }

    public static Inspection inspect(Path path, String rawName) throws IOException {
        String name = fileName(rawName);
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        long size = Files.size(path);
        if (size < 1 || size > MAX_BYTES) throw new BusinessValidationException("Tài liệu cần có nội dung và tối đa 20 MB.");
        byte[] head;
        try (var input = Files.newInputStream(path)) { head = input.readNBytes(8192); }
        if (extension.equals("pdf") && starts(head, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            try (PDDocument document = PDDocument.load(path.toFile(), MemoryUsageSetting.setupMixed(2L * 1024 * 1024))) {
                return new Inspection("application/pdf", !hasActivePdfActions(document));
            } catch (InvalidPasswordException encrypted) {
                return new Inspection("application/pdf", false);
            } catch (IOException malformed) { throw invalid(); }
        }
        if (extension.equals("png") && starts(head, new byte[] {(byte)137, 80, 78, 71, 13, 10, 26, 10}))
            return new Inspection("image/png", true);
        if ((extension.equals("jpg") || extension.equals("jpeg")) && starts(head, new byte[] {(byte)255, (byte)216, (byte)255}))
            return new Inspection("image/jpeg", true);
        if (extension.equals("gif") && (starts(head, "GIF87a".getBytes()) || starts(head, "GIF89a".getBytes())))
            return new Inspection("image/gif", true);
        if (extension.equals("webp") && head.length >= 12 && starts(head, "RIFF".getBytes())
            && new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
            return new Inspection("image/webp", true);
        if ((extension.equals("txt") || extension.equals("csv")) && plainText(head))
            return new Inspection("text/plain", true);
        if ((OFFICE.containsKey(extension) || extension.equals("zip")) && starts(head, new byte[] {80, 75})) {
            inspectArchive(path, extension);
            String type = switch (extension) {
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                default -> "application/zip";
            };
            return new Inspection(type, false);
        }
        if (Set.of("doc", "xls", "ppt").contains(extension)
            && starts(head, new byte[] {(byte)208, (byte)207, 17, (byte)224, (byte)161, (byte)177, 26, (byte)225}))
            return new Inspection(switch(extension) { case "doc" -> "application/msword"; case "xls" -> "application/vnd.ms-excel"; default -> "application/vnd.ms-powerpoint"; }, false);
        throw invalid();
    }

    public static boolean inlineType(String type) { return type != null && INLINE_TYPES.contains(type.toLowerCase(Locale.ROOT)); }

    private static boolean plainText(byte[] bytes) {
        for (byte value : bytes) {
            int unsigned = value & 255;
            if (unsigned == 0 || unsigned < 32 && unsigned != 9 && unsigned != 10 && unsigned != 13) return false;
        }
        return true;
    }
    private static boolean starts(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (bytes[index] != prefix[index]) return false;
        return true;
    }
    private static void inspectArchive(Path path, String extension) throws IOException {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            if (OFFICE.containsKey(extension) && (archive.getEntry("[Content_Types].xml") == null || archive.getEntry(OFFICE.get(extension)) == null)) throw invalid();
            long expanded = 0; int count = 0;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                expanded += Math.max(0, entry.getSize());
                if (++count > 10000 || expanded > 200L * 1024 * 1024 || entry.getSize() < 0) throw invalid();
                if (!extension.equals("zip") && entry.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) throw invalid();
            }
        } catch (java.util.zip.ZipException malformed) { throw invalid(); }
    }
    private static boolean hasActivePdfActions(PDDocument document) {
        var pending = new ArrayDeque<COSBase>();
        var visited = new IdentityHashMap<COSBase, Boolean>();
        pending.add(document.getDocumentCatalog().getCOSObject());
        while (!pending.isEmpty()) {
            COSBase value = pending.removeFirst();
            if (visited.put(value, true) != null) continue;
            if (visited.size() > 30000) return true;
            if (value instanceof COSObject object) { if (object.getObject() != null) pending.add(object.getObject()); }
            else if (value instanceof COSArray array) { for (COSBase item : array) if (item != null) pending.add(item); }
            else if (value instanceof COSDictionary dictionary) {
                if (dictionary.containsKey(COSName.getPDFName("JS")) || dictionary.containsKey(COSName.getPDFName("JavaScript"))) return true;
                String action = dictionary.getNameAsString(COSName.S);
                if (Set.of("JavaScript", "Launch", "SubmitForm", "ImportData", "Rendition", "GoToR").contains(action == null ? "" : action)) return true;
                dictionary.getValues().stream().filter(java.util.Objects::nonNull).forEach(pending::add);
            }
        }
        return false;
    }
    private static BusinessValidationException invalid() {
        return new BusinessValidationException("Loại hoặc nội dung tài liệu không hợp lệ. Hỗ trợ PDF, Office, ảnh, TXT, CSV và ZIP; không nhận HTML, SVG hoặc mã thực thi.");
    }
}
