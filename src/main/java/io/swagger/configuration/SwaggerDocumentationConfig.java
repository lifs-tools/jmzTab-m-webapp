package io.swagger.configuration;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@javax.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen", date = "2018-01-11T19:50:29.849+01:00")

@Configuration
public class SwaggerDocumentationConfig {

    @Bean
    public OpenAPI mzTabOpenApi() {
        return new OpenAPI()
                .info(new Info().title("mzTab validation API.")
                        .description("This is the mzTab validation service.")
                        .version("2.0.0")
                        .license(new License().name("Apache 2.0").url("http://www.apache.org/licenses/LICENSE-2.0.html")))
                .externalDocs(new ExternalDocumentation()
                        .description("Source Code")
                        .url("https://github.com/lifs-tools/jmzTab-m-webapp"))
                .externalDocs(new ExternalDocumentation()
                        .description("mzTab Format documentation")
                        .url("https://github.com/HUPO-PSI/mzTab"))
                .externalDocs(new ExternalDocumentation()
                        .description("Terms of use")
                        .url("https://lifs-tools.org/imprint-privacy-policy.html"))
                .externalDocs(new ExternalDocumentation()
                .description("Contact & Support")
                .url("https://lifs-tools.org/contact"));
    }
    
    @Bean
    GroupedOpenApi mzTabMApiInfo() {
        return GroupedOpenApi.builder()
                .group("mztab-m-api")
                .displayName("mzTab validation and conversion API.")
                .pathsToMatch("/mztabvalidator/rest/v2/**")
                .packagesToScan("org.lifstools.mztab2.server.api")
                .build();
    }

//    @Bean
//    public Docket customImplementation() {
//        return new Docket(DocumentationType.SWAGGER_2)
//                .select()
//                .apis(not(RequestHandlerSelectors.basePackage("org.springframework.boot")))
//                .apis(not(RequestHandlerSelectors.basePackage("org.springframework.cloud")))
//                .apis(not(RequestHandlerSelectors.basePackage("org.springframework.data.rest.webmvc")))
//                .apis(not(RequestHandlerSelectors.basePackage("org.lifstools.mztab.validator.webapp.controller")))
//                .apis(RequestHandlerSelectors.basePackage("org.lifstools.mztab2.server.api"))
//                .build()
//                .directModelSubstitute(java.time.LocalDate.class, java.sql.Date.class)
//                .directModelSubstitute(java.time.OffsetDateTime.class, java.util.Date.class)
//                .apiInfo(apiInfo());
//    }

}
