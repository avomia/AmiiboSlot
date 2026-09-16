package app.amiiboslot;

// Derived from amiitool, MIT, (c) 2015–2017 Marcos Del Sol Vives, (c) 2016 javiMaD.
// See assets/amiitool-LICENSE.txt. No retail keys are included.
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;

public final class AmiiboCrypto {
    static byte[] slice(byte[] x,int p,int n){return Arrays.copyOfRange(x,p,p+n);}
    static byte[] hmac(byte[] key,byte[] data)throws Exception {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(data);}
    public static void validateKeys(byte[] keys) {
        if(keys.length!=160)throw new IllegalArgumentException("key_retail.bin должен содержать 160 байт.");
        for(int base:new int[]{0,80}){
            if((keys[base+31]&255)>16)throw new IllegalArgumentException("Некорректная структура ключей.");
            boolean nul=false;for(int i=16;i<30;i++)if(keys[base+i]==0)nul=true;
            if(!nul || Amiibo.allZero(slice(keys,base,16)))throw new IllegalArgumentException("Некорректный ключ.");
        }
        String first=new String(slice(keys,16,14),java.nio.charset.StandardCharsets.US_ASCII);
        String second=new String(slice(keys,96,14),java.nio.charset.StandardCharsets.US_ASCII);
        if(!first.startsWith("unfixed infos") || !second.startsWith("locked secret"))throw new IllegalArgumentException("Ожидается key_retail.bin: unfixed infos + locked secret.");
    }
    static byte[] derive(byte[] master,byte[] p)throws Exception{
        byte[] seed=new byte[64];System.arraycopy(p,41,seed,0,2);System.arraycopy(p,468,seed,16,8);System.arraycopy(p,468,seed,24,8);System.arraycopy(p,488,seed,32,32);
        ByteArrayOutputStream s=new ByteArrayOutputStream();
        for(int i=16;i<30;i++){s.write(master[i]);if(master[i]==0)break;}
        int magic=master[31]&255;s.write(seed,0,16-magic);s.write(master,32,magic);s.write(seed,16,16);
        for(int i=0;i<32;i++)s.write(seed[i+32]^master[i+48]);
        byte[] prepared=s.toByteArray();ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int ctr=0;ctr<2;ctr++){byte[] msg=new byte[prepared.length+2];msg[1]=(byte)ctr;System.arraycopy(prepared,0,msg,2,prepared.length);out.write(hmac(slice(master,0,16),msg));}
        return Arrays.copyOf(out.toByteArray(),48);
    }
    public static byte[] pack(byte[] keys,byte[] plain)throws Exception{
        validateKeys(keys);
        byte[] d=derive(slice(keys,0,80),plain),t=derive(slice(keys,80,80),plain);
        byte[] signed=plain.clone();System.arraycopy(hmac(slice(t,32,16),slice(plain,468,52)),0,signed,436,32);
        System.arraycopy(hmac(slice(d,32,16),slice(signed,41,479)),0,signed,8,32);
        Cipher c=Cipher.getInstance("AES/CTR/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(slice(d,0,16),"AES"),new IvParameterSpec(slice(d,16,16)));
        System.arraycopy(c.doFinal(slice(plain,44,392)),0,signed,44,392);
        byte[] tag=new byte[540];int[][] map={{0,8,8},{8,128,32},{40,16,36},{76,160,360},{436,52,32},{468,0,8},{476,84,44}};
        for(int[] m:map)System.arraycopy(signed,m[0],tag,m[1],m[2]);return tag;
    }
    /** Decrypt and verify both Amiibo HMACs; rejects modified or wrong-key dumps. */
    public static byte[] unpack(byte[] keys,byte[] tag)throws Exception{
        validateKeys(keys);
        if(tag.length!=540)throw new IllegalArgumentException("Нужен BIN 540 байт.");
        byte[] internal=new byte[540];
        int[][] map={{8,0,8},{128,8,32},{16,40,36},{160,76,360},{52,436,32},{0,468,8},{84,476,44}};
        for(int[] m:map)System.arraycopy(tag,m[0],internal,m[1],m[2]);
        byte[] d=derive(slice(keys,0,80),internal),t=derive(slice(keys,80,80),internal);
        byte[] plain=internal.clone();Cipher cipher=Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(slice(d,0,16),"AES"),new IvParameterSpec(slice(d,16,16)));
        System.arraycopy(cipher.doFinal(slice(internal,44,392)),0,plain,44,392);
        byte[] tagMac=hmac(slice(t,32,16),slice(plain,468,52));
        System.arraycopy(tagMac,0,plain,436,32);
        byte[] dataMac=hmac(slice(d,32,16),slice(plain,41,479));
        if(!java.security.MessageDigest.isEqual(tagMac,slice(internal,436,32)) || !java.security.MessageDigest.isEqual(dataMac,slice(internal,8,32)))throw new IllegalArgumentException("Amiibo HMAC не совпадает: неверные ключи или повреждённый BIN.");
        System.arraycopy(dataMac,0,plain,8,32);return plain;
    }
}
