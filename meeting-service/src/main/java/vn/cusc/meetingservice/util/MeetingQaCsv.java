package vn.cusc.meetingservice.util;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Encodes quoted RFC 4180 rows and keeps untrusted cells literal in spreadsheets. */
public final class MeetingQaCsv {
    private MeetingQaCsv() { }

    public static String row(String... values) {
        return Arrays.stream(values).map(MeetingQaCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    private static String cell(String value) {
        String text = value == null ? "" : value;
        int index = 0;
        while (index < text.length()) {
            int point = text.codePointAt(index);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)
                && !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT) break;
            index += Character.charCount(point);
        }
        if (index < text.length() && "=+-@".indexOf(text.codePointAt(index)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
