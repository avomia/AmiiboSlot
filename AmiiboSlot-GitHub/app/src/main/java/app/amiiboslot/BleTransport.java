package app.amiiboslot;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;

/** All calls are made by ONE worker; GATT writes await onCharacteristicWrite. */
@SuppressLint("MissingPermission")
public final class BleTransport implements Chameleon.Link {
    static final UUID SERVICE=UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX=UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),TX=UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final BlockingQueue<String> events=new LinkedBlockingQueue<>();
    private final BlockingQueue<Frame> responses=new LinkedBlockingQueue<>();
    private final Frame.Decoder decoder=new Frame.Decoder();
    private volatile IOException failure;
    private BluetoothGatt gatt;private BluetoothGattCharacteristic rx;
    public volatile boolean ready;
    @Override public boolean isReady(){return ready;}
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("Ошибка Bluetooth "+status+". Закройте другое приложение Chameleon и подключитесь заново.");return;}
            if(state==BluetoothProfile.STATE_CONNECTED)events.offer("connected");
            else if(state==BluetoothProfile.STATE_DISCONNECTED)fail("Chameleon отключился");
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){event("services",status);}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){event("notify",status);}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){event("write",status);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){receive(c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){receive(value);}
    };
    private void event(String event,int status){if(status==0)events.offer(event);else fail("Ошибка GATT "+status+". При запросе Android подтвердите сопряжение и повторите подключение.");}
    private void receive(byte[] data){try{if(data!=null)responses.addAll(decoder.accept(data));}catch(IOException e){fail(e.getMessage());}}
    private void fail(String message){ready=false;failure=new IOException(message);events.offer("error");responses.offer(new Frame(-1,0,new byte[0]));}
    private void await(String kind)throws Exception{
        String got=events.poll(25,TimeUnit.SECONDS);if(failure!=null)throw failure;
        if(!kind.equals(got))throw new IOException("Тайм-аут Bluetooth: "+kind+". Переподключите устройство.");
    }
    public void connect(Context context,BluetoothDevice device)throws Exception{
        try{
            gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null)throw new IOException("Не удалось открыть Bluetooth");await("connected");
            if(!gatt.discoverServices())throw new IOException("Не удалось запросить службы");await("services");
            BluetoothGattService service=gatt.getService(SERVICE);if(service==null)throw new IOException("Устройство не предоставляет Chameleon UART");
            rx=service.getCharacteristic(RX);BluetoothGattCharacteristic tx=service.getCharacteristic(TX);
            if(rx==null||tx==null)throw new IOException("Отсутствуют UART-характеристики");
            if(!gatt.setCharacteristicNotification(tx,true))throw new IOException("Не удалось включить уведомления");
            BluetoothGattDescriptor ccc=tx.getDescriptor(CCC);if(ccc==null)throw new IOException("Нет CCC-дескриптора");
            ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if(!gatt.writeDescriptor(ccc))throw new IOException("Bluetooth занят при подписке");await("notify");
            ready=true;
        }catch(Exception e){close();throw e;}
    }
    @Override public synchronized byte[] command(int command,byte[] data)throws Exception{
        if(!ready)throw new IOException("Сначала подключите Chameleon");
        if(!responses.isEmpty())throw new IOException("Неожиданный ответ. Подключитесь заново.");
        try{
            byte[] packet=Frame.encode(command,0,data);
            // 20 works with the default ATT MTU (23), no MTU negotiation race.
            for(int offset=0;offset<packet.length;offset+=20){
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);rx.setValue(Arrays.copyOfRange(packet,offset,Math.min(offset+20,packet.length)));
                if(!gatt.writeCharacteristic(rx))throw new IOException("Bluetooth занят при записи");await("write");
            }
            Frame response=responses.poll(10,TimeUnit.SECONDS);
            if(failure!=null)throw failure;
            if(response==null || response.command!=command)throw new IOException("Нет ожидаемого ответа на команду "+command);
            if(response.status!=0x68)throw new IOException("Chameleon отклонил команду "+command+": статус 0x"+Integer.toHexString(response.status));
            return response.data;
        }catch(Exception e){close();throw e;}
    }
    @Override public void close(){ready=false;if(gatt!=null){try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;}}
}
