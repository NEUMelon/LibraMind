package com.libramind.vision;

import com.libramind.common.api.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 校验图片真实格式和像素规模，并为视觉模型生成受限尺寸的输入。 */
@Service
public class ImageService {

    public record ImageData(
            byte[] bytes, String extension, String contentType, BufferedImage image) {}

    public ImageData read(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 10 * 1024 * 1024) {
            throw BusinessException.invalid("请上传不超过10MB的JPEG、PNG或WebP图片");
        }
        try {
            return read(file.getBytes());
        } catch (IOException exception) {
            throw BusinessException.invalid("无法读取图片");
        }
    }

    /** 不相信扩展名和Content-Type，解码前检查宽高，避免异常大图片耗尽内存。 */
    public ImageData read(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) {
            throw BusinessException.invalid("图片大小不符合要求");
        }
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw BusinessException.invalid("文件不是真实的受支持图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.List.of("jpeg", "jpg", "png", "webp").contains(format)) {
                    throw BusinessException.invalid("只支持JPEG、PNG和WebP");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 16 || height < 16 || (long) width * height > 20_000_000L) {
                    throw BusinessException.invalid("图片像素尺寸不符合要求");
                }
                BufferedImage image = reader.read(0);
                String extension = format.equals("jpeg") ? "jpg" : format;
                return new ImageData(
                        bytes,
                        extension,
                        extension.equals("jpg") ? "image/jpeg" : "image/" + extension,
                        image);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw BusinessException.invalid("图片损坏或无法解码");
        }
    }

    /** 缩到最长边1280像素并转JPEG，控制上传费用；原图不默认保存。 */
    public byte[] visionBytes(ImageData data) {
        BufferedImage source = data.image();
        double scale = Math.min(1, 1280.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) (source.getWidth() * scale));
        int height = Math.max(1, (int) (source.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(result, "jpeg", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw BusinessException.invalid("无法处理图片");
        }
    }
}
