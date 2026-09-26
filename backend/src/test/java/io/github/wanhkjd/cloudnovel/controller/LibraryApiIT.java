package io.github.wanhkjd.cloudnovel.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wanhkjd.cloudnovel.support.DatabaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class LibraryApiIT extends DatabaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    byte[] png() {
        return new byte[] {
            (byte) 0x89,
            'P',
            'N',
            'G',
            (byte) 0x0D,
            (byte) 0x0A,
            (byte) 0x1A,
            (byte) 0x0A,
            0,
            0,
            0,
            0
        };
    }

    String upload() throws Exception {
        var file =
                new MockMultipartFile(
                        "file",
                        "原创测试.txt",
                        "text/plain",
                        ("作者：测试作者\n第一卷 测试\n第0001章 信\n原创正文 "
                                        + UUID.randomUUID()
                                        + "\n第二段文字。\n第0002章 回信\n第二章正文。")
                                .getBytes(StandardCharsets.UTF_8));
        var response =
                mvc.perform(
                                multipart("/api/books")
                                        .file(file)
                                        .with(user("admin").roles("ADMIN"))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.chapterCount").value(2))
                        .andExpect(jsonPath("$.catalogPublished").value(false))
                        .andExpect(jsonPath("$.sha256").doesNotExist())
                        .andExpect(jsonPath("$.storagePath").doesNotExist())
                        .andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("id").asText();
    }

    void publish(String id, boolean text) throws Exception {
        mvc.perform(
                        patch("/api/books/" + id)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "title",
                                                        "原创测试",
                                                        "author",
                                                        "测试作者",
                                                        "description",
                                                        "由测试创建",
                                                        "catalogPublished",
                                                        true,
                                                        "textPublished",
                                                        text))))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCanImportPreviewAndExplicitlyPublishWithoutLeakingPrivateText() throws Exception {
        String id = upload();
        mvc.perform(get("/api/books/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/books/" + id + "/chapters/0")).andExpect(status().isNotFound());
        mvc.perform(get("/api/books/" + id + "/chapters/0").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paragraphs[0]")
                                .value(org.hamcrest.Matchers.startsWith("原创正文")));
        publish(id, false);
        mvc.perform(get("/api/books/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canRead").value(false))
                .andExpect(jsonPath("$.preface").value(""));
        mvc.perform(get("/api/books/" + id + "/chapters/0")).andExpect(status().isNotFound());
        mvc.perform(get("/api/books/" + id + "/download")).andExpect(status().isNotFound());
        publish(id, true);
        mvc.perform(get("/api/books/" + id + "/chapters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paragraphs[0]").value("第二章正文。"));
        mvc.perform(get("/api/books/" + id + "/download"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Content-Type",
                                        org.hamcrest.Matchers.startsWith("text/plain")));
    }

    @Test
    void ownerReadingProgressAndTimeArePrivateAndSessionRetriesAreIdempotent() throws Exception {
        String book = upload();
        mvc.perform(
                        put("/api/me/progress/" + book)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"chapterIndex\":1,\"paragraphIndex\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(1));
        mvc.perform(get("/api/me/progress/" + book).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paragraphIndex").value(0));
        mvc.perform(get("/api/me/progress/" + book)).andExpect(status().isUnauthorized());
        String session = UUID.randomUUID().toString();
        long started = System.currentTimeMillis() - 120_000;
        for (int seconds : new int[] {60, 60, 90, 30}) {
            mvc.perform(
                            put("/api/me/sessions/" + session)
                                    .with(user("admin").roles("ADMIN"))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "bookId",
                                                            book,
                                                            "chapterIndex",
                                                            1,
                                                            "paragraphIndex",
                                                            0,
                                                            "elapsedSeconds",
                                                            seconds,
                                                            "startedAt",
                                                            started))))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/me/stats").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeconds").value(90))
                .andExpect(jsonPath("$.books[0].seconds").value(90));
        mvc.perform(get("/api/me/history").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].elapsedSeconds").value(90));
        mvc.perform(
                        put("/api/me/progress/" + book)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"chapterIndex\":0,\"paragraphIndex\":9999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void bookmarksArePrivateUntilExplicitPublicationAndCanBeEditedAndDeleted() throws Exception {
        String book = upload();
        publish(book, true);
        var created =
                mvc.perform(
                                post("/api/me/bookmarks")
                                        .with(user("admin").roles("ADMIN"))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "bookId",
                                                                book,
                                                                "chapterIndex",
                                                                0,
                                                                "paragraphIndex",
                                                                1,
                                                                "note",
                                                                "只给自己看的感想"))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.published").value(false))
                        .andReturn();
        String bookmark =
                json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get("/api/books/" + book + "/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(
                        patch("/api/me/bookmarks/" + bookmark)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"note\":\"分享一份阅读感想\",\"published\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/books/" + book + "/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].note").value("分享一份阅读感想"));
        publish(book, false);
        mvc.perform(get("/api/books/" + book + "/bookmarks")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/bookmarks/" + bookmark).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        delete("/api/me/bookmarks/" + bookmark)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/bookmarks").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanIsolatedLibrary() throws Exception {
        var response =
                mvc.perform(get("/api/books").with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn();
        for (var book : json.readTree(response.getResponse().getContentAsString()))
            mvc.perform(
                            delete("/api/books/" + book.path("id").asText())
                                    .with(user("admin").roles("ADMIN"))
                                    .with(csrf()))
                    .andExpect(status().isNoContent());
    }

    @Test
    void mutationsRequireOwnerAndInvalidOrDuplicateFilesDoNotCreateBooks() throws Exception {
        var file =
                new MockMultipartFile(
                        "file",
                        "test.txt",
                        "text/plain",
                        ("第一章 测试\n原创测试 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/books").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/books").file(file).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/books")
                                .file(file)
                                .with(user("visitor").roles("USER"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/books")
                                .file(file)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isCreated());
        mvc.perform(
                        multipart("/api/books")
                                .file(file)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(
                        multipart("/api/books")
                                .file(
                                        new MockMultipartFile(
                                                "file", "bad.epub", "text/plain", new byte[] {65}))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/api/books")
                                .file(
                                        new MockMultipartFile(
                                                "file", "blank.txt", "text/plain", new byte[] {}))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/books").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/books")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/not-a-real-endpoint")).andExpect(status().isNotFound());
    }

    @Test
    void invalidReadingDataAndUnpublishedCatalogCannotExposeText() throws Exception {
        String book = upload();
        mvc.perform(
                        patch("/api/books/" + book)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"title\":\"test\",\"author\":\"author\",\"catalogPublished\":false,\"textPublished\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/books/" + book + "/chapters")).andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/me/bookmarks")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "bookId",
                                                        book,
                                                        "chapterIndex",
                                                        0,
                                                        "paragraphIndex",
                                                        10000,
                                                        "note",
                                                        "invalid"))))
                .andExpect(status().isBadRequest());
        for (int seconds : new int[] {-1, 1801})
            mvc.perform(
                            put("/api/me/sessions/" + UUID.randomUUID())
                                    .with(user("admin").roles("ADMIN"))
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "bookId",
                                                            book,
                                                            "chapterIndex",
                                                            0,
                                                            "paragraphIndex",
                                                            0,
                                                            "startedAt",
                                                            System.currentTimeMillis() - 120000,
                                                            "elapsedSeconds",
                                                            seconds))))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        put("/api/me/sessions/" + UUID.randomUUID())
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "bookId",
                                                        book,
                                                        "chapterIndex",
                                                        0,
                                                        "paragraphIndex",
                                                        0,
                                                        "startedAt",
                                                        System.currentTimeMillis() + 86400000,
                                                        "elapsedSeconds",
                                                        60))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/stats").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.totalSeconds").value(0));
    }

    @Test
    void removingBookAlsoRemovesItsPrivateJournalAndOriginalDownload() throws Exception {
        String book = upload();
        mvc.perform(
                        put("/api/me/progress/" + book)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"chapterIndex\":0,\"paragraphIndex\":0}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/me/bookmarks")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "bookId",
                                                        book,
                                                        "chapterIndex",
                                                        0,
                                                        "paragraphIndex",
                                                        0,
                                                        "note",
                                                        "private"))))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/books/" + book).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/progress").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/me/bookmarks").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/books/" + book + "/download").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedChapterIndexIsABadRequestWithoutInternalDetails() throws Exception {
        mvc.perform(get("/api/books/" + UUID.randomUUID() + "/chapters/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void malformedJsonAndMissingMultipartFileReturnStructuredClientErrors() throws Exception {
        mvc.perform(
                        patch("/api/books/" + UUID.randomUUID())
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(multipart("/api/books").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(get("/api/this-route-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void unreadBookProgressRemainsAnEmptySuccessfulResponse() throws Exception {
        String id = upload();
        mvc.perform(get("/api/me/progress/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void ownerUploadsCoverThenBookReportsHasCoverWithoutExposingPath() throws Exception {
        String id = upload();
        publish(id, false);
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true))
                .andExpect(jsonPath("$.coverPath").doesNotExist());
        mvc.perform(get("/api/books/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true))
                .andExpect(jsonPath("$.coverPath").doesNotExist());
    }

    @Test
    void coverUploadRejectsNonImageOversizeAndUnauthorizedCallers() throws Exception {
        String id = upload();
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("visitor").roles("USER"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "x.png",
                                                "image/png",
                                                "<svg xmlns=\"a\"></svg>"
                                                        .getBytes(StandardCharsets.UTF_8)))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        byte[] oversize = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, oversize, 0, 8);
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(
                                        new MockMultipartFile(
                                                "file", "big.png", "image/png", oversize))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerRemovesCoverAndSubsequentReadsFallBackIdempotently() throws Exception {
        String id = upload();
        publish(id, false);
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(
                        delete("/api/books/" + id + "/cover")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.hasCover").value(false));
        mvc.perform(get("/api/books/" + id + "/cover")).andExpect(status().isNotFound());
        mvc.perform(
                        delete("/api/books/" + id + "/cover")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void coverGetIsAlways404WithNosniffWhenUnavailableAndStreamsWhenVisible() throws Exception {
        mvc.perform(get("/api/books/" + UUID.randomUUID() + "/cover"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/books/not-a-uuid/cover"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        String id = upload();
        mvc.perform(get("/api/books/" + id + "/cover").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/books/" + id + "/cover"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(get("/api/books/" + id + "/cover").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "public, max-age=300"));

        publish(id, false);
        mvc.perform(get("/api/books/" + id + "/cover"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }
}
