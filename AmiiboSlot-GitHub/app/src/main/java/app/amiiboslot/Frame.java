package app.amiiboslot;

import java.util.*;
import java.io.IOException;

/** Chameleon big-endian frame with three additive checksums. */
public final class Frame {
    public final int command,status; public final byte[] data;
    Frame(int cmd,int status,byte[] data){this.command=cmd;this.status=status;this.data=data;}
    public static int word(byte[] b,int p){return ((b[p]&255)<<8)|(b[p+1]&255);}
    static byte lrc(byte[] b,int end){int sum=0;for(int i=0;i<end;i++)sum+=b[i]&255;return (byte)-sum;}
    public static byte[] encode(int command,int status,byte[] data){
        if(data.length>4096)throw new IllegalArgumentException("Frame too large");
        byte[] b=new byte[data.length+10];b[0]=0x11;b[1]=(byte)0xef;
        b[2]=(byte)(command>>8);b[3]=(byte)command;b[4]=(byte)(status>>8);b[5]=(byte)status;b[6]=(byte)(data.length>>8);b[7]=(byte)data.length;b[8]=lrc(b,8);
        System.arraycopy(data,0,b,9,data.length);b[b.length-1]=lrc(b,b.length-1);return b;
    }
    public static final class Decoder {
        private byte[] pending=new byte[0];
        public synchronized List<Frame> accept(byte[] chunk)throws IOException{
            if(pending.length+chunk.length>16384)throw new IOException("Переполнение входящего BLE-буфера");
            byte[] b=Arrays.copyOf(pending,pending.length+chunk.length);System.arraycopy(chunk,0,b,pending.length,chunk.length);
            List<Frame> out=new ArrayList<>();int p=0;
            while(b.length-p>=2){
                if(b[p]!=0x11 || b[p+1]!=(byte)0xef){p++;continue;}
                if(b.length-p<9)break;
                int length=word(b,p+6);int sum=0;for(int j=p;j<p+9;j++)sum+=b[j]&255;
                if((sum&255)!=0 || length>4096)throw new IOException("Ошибка заголовка Chameleon");
                if(b.length-p<length+10)break;
                sum=0;for(int j=p;j<p+length+10;j++)sum+=b[j]&255;
                if((sum&255)!=0)throw new IOException("Ошибка контрольной суммы Chameleon");
                out.add(new Frame(word(b,p+2),word(b,p+4),Arrays.copyOfRange(b,p+9,p+9+length)));p+=length+10;
            }
            pending=Arrays.copyOfRange(b,p,b.length);return out;
        }
    }
}
