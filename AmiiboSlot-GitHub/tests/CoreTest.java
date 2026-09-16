package app.amiiboslot;

import java.util.*;
import java.nio.file.*;
import java.io.*;

public final class CoreTest {
    static int checks=0;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    interface Throwing{void run()throws Exception;}
    static void rejects(Throwing f,String name)throws Exception{boolean failed=false;try{f.run();}catch(Exception e){failed=true;}check(failed,name);}
    static byte[] sample(){byte[] b=new byte[540];byte[] header=Amiibo.hexBytes("04df84d772754c80cb480fe0f110ffee");System.arraycopy(header,0,b,0,16);System.arraycopy(Amiibo.hexBytes("01010000000e0002"),0,b,84,8);return b;}
    static class Device implements Chameleon.Transport {
        byte[][] memory=new byte[8][540];int[] types={1001,1001,1101,1101,1101,1101,1101,0};
        byte[][] versions=new byte[8][8],collisions=new byte[8][12];
        int active=2,mode=0,writes=0,saves=0;boolean backup=false,corrupt=false,saveFailure=false;
        byte[] enabled=new byte[16];byte[] magic=new byte[8];
        Device(){for(int i=0;i<8;i++){memory[i]=sample();versions[i]=Amiibo.VERSION.clone();collisions[i]=Chameleon.collision(memory[i]);enabled[i*2]=1;}}
        public byte[] command(int c,byte[] d)throws Exception{
            switch(c){
                case 1019:byte[] info=new byte[32];for(int i=0;i<8;i++){info[i*4]=(byte)(types[i]>>8);info[i*4+1]=(byte)types[i];}return info;
                case 1018:return new byte[]{(byte)active};
                case 1002:return new byte[]{(byte)mode};
                case 1023:return enabled.clone();
                case 1003:active=d[0]&255;return new byte[0];
                case 4030:return new byte[]{(byte)135};
                case 4021:byte[] read=Arrays.copyOfRange(memory[active],(d[0]&255)*4,((d[0]&255)+(d[1]&255))*4);if(corrupt&&writes>0&&d[0]==0)read[9]^=1;return read;
                case 4018:return collisions[active].clone();
                case 4019:return new byte[]{magic[active]};
                case 4023:return versions[active].clone();
                case 4025:return new byte[32];
                case 4027:return new byte[4];
                case 4031:case 4036:return new byte[]{0};
                case 1004:check(backup,"backup before type change");types[d[0]&255]=Frame.word(d,1);return new byte[0];
                case 1005:check(backup,"backup before default");memory[d[0]&255]=new byte[540];return new byte[0];
                case 4022:check(backup,"backup before pages");writes++;System.arraycopy(d,2,memory[active],(d[0]&255)*4,d.length-2);return new byte[0];
                case 4001:collisions[active]=d.clone();return new byte[0];
                case 4024:versions[active]=d.clone();return new byte[0];
                case 4020:magic[active]=d[0];return new byte[0];
                case 1006:enabled[(d[0]&255)*2]=d[2];return new byte[0];
                case 1001:mode=d[0];return new byte[0];
                case 1009:if(saveFailure)throw new IOException("flash failed");saves++;return new byte[0];
                case 4029:case 4032:case 4033:case 1007:return new byte[0];
                default:throw new IOException("unexpected command "+c);
            }
        }
    }
    public static void main(String[] args)throws Exception{
        byte[] raw=sample();byte[] original=raw.clone();Amiibo a=new Amiibo(raw);byte[] fixed=a.prepare(null);
        check(Arrays.equals(raw,original),"input unchanged");check(Amiibo.hex(Arrays.copyOfRange(fixed,532,538)).equals("07a494a08080"),"known Bokoblin password");
        check(Arrays.equals(Arrays.copyOf(fixed,532),Arrays.copyOf(raw,532)),"encrypted bytes unchanged");check(Arrays.equals(fixed,new Amiibo(fixed).prepare(null)),"idempotent fix");
        rejects(()->new Amiibo(new byte[539]),"reject size");byte[] bad=raw.clone();bad[3]^=1;rejects(()->new Amiibo(bad),"reject BCC0");byte[] bad2=raw.clone();bad2[8]^=1;rejects(()->new Amiibo(bad2),"reject BCC1");
        byte[] frame=Frame.encode(4022,0x68,fixed);Frame.Decoder decoder=new Frame.Decoder();List<Frame> decoded=new ArrayList<>();for(int p=0;p<frame.length;p+=7)decoded.addAll(decoder.accept(Arrays.copyOfRange(frame,p,Math.min(p+7,frame.length))));
        check(decoded.size()==1&&decoded.get(0).command==4022&&decoded.get(0).status==0x68&&Arrays.equals(decoded.get(0).data,fixed),"fragmented frame");
        byte[] pair=new byte[frame.length*2];System.arraycopy(frame,0,pair,0,frame.length);System.arraycopy(frame,0,pair,frame.length,frame.length);check(new Frame.Decoder().accept(pair).size()==2,"coalesced notifications");
        frame[frame.length-1]^=1;rejects(()->new Frame.Decoder().accept(frame),"reject checksum");
        check(Amiibo.hex(Frame.encode(1000,0,new byte[0])).equals("11ef03e8000000001500"),"protocol golden frame");
        for(int slot:new int[]{3,8}){Device d=new Device();byte[] untouched=d.memory[1].clone();new Chameleon(d).upload(slot,fixed,"test",(s,m)->{check(m.containsKey("slot.txt"),"backup metadata");if(slot==3)check(Arrays.equals(m.get("original.bin"),raw),"backup original bytes");d.backup=true;},s->{});check(d.active==slot-1&&d.mode==0&&d.saves==1,"slot and save");check(Arrays.equals(d.memory[slot-1],fixed),"all 135 pages written");check(Arrays.equals(untouched,d.memory[1]),"other slots unchanged");}
        Device noSpace=new Device();rejects(()->new Chameleon(noSpace).upload(3,fixed,"test",(s,m)->{throw new IOException("disk full");},s->{}),"backup failure");check(noSpace.writes==0,"no writes without backup");
        Device wrong=new Device();rejects(()->new Chameleon(wrong).upload(1,fixed,"test",(s,m)->wrong.backup=true,s->{}),"wrong card type");check(wrong.writes==0,"incompatible slot untouched");
        Device corrupt=new Device();corrupt.corrupt=true;rejects(()->new Chameleon(corrupt).upload(3,fixed,"test",(s,m)->corrupt.backup=true,s->{}),"readback mismatch");check(corrupt.enabled[4]==0,"failed slot disabled");
        Device flash=new Device();flash.saveFailure=true;rejects(()->new Chameleon(flash).upload(3,fixed,"test",(s,m)->flash.backup=true,s->{}),"flash failure");
        byte[] keys=syntheticKeys();byte[] plain=new byte[540];for(int i=0;i<520;i++)plain[i]=(byte)(i*7+11);plain[2]=0x0f;plain[3]=(byte)0xe0;plain[483]=2;
        byte[] encrypted=AmiiboCrypto.pack(keys,plain);if(args.length>0){Files.createDirectories(Path.of(args[0]));Files.write(Path.of(args[0],"synthetic-keys.bin"),keys);Files.write(Path.of(args[0],"synthetic-plain.bin"),plain);Files.write(Path.of(args[0],"synthetic-encrypted.bin"),encrypted);}
        byte[] unpacked=AmiiboCrypto.unpack(keys,encrypted);check(Arrays.equals(Arrays.copyOfRange(unpacked,44,436),Arrays.copyOfRange(plain,44,436)),"crypto round trip");
        byte[] tampered=encrypted.clone();tampered[100]^=1;rejects(()->AmiiboCrypto.unpack(keys,tampered),"reject modified Amiibo HMAC");
        byte[] template=new byte[540];template[2]=0xf;template[3]=(byte)0xe0;System.arraycopy(Amiibo.hexBytes("01000000034c0902"),0,template,476,8);Amiibo decrypted=new Amiibo(template);check(decrypted.decrypted,"recognizes decrypted template");rejects(()->decrypted.prepare(null),"decrypted needs keys");byte[] generated=decrypted.prepare(keys);check(Amiibo.validUid(generated),"generated UID valid");check(new Amiibo(generated).id.equals(decrypted.id),"decrypted identity preserved");
        byte[] malformed=keys.clone();malformed[31]=17;rejects(()->AmiiboCrypto.validateKeys(malformed),"invalid key structure");
        System.out.println("PASS: "+checks+" assertions; synthetic crypto fixtures exported.");
    }
    static byte[] syntheticKeys(){byte[] b=new byte[160];for(int i=0;i<160;i++)b[i]=(byte)(i*13+1);Arrays.fill(b,16,30,(byte)0);Arrays.fill(b,96,110,(byte)0);System.arraycopy("unfixed infos".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,16,13);System.arraycopy("locked secret".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,96,13);b[31]=8;b[111]=8;return b;}
}
