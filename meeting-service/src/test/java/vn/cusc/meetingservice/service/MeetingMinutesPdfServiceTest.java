package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.cusc.meetingservice.dto.request.RenderMinutesRequest;
import vn.cusc.meetingservice.dto.response.MinutesRenderResponse;
import vn.cusc.meetingservice.dto.response.MinutesTemplateResponse;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.model.enums.MinutesTemplateCode;

class MeetingMinutesPdfServiceTest {

    private final MeetingMinutesTemplateService templateService =
        Mockito.mock(MeetingMinutesTemplateService.class);
    private final MeetingMinutesPdfService service =
        new MeetingMinutesPdfService(templateService);

    @Test
    void pdfFontsAreAvailableOnClasspath() throws Exception {
        try (
            InputStream regular = MeetingMinutesPdfService.class
                .getResourceAsStream("/fonts/NotoSerif-Regular.ttf");
            InputStream bold = MeetingMinutesPdfService.class
                .getResourceAsStream("/fonts/NotoSerif-Bold.ttf")
        ) {
            assertNotNull(regular);
            assertNotNull(bold);
        }
    }

    @Test
    void finalCreatesPdf() {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.FINAL,
                "<!DOCTYPE html><html><body><h1>BIÊN BẢN HỌP</h1></body></html>",
                true
            ));

        byte[] bytes = service.exportPdf(12L, 2, request);

        assertTrue(bytes.length > 0);
        assertEquals("%PDF-", signature(bytes));
    }

    @Test
    void draftIsRejected() {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.DRAFT,
                "<!DOCTYPE html><html><body>DRAFT</body></html>",
                false
            ));

        BusinessValidationException exception = assertThrows(
            BusinessValidationException.class,
            () -> service.exportPdf(12L, 2, request)
        );

        assertEquals(
            "Chỉ có thể xuất PDF từ phiên bản biên bản đã hoàn tất",
            exception.getMessage()
        );
    }

    @Test
    void blankHtmlIsRejected() {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(MinutesStatus.FINAL, "", true));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> service.exportPdf(12L, 2, request)
        );

        assertEquals("Không có nội dung HTML để xuất PDF", exception.getMessage());
    }

    @Test
    void selectedVersionIsForwardedExactly() {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.FINAL,
                "<!DOCTYPE html><html><body>V2</body></html>",
                true
            ));

        service.exportPdf(12L, 2, request);

        verify(templateService).render(12L, 2, request);
    }

    @Test
    void rendererFailureDoesNotReturnEmptyPdf() {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.FINAL,
                "<!DOCTYPE html><html><body><div>&broken;</div></body></html>",
                true
            ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> service.exportPdf(12L, 2, request)
        );

        assertEquals("Không thể tạo PDF biên bản", exception.getMessage());
    }

    @Test
    void vietnameseSmokeCreatesPdf() throws Exception {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.FINAL,
                """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8" />
                <style>
                  body { font-family: "CUSC Noto Serif", serif; }
                  h1, h2 { font-weight: 700; }
                </style>
                </head>
                <body>
                <h1>BIÊN BẢN HỌP</h1>
                <h2>THÔNG TIN CHUNG</h2>
                <p>Cuộc họp bắt đầu</p>
                <p>Địa điểm</p>
                <h2>THÀNH PHẦN THAM DỰ</h2>
                <p>Nguyễn Văn A</p>
                <h2>NỘI DUNG CHÍNH</h2>
                <h2>KẾT LUẬN</h2>
                <p>CHỦ TỌA</p>
                <p>THƯ KÝ</p>
                </body>
                </html>
                """,
                true
            ));

        byte[] bytes = service.exportPdf(12L, 2, request);

        assertTrue(bytes.length > 0);
        assertEquals("%PDF-", signature(bytes));

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            String text = new PDFTextStripper().getText(document);

            assertTrue(text.contains("BIÊN BẢN HỌP"));
            assertTrue(text.contains("THÔNG TIN CHUNG"));
            assertTrue(text.contains("THÀNH PHẦN THAM DỰ"));
            assertTrue(text.contains("KẾT LUẬN"));
            assertTrue(text.contains("CHỦ TỌA"));
            assertTrue(text.contains("THƯ KÝ"));
            assertTrue(text.contains("Cuộc họp bắt đầu"));
            assertTrue(text.contains("Địa điểm"));
            assertTrue(text.contains("Nguyễn Văn A"));
            assertFalse(text.contains("BIÊN B#N"));
            assertFalse(text.contains("CH# T#A"));
            assertFalse(text.contains("TH# KÝ"));
        }
    }

    @Test
    void longHtmlCreatesMultiplePages() throws Exception {
        RenderMinutesRequest request = request();
        StringBuilder body = new StringBuilder("<!DOCTYPE html><html><body>");
        for (int index = 0; index < 200; index++) {
            body.append("<p>Dong noi dung dai so ")
                .append(index)
                .append("</p>");
        }
        body.append("</body></html>");
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(MinutesStatus.FINAL, body.toString(), true));

        byte[] bytes = service.exportPdf(12L, 2, request);

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            assertTrue(document.getNumberOfPages() >= 2);
        }
    }

    @Test
    void html5MetaWithoutSelfClosingStillCreatesPdf() {
        RenderMinutesRequest request = request();

        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.FINAL,
                """
                <!doctype html>
                <html>
                  <head>
                    <meta charset="UTF-8">
                    <title>Biên bản họp</title>
                  </head>
                  <body>BIÊN BẢN HỌP</body>
                </html>
                """,
                true
            ));

        byte[] bytes = service.exportPdf(12L, 2, request);

        assertTrue(bytes.length > 0);
        assertEquals("%PDF-", signature(bytes));
    }

    @Test
    void xhtmlCompatibleDocumentStillCreatesPdf() {
        RenderMinutesRequest request = request();
        when(templateService.render(12L, 2, request))
            .thenReturn(rendered(
                MinutesStatus.FINAL,
                """
                <!DOCTYPE html>
                <html>
                  <head>
                    <meta charset="UTF-8" />
                  </head>
                  <body>Biên bản</body>
                </html>
                """,
                true
            ));

        byte[] bytes = service.exportPdf(12L, 2, request);

        assertTrue(bytes.length > 0);
        assertEquals("%PDF-", signature(bytes));
    }

    @Test
    void templateContainsPrintCssAndPdfFontFamily() throws Exception {
        try (InputStream stream = MeetingMinutesPdfService.class
            .getResourceAsStream("/templates/minutes/cusc-standard.html")) {
            assertNotNull(stream);
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(template.contains("@page"));
            assertTrue(template.contains("size: A4"));
            assertTrue(template.contains("@media print"));
            assertTrue(template.contains("background: #ffffff"));
            assertTrue(template.contains("font-family: \"CUSC Noto Serif\""));
        }
    }

    private RenderMinutesRequest request() {
        return new RenderMinutesRequest(MinutesTemplateCode.CUSC_STANDARD);
    }

    private MinutesRenderResponse rendered(
        MinutesStatus status,
        String html,
        boolean canExportPdf
    ) {
        return new MinutesRenderResponse(
            12L,
            2,
            status,
            new MinutesTemplateResponse(
                "CUSC_STANDARD",
                "Biên bản họp CUSC",
                "Mẫu biên bản họp CUSC - BM07.40/PM/CUSC"
            ),
            html,
            canExportPdf
        );
    }

    private String signature(byte[] bytes) {
        return new String(
            Arrays.copyOfRange(bytes, 0, 5),
            StandardCharsets.US_ASCII
        );
    }
}
