/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;
import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ColorSpace;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real custom profile metadata matches the ordinary public graphics APIs. */
public class GateCustomColourDeviceTest {
    private static final String ICC = "AAACMEFEQkUCEAAAbW50clJHQiBYWVogB9AACAALABMAMwA7YWNzcEFQUEwAAAAAbm9uZQAAAAAAAAAAAAAAAAAAAAAAAPbWAAEAAAAA0y1BREJFAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKY3BydAAAAPwAAAAyZGVzYwAAATAAAABrd3RwdAAAAZwAAAAUYmtwdAAAAbAAAAAUclRSQwAAAcQAAAAOZ1RSQwAAAdQAAAAOYlRSQwAAAeQAAAAOclhZWgAAAfQAAAAUZ1hZWgAAAggAAAAUYlhZWgAAAhwAAAAUdGV4dAAAAABDb3B5cmlnaHQgMjAwMCBBZG9iZSBTeXN0ZW1zIEluY29ycG9yYXRlZAAAAGRlc2MAAAAAAAAAEUFkb2JlIFJHQiAoMTk5OCkAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFhZWiAAAAAAAADzUQABAAAAARbMWFlaIAAAAAAAAAAAAAAAAAAAAABjdXJ2AAAAAAAAAAECMwAAY3VydgAAAAAAAAABAjMAAGN1cnYAAAAAAAAAAQIzAABYWVogAAAAAAAAnBgAAE+lAAAE/FhZWiAAAAAAAAA0jQAAoCwAAA+VWFlaIAAAAAAAACYxAAAQLwAAvpw=";
    @Before public void prepare() {
        DecodeGate.setLocal(null);
        DecodeGate.setWaitForTest(20000L);
        DecodeGate.resetForTest();
    }
    @After public void restore() {
        DecodeGate.setLocal(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }
    @Test public void factoryKeepsCustomMatrix() throws Exception { compare(0,0); }
    @Test public void factoryKeepsNearNamedMatrix() throws Exception { compare(1,0); }
    @Test public void factoryKeepsCustomTransfer() throws Exception { compare(2,0); }
    @Test public void headerKeepsCustomMatrix() throws Exception { compare(0,1); }
    @Test public void imageDecoderKeepsCustomMatrix() throws Exception { compare(0,2); }
    @Test public void regionKeepsCustomMatrix() throws Exception { compare(0,3); }
    @Test public void wideFactoryKeepsCustomMatrix() throws Exception { compare(0,4); }
    @Test public void drawableKeepsCustomMatrix() throws Exception { compare(0,5); }
    @Test public void invalidCustomMetadataStopsBeforeHeader() throws Exception {
        float[] values=description();values[0]=Float.NaN;reject(values);
    }
    @Test public void singularCustomMetadataStopsBeforeHeader() throws Exception {
        float[] values=description();System.arraycopy(values,3,values,0,3);reject(values);
    }
    private static void compare(int variant,int operation) throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            executor.submit((Callable<Void>)()->{
                byte[] png=png(variant);
                Bitmap stock=null,gated=null;
                try {
                    if(operation==1) {
                        DecodeGate.setForcedForTest(false);
                        ColorSpace stockColor=header(png);
                        assertNotNull("native fixture lacks custom header colour",stockColor);
                        assertEquals("native fixture lacks custom header identity",-1,stockColor.getId());
                        DecodeGate.setForcedForTest(true);DecodeGate.resetForTest();
                        compareColor(stockColor,header(png));
                        return null;
                    }
                    DecodeGate.setForcedForTest(false);
                    stock=decode(png,operation);
                    assertNotNull("native reference refused custom profile",stock);
                    assertNotNull("native fixture lacks custom colour",stock.getColorSpace());
                    assertEquals("native fixture lacks custom identity",-1,stock.getColorSpace().getId());
                    DecodeGate.setForcedForTest(true);DecodeGate.resetForTest();
                    gated=decode(png,operation);
                    assertNotNull(gated);
                    assertEquals(stock.getConfig(),gated.getConfig());
                    assertEquals(stock.getWidth(),gated.getWidth());assertEquals(stock.getHeight(),gated.getHeight());
                    compareColor(stock.getColorSpace(),gated.getColorSpace());
                    for(int y=0;y<stock.getHeight();y+=3) for(int x=0;x<stock.getWidth();x+=3) {
                        int before=stock.getPixel(x,y),after=gated.getPixel(x,y);
                        for(int shift=0;shift<32;shift+=8) assertTrue("custom colour pixel differs",
                                Math.abs((before>>>shift&255)-(after>>>shift&255))<=3);
                    }
                    assertTrue("no actual VM decode",DecodeGate.countsForTest()[0]>0);
                    return null;
                } finally { if(stock!=null)stock.recycle();if(gated!=null)gated.recycle(); }
            }).get(90,TimeUnit.SECONDS);
        } finally { executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    private static Bitmap decode(byte[] png,int operation) throws Exception {
        if(operation==2) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                (decoder,info,source)->decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        if(operation==3) {
            BitmapRegionDecoder region=BitmapRegionDecoder.newInstance(png,0,png.length,false);
            assertNotNull(region);
            try { return region.decodeRegion(new Rect(8,4,40,20),new BitmapFactory.Options()); }
            finally { region.recycle(); }
        }
        if(operation==5) {
            Object drawable=ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(png)),
                    (decoder,info,source)->decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            assertTrue(drawable instanceof BitmapDrawable);return ((BitmapDrawable)drawable).getBitmap();
        }
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inPreferredConfig=operation==4?Bitmap.Config.RGBA_F16:Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(png,0,png.length,options);
    }
    private static final class HeaderSeen extends RuntimeException { }
    private static ColorSpace header(byte[] png) throws Exception {
        ColorSpace[] result=new ColorSpace[1];
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)),(decoder,info,source)->{
                result[0]=info.getColorSpace();throw new HeaderSeen();
            });
            fail("header callback did not stop decoding");
        } catch(HeaderSeen expected) { }
        return result[0];
    }
    private static void compareColor(ColorSpace before,ColorSpace after) {
        assertNotNull(after);assertEquals(before.getId(),after.getId());assertEquals(before.getName(),after.getName());
        assertTrue(before instanceof ColorSpace.Rgb);assertTrue(after instanceof ColorSpace.Rgb);
        ColorSpace.Rgb a=(ColorSpace.Rgb)before,b=(ColorSpace.Rgb)after;
        assertArrayEquals(a.getTransform(),b.getTransform(),0.00001f);
        ColorSpace.Rgb.TransferParameters x=a.getTransferParameters(),y=b.getTransferParameters();
        assertNotNull(x);assertNotNull(y);
        assertArrayEquals(new double[]{x.a,x.b,x.c,x.d,x.e,x.f,x.g},
                new double[]{y.a,y.b,y.c,y.d,y.e,y.f,y.g},0.00001);
    }
    private static float[] description() {
        return new float[]{0.67224f,0.31111f,0.01947f,0.20528f,0.62567f,0.06087f,
                0.14919f,0.06322f,0.74457f,1,0,0,0,0,0,2.2f};
    }
    private static void reject(float[] values) throws Exception {
        DecodeGate.setForcedForTest(true);DecodeGate.resetForTest();
        FakeGateProvider provider=new FakeGateProvider(0);
        DecodeGate.setLocal((method,extras)->{
            IRemoteCallback callback=IRemoteCallback.Stub.asInterface(extras.getBinder(DecodeGate.EXTRA_CALLBACK));
            Bundle forwarded=new Bundle(extras);
            forwarded.putBinder(DecodeGate.EXTRA_CALLBACK,new IRemoteCallback.Stub(){
                @Override public void sendResult(Bundle reply) throws RemoteException {
                    reply.putInt("colorSpace",8);reply.putFloatArray("colorDescription",values);callback.sendResult(reply);
                }
            }.asBinder());
            return provider.call(method,forwarded);
        });
        boolean[] called={false};
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png(0))),
                    (decoder,info,source)->called[0]=true);
            fail("invalid custom colour reached decoding");
        } catch(ImageDecoder.DecodeException expected) { assertFalse(called[0]); }
    }
    private static byte[] profile(int variant) {
        byte[] bytes=Base64.decode(ICC,Base64.DEFAULT);ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int count=data.getInt(128);
        for(int i=0;i<count;i++) {
            int at=132+12*i,tag=data.getInt(at),offset=data.getInt(at+4);
            if(variant<2 && tag==0x7258595a)data.putInt(offset+8,data.getInt(offset+8)+(variant==0?4096:256));
            if(variant==2 && (tag==0x72545243||tag==0x67545243||tag==0x62545243))
                data.putShort(offset+12,(short)((data.getShort(offset+12)&65535)+1));
        }
        return bytes;
    }
    private static byte[] compressed(byte[] raw) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(DeflaterOutputStream zip=new DeflaterOutputStream(out)){zip.write(raw);}return out.toByteArray();
    }
    private static void chunk(DataOutputStream out,int type,byte[] bytes) throws Exception {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();DataOutputStream tagged=new DataOutputStream(raw);
        tagged.writeInt(type);tagged.write(bytes);byte[] checked=raw.toByteArray();CRC32 crc=new CRC32();crc.update(checked);
        out.writeInt(bytes.length);out.write(checked);out.writeInt((int)crc.getValue());
    }
    private static byte[] png(int variant) throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(result);
        out.writeLong(0x89504e470d0a1a0aL);
        chunk(out,0x49484452,ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putInt(64).putInt(32)
                .put((byte)8).put((byte)2).put((byte)0).put((byte)0).put((byte)0).array());
        ByteArrayOutputStream icc=new ByteArrayOutputStream();icc.write(new byte[]{'c',0,0});icc.write(compressed(profile(variant)));
        chunk(out,0x69434350,icc.toByteArray());ByteArrayOutputStream pixels=new ByteArrayOutputStream();
        for(int y=0;y<32;y++){pixels.write(0);for(int x=0;x<64;x++){pixels.write(32+x*3);pixels.write(40+y*5);pixels.write(160-x);}}
        chunk(out,0x49444154,compressed(pixels.toByteArray()));chunk(out,0x49454e44,new byte[0]);return result.toByteArray();
    }
}
