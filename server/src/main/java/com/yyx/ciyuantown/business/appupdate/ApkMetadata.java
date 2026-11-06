package com.yyx.ciyuantown.business.appupdate;

import com.yyx.ciyuantown.common.exception.BizException;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Bounded binary Android XML reader. Does not execute or extract uploaded archives. */
public record ApkMetadata(String packageName, long versionCode, String versionName) {
    public static ApkMetadata read(Path path) {
        try (var zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null || entry.getSize() > 4 * 1024 * 1024 || zip.getEntry("classes.dex") == null)
                throw new IOException("missing APK entries");
            byte[] bytes;
            try (var input = zip.getInputStream(entry)) { bytes = input.readNBytes(4 * 1024 * 1024 + 1); }
            if (bytes.length > 4 * 1024 * 1024) throw new IOException("manifest too large");
            return parse(bytes);
        } catch (Exception e) { throw new BizException("无效的 APK，请上传包含有效 AndroidManifest.xml 和 classes.dex 的安装包"); }
    }
    static ApkMetadata parse(byte[] bytes) throws IOException {
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 8 || u16(b,0) != 3 || b.getInt(4) != bytes.length) throw new IOException("not binary XML");
        List<String> strings = List.of();
        for (int p = u16(b,2); p + 8 <= bytes.length;) {
            int type = u16(b,p), header = u16(b,p+2), size = b.getInt(p+4);
            if (header < 8 || size < header || size > bytes.length - p) throw new IOException("bad chunk");
            if (type == 1) strings = pool(b,p,header,size);
            if (type == 0x102) {
                if (header < 16 || size < 36) throw new IOException("bad element");
                String tag = string(strings,b.getInt(p+20));
                if (tag.equals("manifest")) {
                    int start = p+16+u16(b,p+24), width = u16(b,p+26), count = u16(b,p+28);
                    if (width < 20 || start < p+36 || (long)start+(long)width*count > (long)p+size) throw new IOException("bad attributes");
                    String pkg = "", name = ""; long code = 0, major = 0;
                    for (int i=0;i<count;i++) {
                        int a = start+i*width, raw = b.getInt(a+8), data = b.getInt(a+16), valueType = bytes[a+15]&255;
                        String key = string(strings,b.getInt(a+4));
                        String ns = string(strings,b.getInt(a));
                        String value = raw >= 0 ? string(strings,raw) : valueType == 3 ? string(strings,data) : "";
                        if (key.equals("package") && ns.isEmpty()) pkg=value;
                        if (ns.equals("http://schemas.android.com/apk/res/android")) {
                            if (key.equals("versionName")) name=value;
                            if (key.equals("versionCode")) code = valueType >= 16 && valueType <= 31 ? Integer.toUnsignedLong(data) : Long.parseLong(value);
                            if (key.equals("versionCodeMajor")) major = Integer.toUnsignedLong(data);
                        }
                    }
                    if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") || code <= 0 || major != 0)
                        throw new IOException("invalid package/version");
                    return new ApkMetadata(pkg,code,name);
                }
            }
            p += size;
        }
        throw new IOException("missing manifest");
    }
    private static List<String> pool(ByteBuffer b,int p,int header,int size) throws IOException {
        if(header < 28) throw new IOException("bad pool");
        int count=b.getInt(p+8), flags=b.getInt(p+16), start=b.getInt(p+20);
        if(count<0 || count>100000 || (long)header+4L*count>size || start<header || start>size) throw new IOException("bad pool bounds");
        var result = new ArrayList<String>(count);
        for(int i=0;i<count;i++) {
            long pos=(long)p+start+Integer.toUnsignedLong(b.getInt(p+header+4*i));
            if(pos<p || pos>=p+size) throw new IOException("bad string offset");
            int[] cursor={(int)pos};
            if((flags&256)!=0) {
                length(b,cursor,true); int n=length(b,cursor,true);
                if((long)cursor[0]+n>=p+size) throw new IOException("bad UTF8 string");
                byte[] data=new byte[n]; b.get(cursor[0],data); result.add(new String(data,StandardCharsets.UTF_8));
            } else {
                int n=length(b,cursor,false);
                if((long)cursor[0]+2L*n+2>p+size) throw new IOException("bad UTF16 string");
                byte[] data=new byte[Math.multiplyExact(n,2)]; b.get(cursor[0],data); result.add(new String(data,StandardCharsets.UTF_16LE));
            }
        }
        return result;
    }
    private static int length(ByteBuffer b,int[] c,boolean utf8) {
        int v=utf8?b.get(c[0]++)&255:u16(b,c[0]); if(!utf8)c[0]+=2;
        if((v&(utf8?128:32768))!=0) { int next=utf8?b.get(c[0]++)&255:u16(b,c[0]); if(!utf8)c[0]+=2; return ((v&(utf8?127:32767))<<(utf8?8:16))|next; }
        return v;
    }
    private static int u16(ByteBuffer b,int p){return Short.toUnsignedInt(b.getShort(p));}
    private static String string(List<String> s,int i) throws IOException {if(i==-1)return "";if(i<0||i>=s.size())throw new IOException("bad string index");return s.get(i);}
}
