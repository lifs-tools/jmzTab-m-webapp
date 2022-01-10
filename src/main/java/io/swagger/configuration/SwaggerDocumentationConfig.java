package io.swagger.configuration;

import static java.util.function.Predicate.not;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.RequestHandler;

import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

@javax.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen", date = "2018-01-11T19:50:29.849+01:00")

@Configuration
public class SwaggerDocumentationConfig {

    ApiInfo apiInfo() {
        return new ApiInfoBuilder()
            .title("mzTab validation API.")
            .description("This is the mzTab validation service.")
            .license("Apache 2.0")
            .licenseUrl("http://www.apache.org/licenses/LICENSE-2.0.html")
            .termsOfServiceUrl("https://lifs.isas.de/imprint-privacy-policy.html")
            .version("2.0.0")
            .contact(new Contact("LIFS-Tools jmzTab-m-webapp","https://github.com/lifs-tools/jmzTab-m-webapp", "lifs-support@isas.de"))
            .build();
    }

    @Bean
    public Docket customImplementation(){
        return new Docket(DocumentationType.SWAGGER_2)
                .select()
                    .apis(not(RequestHandlerSelectors.basePackage("org.springframework.boot")))
                    .apis(not(RequestHandlerSelectors.basePackage("org.springframework.cloud")))
                    .apis(not(RequestHandlerSelectors.basePackage("org.springframework.data.rest.webmvc")))
                    .apis(not(RequestHandlerSelectors.basePackage("org.lifstools.mztab.validator.webapp.controller")))
                    .apis(RequestHandlerSelectors.basePackage("de.isas.mztab2.server.api"))
                    .build()
                .directModelSubstitute(java.time.LocalDate.class, java.sql.Date.class)
                .directModelSubstitute(java.time.OffsetDateTime.class, java.util.Date.class)
                .apiInfo(apiInfo());
    }

}
