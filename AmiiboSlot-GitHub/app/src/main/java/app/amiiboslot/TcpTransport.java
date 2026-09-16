package app.amiiboslot;

import java.io.*;
import java.net.*;
import java.util.List;

/** MuMu/Android emulator uses adb reverse to reach the local USB serial bridge. */
public final class TcpTransport implements Chameleon.Link {
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private final Frame.Decoder decoder=new Frame.Decoder();
    private volatile boolean ready;
    @Override public boolean isReady(){return ready;}
    public void connect()throws Exception{
        try{
            socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",8765),5000);socket.setSoTimeout(10000);
            input=socket.getInputStream();output=socket.getOutputStream();ready=true;
        }catch(Exception e){close();throw new IOException("Не найден USB-мост на ПК. Запустите pc_bridge.py и настройте adb reverse. "+e.getMessage(),e);}
    }
    @Override public synchronized byte[] command(int command,byte[] data)throws Exception{
        if(!ready)throw new IOException("Нет связи с USB-мостом");
        try{
            output.write(Frame.encode(command,0,data));output.flush();byte[] b=new byte[512];
            while(true){int n=input.read(b);if(n<0)throw new EOFException("USB-мост отключился");List<Frame> received=decoder.accept(java.util.Arrays.copyOf(b,n));
                for(Frame response:received){if(response.command!=command)throw new IOException("Неожиданный ответ от Chameleon");
                    if(response.status!=0x68)throw new IOException("Chameleon отклонил команду "+command+": статус 0x"+Integer.toHexString(response.status));return response.data;}
            }
        }catch(Exception e){close();throw e;}
    }
    @Override public void close(){ready=false;try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;}
}
