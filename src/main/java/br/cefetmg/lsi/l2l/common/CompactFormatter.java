package br.cefetmg.lsi.l2l.common;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CompactFormatter extends Formatter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord r) {
        String ts = TS.format(Instant.ofEpochMilli(r.getMillis()));

        String src = r.getSourceClassName();
        if (src != null) {
            int dot = src.lastIndexOf('.');
            src = dot >= 0 ? src.substring(dot + 1) : src;
        } else {
            src = r.getLoggerName();
            if (src != null) {
                int dot = src.lastIndexOf('.');
                src = dot >= 0 ? src.substring(dot + 1) : src;
            } else {
                src = "?";
            }
        }
        String method = r.getSourceMethodName() != null ? r.getSourceMethodName() : "";

        StringBuilder sb = new StringBuilder(128);
        sb.append(ts)
          .append(" [").append(src).append('.').append(method).append(']')
          .append(' ').append(r.getLevel().getName())
          .append(' ').append(formatMessage(r))
          .append('\n');

        if (r.getThrown() != null) {
            StringWriter sw = new StringWriter();
            r.getThrown().printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }

        return sb.toString();
    }
}
