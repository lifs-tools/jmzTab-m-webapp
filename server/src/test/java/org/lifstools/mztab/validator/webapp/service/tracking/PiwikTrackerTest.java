package org.lifstools.mztab.validator.webapp.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PiwikTrackerTest {

    @Test
    void trackingUriIsAnonymous() throws Exception {
        PiwikTracker tracker = new PiwikTracker("3", "https://matomo.example.org/matomo.php",
            "https://validator.example.org/mztabvalidator");
        String uri = tracker.buildUri("validate-started", "mztab").toUriString();
        assertFalse(uri.contains("uid="), uri);
        assertFalse(Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            .matcher(uri).find(), uri);
        assertTrue(uri.contains("action_name=validate-started"), uri);
        assertTrue(uri.contains("e_a=mztab"), uri);
        assertEquals(1, uri.split("idsite=", -1).length - 1);
    }
}
