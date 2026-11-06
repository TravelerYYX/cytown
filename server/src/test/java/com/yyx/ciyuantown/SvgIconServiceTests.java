package com.yyx.ciyuantown;

import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.image.SvgIconService;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SvgIconServiceTests {
    final SvgIconService icons = new SvgIconService(null);
    byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void keepsVectorGeometryGradientsAndInternalReferences() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' class='icon' viewBox='0 0 24 24'><defs><linearGradient id='a'><stop offset='0' stop-color='#fff'/></linearGradient></defs><path d='M0 0h24v24H0z' style='fill:url(#a);stroke:#000;stroke-width:1'/></svg>";
        String clean = new String(icons.sanitize(bytes(svg)), StandardCharsets.UTF_8);
        assertTrue(clean.contains("viewBox=\"0 0 24 24\""));
        assertTrue(clean.contains("url(#a)"));
        assertTrue(clean.contains("<path"));
    }
    @Test void rejectsScriptsEventsForeignContentAndExternalReferences() {
        for (String content : new String[]{
                "<script>alert(1)</script>", "<foreignObject/>", "<image href='https://example.com/x.png'/>",
                "<path onload='alert(1)'/>", "<use href='https://example.com/x.svg#a'/>",
                "<path fill='url(https://example.com/x)'/>", "<path style='fill:url(data:image/svg+xml,a)'/>",
                "<path style='fill:u\\72l(https://example.com)'/>", "<style>@import 'https://example.com';</style>",
                "<path style='behavior:expression(alert(1))'/>", "<?xml-stylesheet href='https://example.com/x'?>"}) {
            assertThrows(BizException.class, () -> icons.sanitize(bytes("<svg xmlns='http://www.w3.org/2000/svg'>" + content + "</svg>")), content);
        }
        assertThrows(BizException.class, () -> icons.sanitize(bytes("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///C:/Windows/win.ini'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>")));
        assertThrows(BizException.class, () -> icons.sanitize(bytes("<html/>")));
        assertThrows(BizException.class, () -> icons.sanitize(new byte[SvgIconService.MAX_BYTES + 1]));
        assertThrows(BizException.class, () -> icons.sanitize(bytes("<svg xmlns='http://www.w3.org/2000/svg'>" + "<g>".repeat(70) + "</g>".repeat(70) + "</svg>")));
    }
}
