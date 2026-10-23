package org.lifstools.mztab2.server.invoker;

import com.fasterxml.jackson.databind.util.StdDateFormat;
import java.text.FieldPosition;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class RFC3339DateFormat extends StdDateFormat {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);

    @Override
    public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition fieldPosition) {
        toAppendTo.append(FORMATTER.format(date.toInstant()));
        return toAppendTo;
    }
}
