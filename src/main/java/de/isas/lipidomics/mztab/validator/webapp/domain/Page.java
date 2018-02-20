/*
 * Copyright 2017 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package de.isas.lipidomics.mztab.validator.webapp.domain;

import java.util.Objects;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public class Page {

    private String title;
    private String appVersion;
    private String gaId;
    private String jmztabVersion;
    private String jmztabmVersion;

    public Page(String title, String appVersion, String gaId,
        String jmztabVersion, String jmztabmVersion) {
        this.title = title;
        this.appVersion = appVersion;
        this.gaId = gaId;
        this.jmztabVersion = jmztabVersion;
        this.jmztabmVersion = jmztabmVersion;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public void setGaId(String gaId) {
        this.gaId = gaId;
    }

    public String getGaId() {
        return this.gaId;
    }

    public String getJmztabVersion() {
        return jmztabVersion;
    }

    public void setJmztabVersion(String jmztabVersion) {
        this.jmztabVersion = jmztabVersion;
    }

    public String getJmztabmVersion() {
        return jmztabmVersion;
    }

    public void setJmztabmVersion(String jmztabmVersion) {
        this.jmztabmVersion = jmztabmVersion;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 41 * hash + Objects.hashCode(this.title);
        hash = 41 * hash + Objects.hashCode(this.appVersion);
        hash = 41 * hash + Objects.hashCode(this.gaId);
        hash = 41 * hash + Objects.hashCode(this.jmztabVersion);
        hash = 41 * hash + Objects.hashCode(this.jmztabmVersion);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Page other = (Page) obj;
        if (!Objects.equals(this.title, other.title)) {
            return false;
        }
        if (!Objects.equals(this.appVersion, other.appVersion)) {
            return false;
        }
        if (!Objects.equals(this.gaId, other.gaId)) {
            return false;
        }
        if (!Objects.equals(this.jmztabVersion, other.jmztabVersion)) {
            return false;
        }
        if (!Objects.equals(this.jmztabmVersion, other.jmztabmVersion)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "Page{" + "title=" + title + ", appVersion=" + appVersion + ", gaId=" + gaId + ", jmztabVersion=" + jmztabVersion + ", jmztabmVersion=" + jmztabmVersion + '}';
    }

}
