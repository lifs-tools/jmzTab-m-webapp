package org.lifstools.mztab.validator.webapp.service.validation;

/** CV mapping documents shared by the mapping-file tests. */
public final class MappingFixtures {

    private MappingFixtures() {
    }

    public static final String MINIMAL_MAPPING = """
        <?xml version="1.0" encoding="UTF-8"?>
        <CvMapping modelName="test" modelURI="https://example.org/model" modelVersion="2.0.0">
          <CvReferenceList>
            <CvReference cvName="MS" cvIdentifier="MS"/>
          </CvReferenceList>
          <CvMappingRuleList>
          </CvMappingRuleList>
        </CvMapping>
        """;

    /** Same document, but declaring a DOCTYPE with a harmless internal entity. */
    public static final String MAPPING_WITH_DOCTYPE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE CvMapping [ <!ENTITY name "test"> ]>
        <CvMapping modelName="&name;" modelURI="https://example.org/model" modelVersion="2.0.0">
          <CvReferenceList>
            <CvReference cvName="MS" cvIdentifier="MS"/>
          </CvReferenceList>
          <CvMappingRuleList>
          </CvMappingRuleList>
        </CvMapping>
        """;
}
