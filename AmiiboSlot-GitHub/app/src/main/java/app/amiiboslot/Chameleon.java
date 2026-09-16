package app.amiiboslot;

import java.io.*;
import java.util.*;

/** Device workflow. A command completes before the next one can begin. */
public final class Chameleon {
    public interface Transport {byte[] command(int command,byte[] data)throws Exception;}
    public interface Link extends Transport,AutoCloseable {boolean isReady();@Override void close();}
    public interface Progress {void show(String text);}
    public interface Backups {void save(int slot,Map<String,byte[]> entries)throws Exception;}
    private final Transport t;
    public Chameleon(Transport t){this.t=t;}
    byte[] cmd(int n,int... values)throws Exception{byte[] b=new byte[values.length];for(int i=0;i<b.length;i++)b[i]=(byte)values[i];return t.command(n,b);}
    static byte[] require(byte[] b,int n,String label)throws IOException{if(b.length!=n)throw new IOException("Некорректный ответ: "+label);return b;}
    public int[] slots()throws Exception{
        byte[] b=require(cmd(1019),32,"слоты");int[] slots=new int[8];for(int i=0;i<8;i++)slots[i]=Frame.word(b,i*4);return slots;
    }
    byte[] readMemory()throws Exception{
        if((require(cmd(4030),1,"число страниц")[0]&255)!=135)throw new IOException("Слот не содержит 135 страниц NTAG215");
        byte[] result=new byte[540];for(int page=0;page<135;page+=16){int n=Math.min(16,135-page);byte[] data=require(cmd(4021,page,n),n*4,"память");System.arraycopy(data,0,result,page*4,data.length);}return result;
    }
    static byte[] collision(byte[] tag){byte[] a=new byte[12];a[0]=7;System.arraycopy(Amiibo.uid(tag),0,a,1,7);a[8]=0x44;return a;}
    public void upload(int slot,byte[] tag,String name,Backups backups,Progress progress)throws Exception{
        if(slot<1||slot>8 || tag.length!=540 || !Amiibo.validUid(tag))throw new IllegalArgumentException("Некорректный слот или BIN");
        final int index=slot-1;int[] slotTypes=slots();int oldType=slotTypes[index];
        if(oldType!=0 && oldType!=1101)throw new IOException("Слот "+slot+" занят другим типом карты. Выберите пустой слот или NTAG215.");
        byte[] previousActive=require(cmd(1018),1,"активный слот");
        byte[] previousMode=require(cmd(1002),1,"режим");
        byte[] enabled=require(cmd(1023),16,"включённые слоты");
        boolean writing=false;
        try{
            cmd(1003,index);
            progress.show("Сохраняю резервную копию слота "+slot+"…");
            Map<String,byte[]> backup=new LinkedHashMap<>();
            backup.put("slot.txt",String.valueOf(slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            backup.put("type.bin",new byte[]{(byte)(oldType>>8),(byte)oldType});backup.put("active.bin",previousActive);backup.put("mode.bin",previousMode);backup.put("enabled.bin",enabled);
            if(oldType==1101){
                backup.put("original.bin",readMemory());
                for(int c:new int[]{4018,4019,4023,4025,4031,4036})backup.put("command-"+c+".bin",cmd(c));
                backup.put("counter.bin",cmd(4027,0));
                // A slot may have no nickname. This optional field is not needed to restore card data.
            }
            backups.save(slot,backup); // MUST succeed before any page/type change.
            writing=true;
            // Temporarily suppress RF emulation while its pages are being changed.
            cmd(1006,index,2,0);
            cmd(1004,index,0x04,0x4d);
            if(oldType==0)cmd(1005,index,0x04,0x4d);
            cmd(1003,index);
            for(int page=0;page<135;page+=16){
                int n=Math.min(16,135-page);byte[] payload=new byte[n*4+2];payload[0]=(byte)page;payload[1]=(byte)n;System.arraycopy(tag,page*4,payload,2,n*4);
                t.command(4022,payload);progress.show("Записываю слот "+slot+": "+Math.min(100,(page+n)*100/135)+"%");
            }
            t.command(4001,collision(tag));cmd(4020,0);t.command(4024,Amiibo.VERSION);
            cmd(4032,0);cmd(4029);cmd(4033,0);
            byte[] label=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(label.length>28)label="Amiibo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] nick=new byte[label.length+2];nick[0]=(byte)index;nick[1]=2;System.arraycopy(label,0,nick,2,label.length);t.command(1007,nick);
            progress.show("Проверяю все 540 байт, UID и GET_VERSION…");
            verify(tag);
            cmd(1006,index,2,1);cmd(1001,0);cmd(1009);
            // Switching away and back exercises saved slot data, not just the write buffer.
            cmd(1003,(index+1)%8);cmd(1003,index);verify(tag);
            if(require(cmd(1018),1,"активный слот")[0]!=(byte)index || require(cmd(1002),1,"режим")[0]!=0)throw new IOException("Не удалось включить эмуляцию");
            progress.show("Готово. Слот "+slot+" сохранён, проверен и активен.");
        }catch(Exception ex){
            if(!writing){try{t.command(1003,previousActive);}catch(Exception ignored){}}
            else {try{cmd(1006,index,2,0);cmd(1009);}catch(Exception ignored){}
                throw new IOException("Запись не завершена. Слот мог измениться; резервная копия сохранена. Подключитесь заново и повторите загрузку. "+ex.getMessage(),ex);}
            throw ex;
        }
    }
    private void verify(byte[] tag)throws Exception{
        if(!Arrays.equals(readMemory(),tag))throw new IOException("Память после записи отличается");
        if(!Arrays.equals(cmd(4023),Amiibo.VERSION))throw new IOException("GET_VERSION отличается");
        if(!Arrays.equals(cmd(4018),collision(tag)))throw new IOException("UID / ATQA / SAK отличаются");
        if(require(cmd(4019),1,"UID Magic")[0]!=0)throw new IOException("UID Magic остался включён");
    }
}
