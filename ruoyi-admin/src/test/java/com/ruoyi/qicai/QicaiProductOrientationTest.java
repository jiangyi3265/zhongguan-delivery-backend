package com.ruoyi.qicai;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual upload, EXIF parsing, transforms, JPEG encoding and filesystem output. */
class QicaiProductOrientationTest {
    @TempDir Path folder;
    String previousProfile;SecurityContext previousContext;
    @BeforeEach void setup() {
        previousProfile=RuoYiConfig.getProfile();previousContext=SecurityContextHolder.getContext();
        new RuoYiConfig().setProfile(folder.toString());
        SecurityContext context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L,103L,new SysUser(),Collections.emptySet()),null));SecurityContextHolder.setContext(context);
    }
    @AfterEach void reset() {new RuoYiConfig().setProfile(previousProfile);SecurityContextHolder.setContext(previousContext);}
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8})
    void keepsCameraOrientationAndStripsMetadata(int orientation) throws Exception {
        Color[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW};
        BufferedImage source=new BufferedImage(96,64,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=source.createGraphics();
        for(int i=0;i<4;i++){graphics.setColor(colors[i]);graphics.fillRect(i%2*48,i/2*32,48,32);}graphics.dispose();
        ByteArrayOutputStream raw=new ByteArrayOutputStream();ImageIO.write(source,"JPEG",raw);byte[] jpeg=raw.toByteArray();
        // Alternate little/big endian TIFF directories to exercise both camera encodings.
        ByteBuffer tiff=ByteBuffer.allocate(26).order(orientation%2==1?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        tiff.put((byte)(orientation%2==1?'I':'M')).put((byte)(orientation%2==1?'I':'M')).putShort((short)42).putInt(8).putShort((short)1).putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)orientation).putShort((short)0).putInt(0);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int app0End=4+((jpeg[4]&255)<<8)+(jpeg[5]&255);bytes.write(jpeg,0,app0End);bytes.write(new byte[]{(byte)255,(byte)225,0,34,'E','x','i','f',0,0});bytes.write(tiff.array());bytes.write(jpeg,app0End,jpeg.length-app0End);
        Map<String,Object> uploaded=new QicaiProductMedia(mock(JdbcTemplate.class)).upload(new MockMultipartFile("file","camera.jpg","image/jpeg",bytes.toByteArray()));
        String url=uploaded.get("imageUrl").toString();byte[] encoded=Files.readAllBytes(folder.resolve("qicai-product").resolve(url.substring(url.lastIndexOf('/')+1)));BufferedImage actual=ImageIO.read(new ByteArrayInputStream(encoded));
        assertEquals(orientation>=5?64:96,actual.getWidth());assertEquals(orientation>=5?96:64,actual.getHeight());
        int[][] expected={{0,1,2,3},{1,0,3,2},{3,2,1,0},{2,3,0,1},{0,2,1,3},{2,0,3,1},{3,1,2,0},{1,3,0,2}};
        for(int i=0;i<4;i++){Color got=new Color(actual.getRGB(actual.getWidth()*(i%2==0?1:3)/4,actual.getHeight()*(i<2?1:3)/4)),want=colors[expected[orientation-1][i]];assertTrue(Math.abs(got.getRed()-want.getRed())+Math.abs(got.getGreen()-want.getGreen())+Math.abs(got.getBlue()-want.getBlue())<80,"corner "+i);}
        assertFalse(new String(encoded,java.nio.charset.StandardCharsets.ISO_8859_1).contains("Exif"));
    }
}
