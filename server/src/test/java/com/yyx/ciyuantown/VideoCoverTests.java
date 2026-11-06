package com.yyx.ciyuantown;

import com.yyx.ciyuantown.business.publish.service.FfmpegService;
import com.yyx.ciyuantown.common.storage.MediaSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class VideoCoverTests {
    @TempDir Path root;
    FfmpegService ffmpeg;

    @BeforeEach void setup() throws Exception {
        var config = new Properties();
        if (Files.isRegularFile(Path.of(".env"))) try (var reader = Files.newBufferedReader(Path.of(".env"))) { config.load(reader); }
        ffmpeg = new FfmpegService();
        ReflectionTestUtils.setField(ffmpeg, "ffmpeg", System.getenv().getOrDefault("FFMPEG_PATH", config.getProperty("FFMPEG_PATH", "")));
        ReflectionTestUtils.setField(ffmpeg, "ffprobe", System.getenv().getOrDefault("FFPROBE_PATH", config.getProperty("FFPROBE_PATH", "")));
        var settings = mock(MediaSettingsService.class);
        var defaults = MediaSettingsService.defaults();
        when(settings.get()).thenReturn(new MediaSettingsService.Settings(new MediaSettingsService.Images(256, 90, 512, 90, 6, defaults.images().cacheMaxBytes()), defaults.videos()));
        ReflectionTestUtils.setField(ffmpeg, "settings", settings);
        ffmpeg.check();
        assumeTrue(ffmpeg.isAvailable(), "FFmpeg/FFprobe must be configured");
    }

    @Test void automaticAndSelectedCoversKeepFullFrameAndNeverUpscale() throws Exception {
        for (String size : List.of("720x960", "720x1280", "1280x720", "160x120")) {
            var input = root.resolve(size + ".mp4");
            ffmpeg.command(List.of("-y", "-f", "lavfi", "-i", "color=c=blue:size=" + size + ":rate=2",
                    "-vf", "drawbox=x=0:y=0:w=iw:h=ih/4:color=red:t=fill,drawbox=x=0:y=3*ih/4:w=iw:h=ih/4:color=lime:t=fill",
                    "-t", "1", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", input.toString()), 30, "fixture failed");
            var info = ffmpeg.probe(input);
            for (boolean automatic : List.of(true, false)) {
                var cover = root.resolve(size + "-" + automatic + ".webp");
                if (automatic) ffmpeg.automaticCover(input, cover, info);
                else ffmpeg.cover(input, cover, 200, new FfmpegService.Crop(0, 0, 1, 1), info);
                var image = ImageIO.read(cover.toFile());
                assertNotNull(image);
                assertEquals(info.width() / (double) info.height(), image.getWidth() / (double) image.getHeight(), .01);
                assertTrue(Math.max(image.getWidth(), image.getHeight()) <= 256);
                assertTrue(image.getWidth() <= info.width() && image.getHeight() <= info.height());
                var top = new java.awt.Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 10));
                var bottom = new java.awt.Color(image.getRGB(image.getWidth() / 2, image.getHeight() * 9 / 10));
                assertTrue(top.getRed() > 150 && top.getGreen() < 100, "top of frame must survive");
                assertTrue(bottom.getGreen() > 150 && bottom.getRed() < 100, "bottom of frame must survive");
            }
        }
    }

    @Test void rotatedVideoCoverUsesDisplayedPortraitOrientation() throws Exception {
        var source = root.resolve("landscape.mp4");
        ffmpeg.command(List.of("-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=2", "-t", "1", "-c:v", "libx264", source.toString()), 30, "fixture failed");
        var rotated = root.resolve("rotated.mp4");
        ffmpeg.command(List.of("-y", "-display_rotation:v:0", "90", "-i", source.toString(), "-c", "copy", rotated.toString()), 30, "rotation fixture failed");
        var info = ffmpeg.probe(rotated);
        assertEquals(180, info.width());
        assertEquals(320, info.height());
        var cover = root.resolve("rotated.webp");
        ffmpeg.automaticCover(rotated, cover, info);
        var image = ImageIO.read(cover.toFile());
        assertEquals(9d / 16, image.getWidth() / (double) image.getHeight(), .01);
    }
}
