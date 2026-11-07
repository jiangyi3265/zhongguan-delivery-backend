package com.ruoyi.framework.web.service;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.framework.config.ResourcesConfig;
import com.ruoyi.framework.interceptor.RepeatSubmitInterceptor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real file upload utility and Spring resource handler; no server or browser. */
class UploadContentSecurityTest
{
    @TempDir Path directory;
    private String previousProfile;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private static final byte[] HTML = "<html><script>document.title='synthetic-only'</script></html>".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void setup() throws Exception
    {
        previousProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(directory.toString());
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfiguration.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach void cleanup()
    {
        if (context != null) context.close();
        new RuoYiConfig().setProfile(previousProfile);
    }

    @Configuration @EnableWebMvc
    static class WebConfiguration
    {
        @Bean RepeatSubmitInterceptor repeatSubmitInterceptor() throws Exception
        {
            RepeatSubmitInterceptor repeat = mock(RepeatSubmitInterceptor.class);
            when(repeat.preHandle(any(), any(), any())).thenReturn(true);
            return repeat;
        }
        @Bean ResourcesConfig resources() { return new ResourcesConfig(); }
    }

    private String upload(String name, String mime, byte[] bytes) throws Exception
    {
        return FileUploadUtils.upload(directory.resolve("upload").toString(), new MockMultipartFile("file", name, mime, bytes));
    }

    @Test void htmlAndHtmAreRejectedBeforeAnyFileIsWritten() throws Exception
    {
        assertThrows(IOException.class, () -> upload("active.html", "text/html", HTML));
        assertThrows(IOException.class, () -> upload("active.HTM", "image/png", HTML));
        assertEquals(0, Files.walk(directory).filter(Files::isRegularFile).count());
    }

    @Test void svgIsNotAcceptedAsAnImageOrDocument() throws Exception
    {
        assertThrows(IOException.class, () -> upload("active.svg", "image/svg+xml", "<svg onload='x()'/>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, Files.walk(directory).filter(Files::isRegularFile).count());
    }

    @Test void claimedImageMimeAndExtensionCannotHideHtmlOrTruncatedData() throws Exception
    {
        assertThrows(IOException.class, () -> upload("fake.png", "image/png", HTML));
        assertThrows(IOException.class, () -> upload("truncated.png", "image/png", new byte[] {(byte)137,80,78,71,13,10,26,10}));
        assertEquals(0, Files.walk(directory).filter(Files::isRegularFile).count());
    }

    @Test void actualImageFormatMustMatchServedExtension() throws Exception
    {
        assertThrows(IOException.class, () -> upload("wrong.jpg", "image/jpeg", png()));
        assertEquals(0, Files.walk(directory).filter(Files::isRegularFile).count());
    }

    @Test void realPngStillUploadsAndLoadsInline() throws Exception
    {
        byte[] image = png();
        String path = upload("valid.png", "image/png", image);
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(image))
                .andExpect(content().contentType("image/png"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void existingHtmlAndSvgRemainDownloadableWithoutInlineExecution() throws Exception
    {
        Files.write(directory.resolve("old.html"), HTML);
        Files.write(directory.resolve("old.svg"), "<svg/>".getBytes(StandardCharsets.UTF_8));
        for (String name : new String[] {"old.html", "old.svg"})
            mvc.perform(get("/profile/" + name)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment"))
                    .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void documentUploadRemainsAvailableAsAttachment() throws Exception
    {
        byte[] bytes = "%PDF-1.4\nsynthetic document".getBytes(StandardCharsets.UTF_8);
        String path = upload("document.pdf", "application/pdf", bytes);
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", "attachment"));
    }

    @Test void mixedBatchValidatesAllFilesBeforeWritingAnyFile() throws Exception
    {
        MockMultipartFile valid = new MockMultipartFile("files", "valid.png", "image/png", png());
        MockMultipartFile invalid = new MockMultipartFile("files", "active.html", "text/html", HTML);
        assertThrows(com.ruoyi.common.exception.file.InvalidExtensionException.class,
                () -> FileUploadUtils.uploadAll(directory.resolve("upload").toString(), java.util.Arrays.asList(valid, invalid)));
        try (java.util.stream.Stream<Path> paths = Files.walk(directory))
        {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test void secondWriteFailureRemovesOnlyThisBatchIncludingPartialFile() throws Exception
    {
        Path existing = directory.resolve("existing.txt");
        Files.write(existing, "keep-existing".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile valid = new MockMultipartFile("files", "valid.png", "image/png", png());
        MockMultipartFile broken = new MockMultipartFile("files", "broken.pdf", "application/pdf", "%PDF-1.4 synthetic".getBytes(StandardCharsets.UTF_8))
        {
            @Override public void transferTo(Path target) throws IOException
            {
                Files.write(target, "partial".getBytes(StandardCharsets.UTF_8));
                throw new IOException("synthetic write failure");
            }
        };
        assertThrows(IOException.class, () -> FileUploadUtils.uploadAll(directory.resolve("upload").toString(), java.util.Arrays.asList(valid, broken)));
        assertEquals("keep-existing", new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));
        try (java.util.stream.Stream<Path> paths = Files.walk(directory))
        {
            assertEquals(1, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test void emptyBatchAndEmptyFileCannotReturnSuccess()
    {
        assertThrows(IOException.class, () -> FileUploadUtils.uploadAll(directory.resolve("upload").toString(), java.util.Collections.emptyList()));
        assertThrows(IOException.class, () -> FileUploadUtils.uploadAll(directory.resolve("upload").toString(), java.util.Collections.singletonList(new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]))));
    }

    private byte[] png() throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
