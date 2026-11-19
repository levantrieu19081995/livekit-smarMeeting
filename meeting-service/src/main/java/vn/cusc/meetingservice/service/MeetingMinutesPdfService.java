 package vn.cusc.meetingservice.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.dto.request.RenderMinutesRequest;
import vn.cusc.meetingservice.dto.response.MinutesRenderResponse;
import vn.cusc.meetingservice.exception.BusinessValidationException;

@Service
public class MeetingMinutesPdfService {

    private static final String PDF_FONT_FAMILY = "CUSC Noto Serif";
    private static final String REGULAR_FONT_RESOURCE =
        "/fonts/NotoSerif-Regular.ttf";
    private static final String BOLD_FONT_RESOURCE =
        "/fonts/NotoSerif-Bold.ttf";

    private final MeetingMinutesTemplateService meetingMinutesTemplateService;

    public MeetingMinutesPdfService(
        MeetingMinutesTemplateService meetingMinutesTemplateService
    ) {
        this.meetingMinutesTemplateService = meetingMinutesTemplateService;
    }

    public byte[] exportPdf(
        Long meetingId,
        Integer version,
        RenderMinutesRequest request
    ) {
        MinutesRenderResponse rendered =
            meetingMinutesTemplateService.render(meetingId, version, request);

        if (!rendered.canExportPdf()) {
            throw new BusinessValidationException(
                "Chỉ có thể xuất PDF từ phiên bản biên bản đã hoàn tất"
            );
        }

        String html = rendered.html();

        if (html == null || html.isBlank()) {
            throw new IllegalStateException(
                "Không có nội dung HTML để xuất PDF"
            );
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            builder.useFastMode();
            registerPdfFonts(builder);

            String xhtml = toXhtmlCompatibleHtml(html);

            builder.withHtmlContent(xhtml, null);
            builder.toStream(outputStream);
            builder.run();

            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Không thể tạo PDF biên bản",
                exception
            );
        }
    }

    private void registerPdfFonts(PdfRendererBuilder builder) {
        verifyFontResource(REGULAR_FONT_RESOURCE, "NotoSerif-Regular.ttf");
        verifyFontResource(BOLD_FONT_RESOURCE, "NotoSerif-Bold.ttf");

        builder.useFont(
            () -> fontStream(REGULAR_FONT_RESOURCE),
            PDF_FONT_FAMILY,
            400,
            FontStyle.NORMAL,
            true
        );
        builder.useFont(
            () -> fontStream(BOLD_FONT_RESOURCE),
            PDF_FONT_FAMILY,
            700,
            FontStyle.NORMAL,
            true
        );
    }

    private void verifyFontResource(String resourcePath, String fileName) {
        try (InputStream stream = fontStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException(
                    "Không tìm thấy font PDF: " + fileName
                );
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Không tìm thấy font PDF: " + fileName,
                exception
            );
        }
    }

    private InputStream fontStream(String resourcePath) {
        return MeetingMinutesPdfService.class.getResourceAsStream(resourcePath);
    }

    private String toXhtmlCompatibleHtml(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }

        return html
            .replaceFirst(
                "(?i)<!doctype\\s+html\\s*>",
                "<!DOCTYPE html>"
            )
            .replaceAll(
                "(?i)<meta([^>]*?)(?<!/)>",
                "<meta$1 />"
            );
    }
}
