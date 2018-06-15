/*
 * Copyright 2018 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.isas.lipidomics.mztab.validator.webapp.service.tracking;

import de.isas.lipidomics.mztab.validator.webapp.service.AnalyticsTracker;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Implementation to record basic application actions with Piwik / Matomo.
 *
 * @author nilshoffmann
 */
@Slf4j
@Service
public class PiwikTracker implements AnalyticsTracker {

    private final Integer gaId;

    private final boolean enabled;

    private final URL appUrl;

    private final URL piwikServerUrl;

    @Autowired
    public PiwikTracker(@Value("${ga.id}") String gaId,
        @Value("${ga.url}") String piwikServerUrl,
        @Value("${ga.app.url}") String appUrl) throws MalformedURLException {
        if (gaId == null || gaId.isEmpty() || piwikServerUrl == null || piwikServerUrl.
            isEmpty() || appUrl == null || appUrl.
                isEmpty()) {
            this.gaId = null;
            enabled = false;
            this.piwikServerUrl = null;
            this.appUrl = null;
            log.info(
                "Disabling piwik tracker. To enable, set 'ga.id' property to your site id, 'ga.url' to the full URL of your piwik.php script, and 'ga.app.url' to the url of your application.");

        } else {
            this.gaId = Integer.parseInt(gaId);
            enabled = true;
            this.piwikServerUrl = new URL(piwikServerUrl);
            this.appUrl = new URL(appUrl);
            log.info(
                "Enabling piwik tracker for site " + gaId + " and server " + piwikServerUrl + " and application url " + appUrl);
        }
    }

    @Override
    public void started(UUID uuid, String event, String type) {
        if (enabled) {
            try {
                callUri(uuid, event + "-started", type);
            } catch (URISyntaxException ex) {
                log.error("Exception in started() method of PiwikTracker", ex);
            }
        }
    }

    @Override
    public void stopped(UUID uuid, String event, String type) {
        if (enabled) {
            try {
                callUri(uuid, event + "-stopped", type);
            } catch (URISyntaxException ex) {
                log.error("Exception in started() method of PiwikTracker", ex);
            }
        }
    }

    @Override
    public void count(UUID uuid, String event, String type) {
        if (enabled) {
            try {
                callUri(uuid, event, type);
            } catch (URISyntaxException ex) {
                log.error("Exception in count() method of PiwikTracker", ex);
            }
        }
    }

    private void callUri(UUID uuid, String event, String type) throws URISyntaxException {
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance().
            uri(this.piwikServerUrl.toURI());
        UriComponents uriComponents = builder.queryParam("rec", 1).
            queryParam("idsite", gaId).
            queryParam("url", this.appUrl.toURI().
                toASCIIString()).
            queryParam("uid", uuid.toString()).
            queryParam("action_name", event).
            queryParam("e_a", type).
            queryParam("send_image", 0).
            build();
        RestTemplate template = new RestTemplate();
        ResponseEntity<Integer> response = template.getForEntity(uriComponents.
            toUri(), Integer.class);
        if (response.getStatusCodeValue() == 204 || response.
            getStatusCodeValue() == 200) {
            log.debug("Piwik call succeeded!");
        } else {
            log.warn(
                "Piwik call to " + uriComponents.toUri() + " did not succeed!");
        }

    }

}
