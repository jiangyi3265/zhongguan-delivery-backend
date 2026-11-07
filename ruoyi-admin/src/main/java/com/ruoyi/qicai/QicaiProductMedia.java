package com.ruoyi.qicai;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.SecurityUtils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Only decoded, bounded, single-frame JPEGs are exposed as public product media. */
@Service
public class QicaiProductMedia {
    public static final String PREFIX="/qicai/mobile/catalog/images/";
    private final JdbcTemplate jdbc;
    public QicaiProductMedia(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private Path folder() { return Paths.get(RuoYiConfig.getProfile(),"qicai-product").toAbsolutePath().normalize(); }
    private QicaiApiException invalid(String message) { return new QicaiApiException(HttpStatus.BAD_REQUEST,message); }

    @Transactional
    public Map<String,Object> upload(MultipartFile file) throws Exception {
        if(file.isEmpty() || file.getSize()>3*1024*1024) throw invalid("请选择3MB以内的静态JPEG或PNG图片");
        byte[] input=file.getBytes();
        // PNG animation is carried in acTL. Reject it, as well as GIF and other formats.
        boolean png=input.length>8 && input[0]==(byte)137 && input[1]==80 && input[2]==78 && input[3]==71;
        boolean jpeg=input.length>3 && input[0]==(byte)255 && input[1]==(byte)216 && input[2]==(byte)255;
        if(!png && !jpeg) throw invalid("只支持静态JPEG或PNG图片");
        if(png) {
            int offset=8;
            while(offset+12<=input.length) {
                long length=((long)(input[offset]&255)<<24)|((long)(input[offset+1]&255)<<16)|((long)(input[offset+2]&255)<<8)|(input[offset+3]&255);
                if(length>input.length-offset-12) throw invalid("PNG文件损坏");
                String chunk=new String(input,offset+4,4,java.nio.charset.StandardCharsets.US_ASCII);
                if("acTL".equals(chunk)) throw invalid("商品图片不能使用动画");
                offset+=(int)length+12;
            }
        }
        BufferedImage decoded;
        try(ImageInputStream stream=ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext()) throw invalid("图片无法解码");
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream,false,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>6000 || height>6000 || (long)width*height>16000000) throw invalid("图片尺寸过大，请使用1600万像素以内图片");
                if(reader.getNumImages(true)!=1) throw invalid("商品图片不能使用动画或多页图片");
                decoded=reader.read(0);
            } finally { reader.dispose(); }
        } catch(QicaiApiException ex) { throw ex; }
        catch(Exception ex) { throw invalid("图片损坏，请重新选择图片"); }
        int orientation=jpeg?orientation(input):1;
        int rawWidth=decoded.getWidth(),rawHeight=decoded.getHeight();
        int w=orientation>=5?rawHeight:rawWidth,h=orientation>=5?rawWidth:rawHeight;
        double scale=Math.min(1,1600d/Math.max(w,h));
        BufferedImage normalized=new BufferedImage(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,normalized.getWidth(),normalized.getHeight());
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.scale(scale,scale);
            java.awt.geom.AffineTransform transform;
            switch(orientation) {
                case 2: transform=new java.awt.geom.AffineTransform(-1,0,0,1,rawWidth,0);break;
                case 3: transform=new java.awt.geom.AffineTransform(-1,0,0,-1,rawWidth,rawHeight);break;
                case 4: transform=new java.awt.geom.AffineTransform(1,0,0,-1,0,rawHeight);break;
                case 5: transform=new java.awt.geom.AffineTransform(0,1,1,0,0,0);break;
                case 6: transform=new java.awt.geom.AffineTransform(0,1,-1,0,rawHeight,0);break;
                case 7: transform=new java.awt.geom.AffineTransform(0,-1,-1,0,rawHeight,rawWidth);break;
                case 8: transform=new java.awt.geom.AffineTransform(0,-1,1,0,0,rawWidth);break;
                default: transform=new java.awt.geom.AffineTransform();
            }
            graphics.drawImage(decoded,transform,null);
        }
        finally { graphics.dispose(); }
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        if(!ImageIO.write(normalized,"JPEG",buffer)) throw new IllegalStateException("JPEG encoder unavailable");
        byte[] bytes=buffer.toByteArray();
        String hash=hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        Path directory=folder();Files.createDirectories(directory);
        Path target=directory.resolve(hash+".jpg");
        if(!Files.exists(target)) {
            Path temporary=Files.createTempFile(directory,"image-",".tmp");
            try { Files.write(temporary,bytes); try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); } catch(FileAlreadyExistsException ignored) {} }
            finally { Files.deleteIfExists(temporary); }
        }
        jdbc.update("INSERT IGNORE INTO qc_product_image(image_hash,byte_size,width,height,created_by) VALUES(?,?,?,?,?)",hash,bytes.length,normalized.getWidth(),normalized.getHeight(),SecurityUtils.getUserId());
        Map<String,Object> result=new LinkedHashMap<>();result.put("imageUrl",PREFIX+hash+".jpg");result.put("width",normalized.getWidth());result.put("height",normalized.getHeight());return result;
    }

    public byte[] read(String name) throws Exception {
        if(!name.matches("[a-f0-9]{64}\\.jpg") || jdbc.queryForObject("SELECT COUNT(*) FROM qc_product_image WHERE image_hash=?",Long.class,name.substring(0,64))!=1) throw new QicaiApiException(HttpStatus.NOT_FOUND,"商品图片不存在");
        Path target=folder().resolve(name);
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) throw new QicaiApiException(HttpStatus.NOT_FOUND,"商品图片不存在");
        return Files.readAllBytes(target);
    }

    public void save(long productId,Map<String,Object> body) {
        if(!body.containsKey("imageUrl")) return; // Legacy clients preserve the current photo.
        Object raw=body.get("imageUrl");
        if(!(raw instanceof String)) throw invalid("商品图片地址无效");
        String url=(String)raw;
        if(url.isEmpty()) { jdbc.update("DELETE FROM qc_product_media WHERE product_id=?",productId);return; }
        if(!url.matches(java.util.regex.Pattern.quote(PREFIX)+"[a-f0-9]{64}\\.jpg")) throw invalid("请使用后台上传的商品图片");
        String hash=url.substring(PREFIX.length(),PREFIX.length()+64);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM qc_product_image WHERE image_hash=?",Long.class,hash)!=1) throw invalid("商品图片不存在，请重新上传");
        jdbc.update("INSERT INTO qc_product_media(product_id,image_hash) VALUES(?,?) ON DUPLICATE KEY UPDATE image_hash=VALUES(image_hash)",productId,hash);
    }

    @SuppressWarnings("unchecked")
    public Object attach(Object result) {
        List<Map<String,Object>> rows;
        if(result instanceof List) rows=(List<Map<String,Object>>)result;
        else if(result instanceof Map && ((Map<?,?>)result).get("rows") instanceof List) rows=(List<Map<String,Object>>)((Map<?,?>)result).get("rows");
        else if(result instanceof Map) rows=Collections.singletonList((Map<String,Object>)result);
        else return result;
        if(rows.isEmpty()) return result;
        List<Object> ids=new ArrayList<>();for(Map<String,Object> row:rows) ids.add(row.get("id"));
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        Map<Long,String> urls=new HashMap<>();
        for(Map<String,Object> media:jdbc.queryForList("SELECT product_id,image_hash FROM qc_product_media WHERE product_id IN ("+placeholders+")",ids.toArray())) urls.put(((Number)media.get("product_id")).longValue(),PREFIX+media.get("image_hash")+".jpg");
        for(Map<String,Object> row:rows) { String url=urls.get(((Number)row.get("id")).longValue());if(url!=null) row.put("imageUrl",url); }
        return result;
    }

    public void snapshot(long orderId,long productId) {
        jdbc.update("INSERT INTO qc_order_item_media(order_item_id,image_hash) SELECT i.id,m.image_hash FROM qc_order_item i JOIN qc_product_media m ON m.product_id=i.product_id WHERE i.order_id=? AND i.product_id=?",orderId,productId);
    }
    public void attachItems(List<Map<String,Object>> items) {
        for(Map<String,Object> item:items) {
            List<String> hashes=jdbc.queryForList("SELECT image_hash FROM qc_order_item_media WHERE order_item_id=?",String.class,item.get("id"));
            if(!hashes.isEmpty()) item.put("imageUrl",PREFIX+hashes.get(0)+".jpg");
        }
    }
    public void attachOrderRows(List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return;
        List<Object> ids=new ArrayList<>();for(Map<String,Object> row:rows)ids.add(row.get("id"));
        Map<Long,String> urls=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT i.order_id,m.image_hash FROM qc_order_item_media m JOIN qc_order_item i ON i.id=m.order_item_id WHERE i.order_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray())) urls.putIfAbsent(((Number)row.get("order_id")).longValue(),PREFIX+row.get("image_hash")+".jpg");
        for(Map<String,Object> row:rows) { String url=urls.get(((Number)row.get("id")).longValue());if(url!=null)row.put("imageUrl",url); }
    }
    private static String hex(byte[] data) { StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format("%02x",v&255));return b.toString(); }
    /** Read only the fixed-width TIFF orientation tag. No EXIF/XML/ICC metadata is copied. */
    private static int orientation(byte[] data) {
        int position=2;
        while(position+4<=data.length && (data[position]&255)==255) {
            while(position<data.length && (data[position]&255)==255)position++;
            if(position>=data.length)return 1;
            int marker=data[position++]&255;
            if(marker==0xda || marker==0xd9)return 1;
            if(marker==0xd8 || marker==1 || marker>=0xd0&&marker<=0xd7)continue;
            if(position+2>data.length)return 1;
            int length=u16(data,position,false);
            if(length<2 || length>data.length-position)return 1;
            int start=position+2,end=position+length;
            if(marker==0xe1 && end-start>=14 && data[start]=='E' && data[start+1]=='x' && data[start+2]=='i' && data[start+3]=='f' && data[start+4]==0 && data[start+5]==0) {
                int tiff=start+6;boolean little=data[tiff]=='I'&&data[tiff+1]=='I',big=data[tiff]=='M'&&data[tiff+1]=='M';
                if(!little&&!big || u16(data,tiff+2,little)!=42)return 1;
                long offset=u32(data,tiff+4,little);
                if(offset<8 || offset>end-tiff-2)return 1;
                int directory=tiff+(int)offset,count=u16(data,directory,little);
                if(count>4096 || (long)directory+2+(long)count*12>end)return 1;
                for(int n=0;n<count;n++) {
                    int entry=directory+2+n*12;
                    if(u16(data,entry,little)==0x112 && u16(data,entry+2,little)==3 && u32(data,entry+4,little)==1) {
                        int value=u16(data,entry+8,little);return value>=1&&value<=8?value:1;
                    }
                }
            }
            position=end;
        }
        return 1;
    }
    private static int u16(byte[] data,int p,boolean little) {return little?(data[p]&255)|((data[p+1]&255)<<8):((data[p]&255)<<8)|(data[p+1]&255);}
    private static long u32(byte[] data,int p,boolean little) {return little?u16(data,p,true)|((long)u16(data,p+2,true)<<16):((long)u16(data,p,false)<<16)|u16(data,p+2,false);}
}
