package com.libramind.vision;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.HybridBinarizer;
import com.libramind.auth.service.ActorService;
import com.libramind.catalog.CatalogDAO;
import com.libramind.catalog.CatalogDtos.*;
import com.libramind.catalog.CatalogService;
import com.libramind.catalog.Isbn;
import com.libramind.common.api.ApiResponse;
import com.libramind.common.api.BusinessException;
import com.libramind.common.db.SqlStore;
import com.libramind.common.web.RequestIdFilter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** ISBN和馆藏条码识别、标签生成；不调用任何大模型。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "条码")
public class BarcodeController {

    public record BarcodeResult(
            long recognitionId,
            String format,
            String value,
            boolean found,
            BookView book,
            CopyView copy) {}

    public record Label(CopyView copy, String barcodeUrl) {}

    public record LabelInput(List<Long> copyIds) {}

    private final ImageService images;
    private final ActorService actors;
    private final CatalogDAO catalog;
    private final CatalogService service;
    private final SqlStore sql;
    private final ObjectMapper json;

    public BarcodeController(
            ImageService images,
            ActorService actors,
            CatalogDAO catalog,
            CatalogService service,
            SqlStore sql,
            ObjectMapper json) {
        this.images = images;
        this.actors = actors;
        this.catalog = catalog;
        this.service = service;
        this.sql = sql;
        this.json = json;
    }

    @PostMapping(value = "/vision/barcode", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "识别ISBN或馆藏Code128图片（需登录，不调用模型）")
    public ApiResponse<BarcodeResult> decode(
            @RequestPart MultipartFile file, HttpServletRequest request) {
        var actor = actors.current();
        var image = images.read(file);
        long start = System.nanoTime();
        try {
            var bitmap =
                    new BinaryBitmap(
                            new HybridBinarizer(new BufferedImageLuminanceSource(image.image())));
            var result =
                    new MultiFormatReader()
                            .decode(
                                    bitmap,
                                    Map.of(
                                            DecodeHintType.TRY_HARDER,
                                            true,
                                            DecodeHintType.POSSIBLE_FORMATS,
                                            List.of(BarcodeFormat.EAN_13, BarcodeFormat.CODE_128)));
            String value = result.getText();
            BookView book = null;
            CopyView copy = null;
            boolean isbn = result.getBarcodeFormat() == BarcodeFormat.EAN_13;
            if (isbn) {
                value = Isbn.normalize(value);
                book = catalog.findIsbn(value);
            } else {
                copy = catalog.findAccession(value);
                if (copy != null) {
                    book = catalog.findBook(copy.editionId());
                }
            }
            long id =
                    sql.insert(
                            """
INSERT INTO recognition_log(user_id,type,extracted_fields,outcome,latency_ms,model)
VALUES(?,?,?,?,?,'ZXing')
""",
                            actor.id(),
                            isbn ? "ISBN" : "ACCESSION",
                            json.writeValueAsString(Map.of("value", value)),
                            book == null ? "NOT_FOUND" : "FOUND",
                            (System.nanoTime() - start) / 1_000_000);
            return ApiResponse.ok(
                    new BarcodeResult(
                            id, result.getBarcodeFormat().name(), value, book != null, book, copy),
                    RequestIdFilter.id(request));
        } catch (com.google.zxing.NotFoundException exception) {
            throw BusinessException.invalid("没有识别到清晰的ISBN或馆藏条码，请重新拍照或手动输入");
        }
    }

    @GetMapping(value = "/copies/{id}/barcode", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "生成单册Code128标签图片（公开）")
    public ResponseEntity<byte[]> barcode(@PathVariable long id) throws Exception {
        CopyView copy = service.copy(id);
        var matrix =
                new MultiFormatWriter()
                        .encode(
                                copy.accessionNo(),
                                BarcodeFormat.CODE_128,
                                520,
                                120,
                                Map.of(EncodeHintType.MARGIN, 15));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(output.toByteArray());
    }

    @PostMapping("/copies/labels")
    @Operation(summary = "准备批量打印标签（管理员，返回书号与图片地址）")
    public ApiResponse<List<Label>> labels(
            @RequestBody LabelInput input, HttpServletRequest request) {
        actors.admin();
        if (input.copyIds() == null || input.copyIds().isEmpty() || input.copyIds().size() > 100) {
            throw BusinessException.invalid("每次打印1到100个标签");
        }
        return ApiResponse.ok(
                input.copyIds().stream()
                        .map(id -> new Label(service.copy(id), "/api/v1/copies/" + id + "/barcode"))
                        .toList(),
                RequestIdFilter.id(request));
    }
}
