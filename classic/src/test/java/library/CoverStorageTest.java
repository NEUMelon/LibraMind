package library;

import library.service.CoverStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证按数据库相对路径读取封面，路径不能越出本地目录。 */
class CoverStorageTest {
    @TempDir Path directory;

    @Test
    void importedIsbnIsExtractedButUnknownEditionIsNotGuessed() {
        assertEquals("9787115251121", CoverStorage.isbnFor("CN-9787115251121"));
        assertEquals("", CoverStorage.isbnFor("BK-0001"));
        assertEquals("", CoverStorage.isbnFor(null));
    }

    @Test
    void arbitraryFilePathsAreRejected() {
        CoverStorage storage = new CoverStorage(directory);
        assertEquals("", CoverStorage.isbnFor("CN-9787115251122"));
        for (String input :
                new String[] {
                    "../db.properties",
                    "9787115251121/../secret",
                    "/absolute.jpg",
                    "C:\\secret.jpg",
                    "file.svg",
                    "https://example.com/image.jpg",
                    ""
                }) assertTrue(storage.find(input).isEmpty());
    }

    @Test
    void missingOrEmptyCoverUsesPlaceholder() throws Exception {
        CoverStorage storage = new CoverStorage(directory);
        assertTrue(storage.find("9787115251121.jpg").isEmpty());
        Files.createFile(directory.resolve("9787115251121.jpg"));
        assertTrue(storage.find("9787115251121.jpg").isEmpty());
    }

    @Test
    void exactLocalFileIsFoundWithoutNetworkLookup() throws Exception {
        Path expected = directory.resolve("9787115251121.jpg");
        Files.write(expected, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        assertEquals(expected, new CoverStorage(directory).find("9787115251121.jpg").orElseThrow());
        assertTrue(new CoverStorage(directory).find("9787115249494.jpg").isEmpty());
    }

    @Test
    void pathDoesNotNeedToBeAnIsbn() throws Exception {
        Path cover = directory.resolve("shared-cover.jpg");
        Files.write(cover, new byte[] {1, 2, 3});
        assertTrue(CoverStorage.isValidPath("shared-cover.jpg"));
        assertEquals(cover, new CoverStorage(directory).find("shared-cover.jpg").orElseThrow());
    }
}
