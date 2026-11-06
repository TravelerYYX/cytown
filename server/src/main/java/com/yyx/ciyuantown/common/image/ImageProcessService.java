package com.yyx.ciyuantown.common.image;

import com.yyx.ciyuantown.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class ImageProcessService {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    @Value("${ciyuantown.upload-dir:./uploads}") private String uploadDir;
    @org.springframework.beans.factory.annotation.Autowired private com.yyx.ciyuantown.common.storage.StorageService storage;

    public String process(MultipartFile file, String format, Integer quality) {
        return processDetailed(file,format,quality).url();
    }
    public record ProcessedImage(String url,long originalBytes,long processedBytes) {}
    public ProcessedImage processDetailed(MultipartFile file, String format, Integer quality) {
        if (file == null || file.isEmpty()) throw new BizException("上传文件不能为空");
        if (file.getSize() > 10L * 1024 * 1024) throw new BizException("图片大小不能超过 10MB");
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/") || !EXTENSIONS.contains(ext))
            throw new BizException("仅支持 jpg/jpeg/png/webp 格式的图片");
        String output = format == null || format.isBlank() ? "webp" : format.toLowerCase(Locale.ROOT);
        if (!Set.of("webp", "jpg", "png").contains(output)) throw new BizException("输出格式仅支持 webp/jpg/png");
        float compression = Math.max(1, Math.min(100, quality == null ? 75 : quality)) / 100f;
        Path dir = Paths.get(uploadDir, "config").toAbsolutePath().normalize();
        Path target = dir.resolve(UUID.randomUUID() + "." + output).normalize();
        try {
            BufferedImage decoded = ImageIO.read(file.getInputStream());
            if (decoded == null) throw new BizException("图片无法解码");
            BufferedImage image = "jpg".equals(output) ? flatten(decoded) : decoded;
            Files.createDirectories(dir);
            write(image, output, compression, target);
            long processedBytes=Files.size(target);
            if(storage==null) return new ProcessedImage("/uploads/config/" + target.getFileName(),file.getSize(),processedBytes);
            try {return new ProcessedImage(storage.put(storage.active(), "config/"+target.getFileName(),target,com.yyx.ciyuantown.common.storage.StorageService.mime(target)).url(),file.getSize(),processedBytes);} finally {delete(target);}
        } catch (BizException e) {
            delete(target); throw e;
        } catch (Exception e) {
            delete(target); throw new BizException("图片处理失败，请更换图片后重试");
        }
    }

    private static BufferedImage flatten(BufferedImage source) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
        graphics.drawImage(source, 0, 0, null); graphics.dispose();
        return target;
    }

    private static void write(BufferedImage image, String format, float quality, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) throw new BizException("当前环境不支持 " + format + " 编码");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (!"png".equals(format) && params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] types = params.getCompressionTypes();
                if (types != null && types.length > 0) params.setCompressionType(types[0]);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally { writer.dispose(); }
    }

    private static void delete(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }
}
