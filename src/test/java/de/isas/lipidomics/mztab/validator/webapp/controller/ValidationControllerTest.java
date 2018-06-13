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
package de.isas.lipidomics.mztab.validator.webapp.controller;

import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.service.storage.StorageFileNotFoundException;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.fileUpload;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 *
 * @author nilshoffmann
 */
@RunWith(SpringRunner.class)
@AutoConfigureMockMvc
@SpringBootTest
public class ValidationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private StorageService storageService;

//    @Test
//    public void shouldListAllFiles() throws Exception {
//        UUID sessionUUID = UUID.randomUUID();
//        given(this.storageService.loadAll(sessionUUID))
//                .willReturn(Stream.of(Paths.get("first.txt"), Paths.get("second.txt")));
//
//        this.mvc.perform(get("/")).andExpect(status().isOk())
//                .andExpect(model().attribute("files",
//                        Matchers.contains("http://localhost/files/first.txt",
//                                "http://localhost/files/second.txt")));
//    }
//
//    @Test
//    public void shouldSaveUploadedFile() throws Exception {
//        MockMultipartFile multipartFile = new MockMultipartFile("file", "test.txt",
//                "text/plain", "Spring Framework".getBytes());
//        UUID sessionUuid = UUID.randomUUID();
//        this.mvc.perform(fileUpload("/").file(multipartFile))
//                .andExpect(status().isOk());
//        then(this.storageService).should().store(multipartFile, sessionUuid);
//    }
//
//    @SuppressWarnings("unchecked")
//    @Test
//    public void should404WhenMissingFile() throws Exception {
//        UUID sessionUuid = UUID.randomUUID();
//        UserSessionFile usf = new UserSessionFile("test.txt", sessionUuid);
//        given(this.storageService.loadAsResource(usf))
//                .willThrow(StorageFileNotFoundException.class);
//
//        this.mvc.perform(get("/validate/"+sessionUuid)).andExpect(status().isNotFound());
//    }


    /**
     * Test of listUploadedFiles method, of class ValidationController.
     */
    @Test
    public void testListUploadedFiles() throws Exception {
    }

    /**
     * Test of handleFileUpload method, of class ValidationController.
     */
    @Test
    public void testHandleFileUpload() {
    }

    /**
     * Test of validateFile method, of class ValidationController.
     */
    @Test
    public void testValidateFile() {
    }

    /**
     * Test of handleAbout method, of class ValidationController.
     */
    @Test
    public void testHandleAbout() {
    }

    /**
     * Test of deleteResults method, of class ValidationController.
     */
    @Test
    public void testDeleteResults() {
    }

    /**
     * Test of handleStorageFileNotFound method, of class ValidationController.
     */
    @Test
    public void testHandleStorageFileNotFound() {
    }

    /**
     * Test of handleMultipartError method, of class ValidationController.
     */
    @Test
    public void testHandleMultipartError() throws Exception {
    }

    /**
     * Test of handleError method, of class ValidationController.
     */
    @Test
    public void testHandleError() throws Exception {
    }

    /**
     * Test of handleUnmapped method, of class ValidationController.
     */
    @Test
    public void testHandleUnmapped() {
    }
    
}
