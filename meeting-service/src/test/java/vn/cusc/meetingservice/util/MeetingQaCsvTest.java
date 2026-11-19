package vn.cusc.meetingservice.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MeetingQaCsvTest {
    @Test
    void quotesCommaDoubleQuoteAndEmbeddedNewlineWithCrLfRowEnding() {
        assertEquals("\"Line, \"\"quoted\"\"\r\nnext\",\"name\"\r\n", MeetingQaCsv.row("Line, \"quoted\"\r\nnext", "name"));
    }

    @Test
    void preservesUnicodeAndEmptyCells() {
        assertEquals("\"\",\"\",\"Ẩn danh\",\"Chờ duyệt\"\r\n", MeetingQaCsv.row(null, "", "Ẩn danh", "Chờ duyệt"));
    }

    @ParameterizedTest
    @MethodSource("formulaCases")
    void untrustedCellsCannotStartSpreadsheetFormulasAfterIgnoredPrefixes(String input, String expected) {
        assertEquals(expected, MeetingQaCsv.row(input));
    }

    private static Stream<Arguments> formulaCases() {
        return Stream.of(
            Arguments.of("=SUM(1,2)", "\"'=SUM(1,2)\"\r\n"),
            Arguments.of("+cmd", "\"'+cmd\"\r\n"),
            Arguments.of("-1+2", "\"'-1+2\"\r\n"),
            Arguments.of("@SUM(1)", "\"'@SUM(1)\"\r\n"),
            Arguments.of(" \t=1", "\"' \t=1\"\r\n"),
            Arguments.of("\u00A0-1", "\"'\u00A0-1\"\r\n"),
            Arguments.of("\u2003@SUM(1)", "\"'\u2003@SUM(1)\"\r\n"),
            Arguments.of("\uFEFF=1", "\"'\uFEFF=1\"\r\n"),
            Arguments.of("\u200B=1", "\"'\u200B=1\"\r\n"),
            Arguments.of("\u0000\r\n\u2066+cmd", "\"'\u0000\r\n\u2066+cmd\"\r\n")
        );
    }

    @Test
    void ordinaryTextContainingEqualsRemainsLiteralWithoutExtraPrefix() {
        assertEquals("\"Số người = 5\",\"5\",\"06/10/2026 01:00:00\"\r\n",
            MeetingQaCsv.row("Số người = 5", "5", "06/10/2026 01:00:00"));
    }
}
