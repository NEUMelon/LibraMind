package com.libramind.vision;

import static org.assertj.core.api.Assertions.*;

import com.libramind.common.api.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

class ImageServiceTest {
    private final ImageService images = new ImageService();

    @Test
    void extensionAndMimeCannotDisguiseNonImage() {
        var file =
                new MockMultipartFile(
                        "file", "cover.jpg", "image/jpeg", "<script>evil</script>".getBytes());
        assertThatThrownBy(() -> images.read(file)).isInstanceOf(BusinessException.class);
    }

    @Test
    void actualPngIsRecognizedAndResizedForVision() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2200, 1200, BufferedImage.TYPE_INT_RGB), "png", output);
        var file = new MockMultipartFile("file", "wrong.jpg", "text/plain", output.toByteArray());
        var image = images.read(file);
        assertThat(image.extension()).isEqualTo("png");
        var resized = ImageIO.read(new java.io.ByteArrayInputStream(images.visionBytes(image)));
        assertThat(resized.getWidth()).isLessThanOrEqualTo(1280);
    }

    @Test
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> images.read(new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(BusinessException.class);
    }
}
