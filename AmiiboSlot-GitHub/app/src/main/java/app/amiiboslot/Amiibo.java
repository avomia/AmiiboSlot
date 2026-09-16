package app.amiiboslot;

import java.util.Arrays;
import java.security.SecureRandom;

/** Pure file operations; never changes the caller's original byte array. */
public final class Amiibo {
    public static final byte[] VERSION = hexBytes("0004040201001103");
    public final byte[] original;
    public final boolean decrypted;
    public final String id;
    public Amiibo(byte[] bytes) {
        if (bytes.length != 540) throw new IllegalArgumentException("Нужен BIN ровно 540 байт. Получено: " + bytes.length);
        original = bytes.clone();
        if (validUid(bytes)) {
            decrypted = false;
            id = hex(Arrays.copyOfRange(bytes, 84, 92));
        } else {
            // Internal amiitool layout; a broken raw UID must not be silently repaired.
            boolean internal = bytes[2] == 0x0f && (bytes[3]&255) == 0xe0 && bytes[483] == 2;
            if (!internal) throw new IllegalArgumentException("Повреждён UID/BCC или неизвестный формат. Исходник не изменён.");
            decrypted = true;
            id = hex(Arrays.copyOfRange(bytes, 476, 484));
        }
        if (!id.endsWith("02")) throw new IllegalArgumentException("Не обнаружен идентификатор Amiibo.");
    }
    public byte[] prepare(byte[] keys) throws Exception {
        byte[] tag;
        if (!decrypted) tag = original.clone();
        else {
            if (keys == null) throw new IllegalArgumentException("Для Decrypted BIN импортируйте свой key_retail.bin (160 байт).");
            byte[] plain = original.clone();
            byte[] uidBlock = Arrays.copyOfRange(plain, 468, 476);
            if (uidBlock[0] != 4 || uidBlock[3] != (byte)(0x88 ^ uidBlock[0] ^ uidBlock[1] ^ uidBlock[2])) {
                if (!allZero(uidBlock)) throw new IllegalArgumentException("Некорректный UID в Decrypted BIN.");
                new SecureRandom().nextBytes(uidBlock);
                uidBlock[0]=4;
                uidBlock[3]=(byte)(0x88 ^ uidBlock[0] ^ uidBlock[1] ^ uidBlock[2]);
                System.arraycopy(uidBlock,0,plain,468,8);
            }
            // Blank decrypted templates omit NTAG header and Amiibo format marker.
            plain[0]=(byte)(uidBlock[4]^uidBlock[5]^uidBlock[6]^uidBlock[7]);
            plain[1]=0x48; plain[2]=0x0f; plain[3]=(byte)0xe0;
            System.arraycopy(hexBytes("f110ffee"),0,plain,4,4);
            plain[40]=(byte)0xa5;
            tag = AmiiboCrypto.pack(keys, plain);
            AmiiboCrypto.unpack(keys,tag); // Check the actual encrypted tag, not just its length.
            System.arraycopy(hexBytes("01000fbd000000045f000000"),0,tag,520,12);
        }
        if (!validUid(tag)) throw new IllegalArgumentException("Ошибка контрольных байтов UID.");
        byte[] uid=uid(tag);
        byte[] pwd={(byte)(0xaa^uid[1]^uid[3]),(byte)(0x55^uid[2]^uid[4]),(byte)(0xaa^uid[3]^uid[5]),(byte)(0x55^uid[4]^uid[6])};
        System.arraycopy(pwd,0,tag,532,4);
        tag[536]=(byte)0x80; tag[537]=(byte)0x80;
        return tag;
    }
    public static boolean validUid(byte[] b) {
        return b.length>=9 && b[0]==4 && b[3]==(byte)(0x88^b[0]^b[1]^b[2]) && b[8]==(byte)(b[4]^b[5]^b[6]^b[7]);
    }
    public static byte[] uid(byte[] b) {return new byte[]{b[0],b[1],b[2],b[4],b[5],b[6],b[7]};}
    public static boolean allZero(byte[] b) { for(byte v:b) if(v!=0)return false; return true; }
    public static String hex(byte[] b) {StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
    public static byte[] hexBytes(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}
