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
package org.lifstools.mztab.validator.webapp;

import org.lifstools.mztab.validator.webapp.domain.AppInfo;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import java.util.concurrent.Executor;
import org.lifstools.mztab2.io.serialization.ParameterConverter;
import org.lifstools.mztab2.server.api.ConversionSlotInterceptor;
import org.lifstools.mztab2.server.config.LocalDateConverter;
import org.lifstools.mztab2.server.config.LocalDateTimeConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.format.FormatterRegistry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;
import uk.ac.ebi.pride.utilities.ols.web.service.config.OLSWsConfig;

@Configuration
@EnableAsync
@PropertySource(name = "exampleProps", value = "classpath:examples.properties", ignoreResourceNotFound = false)
public class WebConfig implements WebMvcConfigurer {

    public WebConfig() {
        super();
    }

    @Bean
    public AppInfo appInfo() {
        return new AppInfo();
    }

    @Bean(name = "toolThreadPoolTaskExecutor")
    public Executor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor tpe = new ThreadPoolTaskExecutor();
        tpe.setCorePoolSize(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        return tpe;
    }

    @Autowired
    private ConversionSlotInterceptor conversionSlots;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(conversionSlots).addPathPatterns("/rest/v2/convert", "/rest/v2/convertPlain");
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new LocalDateConverter("yyyy-MM-dd"));
        registry.addConverter(new LocalDateTimeConverter("yyyy-MM-dd'T'HH:mm:ss.SSS"));
    }

    @Bean
    public OLSClient olsClient() {
        OLSWsConfig config = new OLSWsConfig();
        return new OLSClient(config);
    }

    @Bean
    public CvParameterLookupService cvParameterLookupService(@Autowired OLSClient client) {
        return new CvParameterLookupService(client);
    }

    @Bean
    public ParameterConverter parameterConverter() {
        return new ParameterConverter();
    }
}
