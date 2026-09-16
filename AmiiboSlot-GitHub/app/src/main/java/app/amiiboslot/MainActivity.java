package app.amiiboslot;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.os.Environment;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Offline-first single screen. All BLE/file operations are serialized off the UI thread. */
public class MainActivity extends Activity {
    private static final int BG=0xff101713,CARD=0xff1c2720,FG=0xfff0f4ed,MUTED=0xffa9b7ad,GREEN=0xffb8f568;
    private static final int IMPORT=1,KEYS=2,EXPORT=3,EXPORT_BACKUP=4;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root,slotGrid;
    private TextView details,status,connection,fileLabel,identity,series,slotSummary,keysLabel;
    private Button importButton,exportButton,prepareButton,connectButton,uploadButton,keysButton,backupButton;
    private ProgressBar progress;
    private final ArrayList<Button> slots=new ArrayList<>();
    private Amiibo amiibo;private byte[] prepared,keys;private String fileName="amiibo.bin",amiiboName="Amiibo";
    private JSONObject catalog;private volatile Chameleon.Link link;
    private boolean busy,scanning;private int selectedSlot=3;private int[] slotTypes;
    private BluetoothLeScanner scanner;private ScanCallback scanCallback;private AlertDialog scanDialog;
    private final ArrayList<BluetoothDevice> devices=new ArrayList<>();
    private ArrayAdapter<String> scanAdapter;
    private File exportBackup;
    private byte[] pendingExport;
    private String finalStatus="Выберите BIN. Подключение нужно только для записи в устройство.";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{catalog=new JSONObject(new String(readLimited(getAssets().open("amiibo.json"),2_000_000),StandardCharsets.UTF_8));}
        catch(Exception e){catalog=new JSONObject();}
        try{keys=readLimited(getAssets().open("key_retail.bin"),160);AmiiboCrypto.validateKeys(keys);}catch(Exception e){keys=null;}
        if(state!=null){selectedSlot=state.getInt("slot",3);fileName=state.getString("name","amiibo.bin");byte[] saved=state.getByteArray("original");if(saved!=null)try{amiibo=new Amiibo(saved);prepared=state.getByteArray("prepared");}catch(Exception ignored){}}
        build();renderFile();updateControls();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(20));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    private void title(TextView t){t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(18),dp(16),dp(18),dp(16));v.setBackground(bg(CARD));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(14);root.addView(v,lp);return v;}
    private Button button(String name,boolean primary,LinearLayout parent){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?BG:FG);b.setBackground(bg(primary?GREEN:0xff304037));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.topMargin=dp(10);parent.addView(b,lp);return b;}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(28));scroll.addView(root);setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(20),dp(20)+insets.getSystemWindowInsetTop(),dp(20),dp(28)+insets.getSystemWindowInsetBottom());return insets;});root.requestApplyInsets();
        TextView badge=text("AMIIBO  /  CHAMELEON ULTRA",11,GREEN);badge.setLetterSpacing(.14f);root.addView(badge);
        TextView heading=text("Твой персонаж.\nТвой слот.",32,FG);title(heading);root.addView(heading);
        root.addView(text("Проверь BIN, подготовь его и отправь в Chameleon.",14,MUTED));
        LinearLayout fileCard=card();fileCard.addView(text("01   ФАЙЛ AMIIBO",11,GREEN));
        identity=text("Выбери персонажа",25,FG);title(identity);fileCard.addView(identity);
        series=text("Импорт .bin • определение по ID",14,MUTED);fileCard.addView(series);
        fileLabel=text("",12,MUTED);fileCard.addView(fileLabel);
        details=text("Обычные BIN обрабатываются без ключей и без интернета.",13,FG);details.setTextIsSelectable(true);fileCard.addView(details);
        importButton=button("Открыть BIN",true,fileCard);importButton.setOnClickListener(v->pick(IMPORT));
        prepareButton=button("Исправить и подготовить",false,fileCard);prepareButton.setOnClickListener(v->{if(amiibo!=null&&amiibo.decrypted&&keys==null){new AlertDialog.Builder(this).setTitle("Нужен ключ для Decrypted BIN").setMessage("Этот файл содержит только ID персонажа и не содержит готовых данных карты. Выберите свой key_retail.bin (160 байт). Приложение создаст зашифрованный BIN, назначит UID и исправит PWD/PACK. Ключ остаётся только в памяти приложения.").setNegativeButton("Отмена",null).setPositiveButton("Выбрать ключ",(d,w)->pick(KEYS)).show();}else prepare();});
        exportButton=button("Экспорт исправленного BIN",false,fileCard);exportButton.setOnClickListener(v->{pendingExport=prepared.clone();String name=safeName(amiiboName)+"-"+System.currentTimeMillis()+".bin";if(Build.VERSION.SDK_INT>=29)runWork(()->saveToDownloads(name,"application/octet-stream",pendingExport));else createDocument(EXPORT,name,"application/octet-stream");});
        LinearLayout device=card();device.addView(text("02   CHAMELEON ULTRA",11,GREEN));
        connection=text("Устройство не подключено",18,FG);title(connection);device.addView(connection);
        device.addView(text("Bluetooth или USB-мост MuMu · NTAG215 · слоты 1–8",13,MUTED));
        connectButton=button("Найти Chameleon по Bluetooth",false,device);connectButton.setOnClickListener(v->{if(link!=null&&link.isReady()){link.close();link=null;slotTypes=null;connection.setText("Устройство отключено");updateControls();}else requestScan();});
        Button pcButton=button("Подключить через ПК / MuMu",false,device);pcButton.setOnClickListener(v->connectPc());
        slotGrid=new LinearLayout(this);slotGrid.setOrientation(LinearLayout.VERTICAL);device.addView(slotGrid);
        for(int row=0;row<2;row++){LinearLayout line=new LinearLayout(this);slotGrid.addView(line);for(int col=0;col<4;col++){int slot=row*4+col+1;Button b=new Button(this);b.setText(String.valueOf(slot));b.setTextSize(18);b.setAllCaps(false);b.setContentDescription("Слот "+slot);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),dp(10),dp(3),0);line.addView(b,lp);b.setOnClickListener(v->{selectedSlot=slot;renderSlots();});slots.add(b);}}
        slotSummary=text("Выбран слот 3",13,MUTED);device.addView(slotSummary);
        uploadButton=button("Записать в слот 3",true,device);uploadButton.setOnClickListener(v->confirmUpload());
        device.addView(text("PWD / PACK + UID + GET_VERSION\nРезервная копия перед записью. Проверка после сохранения.",12,MUTED));
        LinearLayout activity=card();activity.addView(text("СТАТУС",11,GREEN));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);activity.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        status=text(finalStatus,14,FG);status.setTextIsSelectable(true);activity.addView(status);
        LinearLayout tools=card();tools.addView(text("ФАЙЛЫ И КЛЮЧИ",11,GREEN));
        keysLabel=text(keys==null?"Ключ Amiibo недоступен — импортируйте key_retail.bin.":"✓ Ключ Amiibo встроен. Decrypted BIN можно исправлять сразу.",12,MUTED);tools.addView(keysLabel);
        keysButton=button("Импорт ключей для Decrypted BIN",false,tools);keysButton.setOnClickListener(v->pick(KEYS));
        backupButton=button("Резервные копии слотов",false,tools);backupButton.setOnClickListener(v->showBackups());
        Button about=button("Как пользоваться",false,tools);about.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Amiibo Slot 1.0").setMessage("1. Откройте BIN — подготовка выполняется автоматически.\n2. Экспортируйте BIN или подключите Chameleon.\n3. Выберите слот и запишите.\n\nДля MuMu: запустите start-mumu-bridge.ps1 на ПК, оставьте окно открытым и нажмите «Подключить через ПК / MuMu». Chameleon должен быть подключён к COM6. Для телефона используйте Bluetooth.\n\nBIN не содержит GET_VERSION. Прямая запись выставляет его отдельно. Для Chameleon GUI настройте NTAG215 и GET_VERSION 0004040201001103.\n\nЗакройте другие приложения, подключённые к Chameleon. При запросе Android выполните сопряжение с PIN вашего устройства. На Android 8–11 для поиска BLE нужна включённая геолокация.\n\nПосле обрыва связи резервная копия доступна в разделе файлов. Не считайте незавершённую запись успешной.\n\nОфлайн-каталог: AmiiboAPI, MIT. Криптография: алгоритмы amiitool, MIT. В эту личную сборку встроен key_retail.bin с вашего компьютера. Не распространяйте APK: ключ можно извлечь из файла приложения. Игровые дампы в APK не включены.").setPositiveButton("Понятно",null).show());
        root.addView(text("Офлайн по умолчанию. Ваши файлы остаются у вас.",11,MUTED));
    }
    private void renderFile(){
        if(amiibo==null)return;
        String name="Неизвестный Amiibo",family="Неизвестная серия";
        try{JSONObject a=catalog.getJSONObject("amiibos").optJSONObject("0x"+amiibo.id);if(a!=null)name=a.optString("name",name);family=catalog.getJSONObject("amiibo_series").optString("0x"+amiibo.id.substring(12,14),family);}catch(Exception ignored){}
        amiiboName=name;identity.setText(name);series.setText(family);fileLabel.setText(fileName);
        StringBuilder s=new StringBuilder("ID  "+amiibo.id+"\n540 байт · "+(amiibo.decrypted?"Decrypted / внутренний формат":"NTAG215 / обычный дамп"));
        if(prepared!=null){s.append("\nUID  ").append(Amiibo.hex(Amiibo.uid(prepared))).append("\nPWD  ").append(Amiibo.hex(Arrays.copyOfRange(prepared,532,536))).append("   PACK  8080\n✓ Готов к экспорту и записи");if(!amiibo.decrypted)s.append("\nКриптоподпись исходника не проверялась.");}
        else if(amiibo.decrypted){int nonzero=0;for(byte b:amiibo.original)if(b!=0)nonzero++;s.append("\nШаблон: ").append(nonzero).append(" заполненных байт из 540. Для подготовки нужен key_retail.bin. Приложение создаст зашифрованный дамп и назначит UID.");}
        else s.append("\nUID  ").append(Amiibo.hex(Amiibo.uid(amiibo.original))).append("\nPWD  ").append(Amiibo.hex(Arrays.copyOfRange(amiibo.original,532,536))).append("   PACK  ").append(Amiibo.hex(Arrays.copyOfRange(amiibo.original,536,538)));
        details.setText(s.toString());
    }
    private void renderSlots(){
        for(int i=0;i<slots.size();i++){Button b=slots.get(i);b.setBackground(bg(i+1==selectedSlot?GREEN:0xff304037));b.setTextColor(i+1==selectedSlot?BG:FG);b.setEnabled(!busy&&!scanning);}
        String occupancy=slotTypes==null?"подключите устройство для проверки":slotTypes[selectedSlot-1]==0?"пустой":slotTypes[selectedSlot-1]==1101?"NTAG215 — будет заменён":"другой тип карты — запись запрещена";
        slotSummary.setText("Слот "+selectedSlot+" · "+occupancy);uploadButton.setText("Записать в слот "+selectedSlot);
        uploadButton.setEnabled(!busy&&!scanning&&prepared!=null&&link!=null&&link.isReady()&&slotTypes!=null&&(slotTypes[selectedSlot-1]==0||slotTypes[selectedSlot-1]==1101));
    }
    private void updateControls(){
        boolean idle=!busy&&!scanning;importButton.setEnabled(idle);prepareButton.setEnabled(idle&&amiibo!=null);prepareButton.setText(amiibo!=null&&amiibo.decrypted&&keys==null?"Выбрать ключ и исправить":"Исправить и подготовить");exportButton.setEnabled(idle&&prepared!=null);connectButton.setEnabled(idle);keysButton.setEnabled(idle);backupButton.setEnabled(idle);
        connectButton.setText(link!=null&&link.isReady()?"Отключить":"Найти Chameleon по Bluetooth");progress.setVisibility(busy||scanning?View.VISIBLE:View.GONE);renderSlots();
    }
    private interface Work{void run()throws Exception;}
    private void runWork(Work action){
        busy=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateControls();
        worker.execute(()->{try{action.run();}catch(Exception e){report("Ошибка: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}finally{ui(()->{busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(link!=null&&!link.isReady()){connection.setText("Связь отсутствует. Подключитесь заново.");slotTypes=null;}updateControls();});}});
    }
    private void ui(Runnable action){main.post(()->{if(!isFinishing()&&!isDestroyed())action.run();});}
    private void report(String message){ui(()->{finalStatus=message;status.setText(message);});}
    private void prepare(){final Amiibo input=amiibo;final byte[] importedKeys=keys;runWork(()->{byte[] result=input.prepare(importedKeys);ui(()->{prepared=result;renderFile();});report("Готово: PWD вычислен по UID, PACK = 8080. Исходный файл сохранён без изменений.");});}
    private void pick(int code){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,code);}
    private void createDocument(int code,String name,String type){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,code);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==EXPORT||request==EXPORT_BACKUP){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(SecurityException ignored){}}
        runWork(()->{
            if(request==IMPORT){byte[] bytes=readLimited(getContentResolver().openInputStream(uri),4096);Amiibo a=new Amiibo(bytes);byte[] converted=(keys!=null||!a.decrypted)?a.prepare(keys):null;String name=displayName(uri);ui(()->{amiibo=a;prepared=converted;fileName=name;renderFile();updateControls();});report(converted==null?"Распознан Decrypted BIN. Нажмите «Выбрать ключ и исправить».":a.decrypted?"Decrypted BIN автоматически зашифрован; криптоподпись проверена. Можно экспортировать или записать в слот.":"BIN подготовлен автоматически: UID и PWD/PACK исправлены. Можно экспортировать или записать в слот.");}
            else if(request==KEYS){byte[] bytes=readLimited(getContentResolver().openInputStream(uri),160);AmiiboCrypto.validateKeys(bytes);Amiibo current=amiibo;byte[] converted=current!=null&&current.decrypted?current.prepare(bytes):null;ui(()->{if(keys!=null)Arrays.fill(keys,(byte)0);keys=bytes;keysLabel.setText("✓ Ключи импортированы в память. Decrypted BIN подготовлен.");if(converted!=null)prepared=converted;renderFile();updateControls();});report(converted!=null?"Готово: Decrypted BIN зашифрован, криптоподпись проверена, PWD/PACK исправлены.":"Ключи импортированы. На диск не записывались.");}
            else if(request==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Не удалось открыть файл");out.write(pendingExport);}report("Исправленный BIN экспортирован. GET_VERSION задаётся при записи в Chameleon.");}
            else if(request==EXPORT_BACKUP){try(InputStream in=new FileInputStream(exportBackup);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Не удалось создать архив");copy(in,out);}report("Резервная копия экспортирована.");}
        });
    }
    private String displayName(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "amiibo.bin";}
    static byte[] readLimited(InputStream stream,int limit)throws IOException {if(stream==null)throw new IOException("Файл недоступен");try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Файл слишком большой (максимум "+limit+" байт)");out.write(buffer,0,n);}return out.toByteArray();}}
    static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    private static String safeName(String name){return name.replaceAll("[^a-zA-Z0-9а-яА-Я _-]","_");}
    @android.annotation.TargetApi(29) private void saveToDownloads(String name,String mime,byte[] bytes)throws Exception{
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/AmiiboSlot");values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("Не удалось создать файл в Загрузках");
        try{
            try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("Не удалось открыть файл для записи");out.write(bytes);out.flush();}
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,values,null,null);
            report("Экспорт готов: Загрузки/AmiiboSlot/"+name);
        }catch(Exception ex){try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}throw ex;}
    }

    private void requestScan(){
        String[] perms=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for(String p:perms)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){requestPermissions(perms,22);return;}
        startScan();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==22){boolean ok=grants.length>0;for(int g:grants)ok&=g==PackageManager.PERMISSION_GRANTED;if(ok)startScan();else report("Для подключения разрешите доступ к Bluetooth / устройствам поблизости в настройках Android.");}}
    @android.annotation.SuppressLint("MissingPermission") private void startScan(){
        BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null||!adapter.isEnabled()){report("Включите Bluetooth на телефоне и повторите поиск.");return;}
        if(Build.VERSION.SDK_INT<31){android.location.LocationManager lm=getSystemService(android.location.LocationManager.class);if(lm!=null&&!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)&&!lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)){report("Android 8–11 требует включённую геолокацию для поиска BLE. Включите её и повторите.");return;}}
        if(link!=null){link.close();link=null;}scanner=adapter.getBluetoothLeScanner();if(scanner==null){report("Bluetooth LE недоступен");return;}
        devices.clear();scanAdapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());
        scanDialog=new AlertDialog.Builder(this).setTitle("Поиск Chameleon…").setAdapter(scanAdapter,(d,position)->{BluetoothDevice found=devices.get(position);stopScan();connect(found);}).setNegativeButton("Закрыть",(d,w)->stopScan()).create();scanDialog.setOnCancelListener(d->stopScan());scanDialog.show();
        scanCallback=new ScanCallback(){@Override public void onScanResult(int type,ScanResult r){
            String name=r.getDevice().getName();if(name==null&&r.getScanRecord()!=null)name=r.getScanRecord().getDeviceName();
            boolean nus=r.getScanRecord()!=null&&r.getScanRecord().getServiceUuids()!=null&&r.getScanRecord().getServiceUuids().contains(new ParcelUuid(BleTransport.SERVICE));
            if(!nus&&(name==null||!name.toLowerCase(Locale.ROOT).contains("chameleon")))return;
            final String label=(name==null?"Chameleon UART":name)+"\n"+r.getDevice().getAddress()+" · "+r.getRssi()+" dBm";
            ui(()->{if(scanning&&!devices.contains(r.getDevice())){devices.add(r.getDevice());scanAdapter.add(label);}});
        }@Override public void onScanFailed(int error){ui(()->{stopScan();report("Не удалось начать поиск BLE: "+error);});}};
        scanning=true;updateControls();
        try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);}catch(Exception e){stopScan();report(e.getMessage());}
        main.postDelayed(()->{if(scanning){stopScan();if(scanDialog!=null&&scanDialog.isShowing())scanDialog.setTitle(devices.isEmpty()?"Не найден. Разбудите Chameleon и повторите.":"Выберите Chameleon");}},12000);
    }
    @android.annotation.SuppressLint("MissingPermission") private void stopScan(){if(scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}scanning=false;updateControls();}
    @android.annotation.SuppressLint("MissingPermission") private void connect(BluetoothDevice device){
        connection.setText("Подключение…");report("Подключаюсь. Если Android запросит PIN, используйте PIN вашего Chameleon.");
        runWork(()->{BleTransport transport=new BleTransport();link=transport;transport.connect(getApplicationContext(),device);byte[] version=transport.command(1000,new byte[0]);int[] types=new Chameleon(transport).slots();ui(()->{slotTypes=types;connection.setText("Подключено · "+device.getName()+"\nПрошивка "+(version.length>=2?(version[0]&255)+"."+(version[1]&255):Amiibo.hex(version)));});report("Chameleon готов. Выберите слот для записи.");});
    }
    private void connectPc(){
        if(link!=null){link.close();link=null;}connection.setText("Подключаюсь через ПК…");
        runWork(()->{TcpTransport transport=new TcpTransport();link=transport;transport.connect();byte[] version=transport.command(1000,new byte[0]);int[] types=new Chameleon(transport).slots();ui(()->{slotTypes=types;connection.setText("Подключено через ПК · прошивка "+(version.length>=2?(version[0]&255)+"."+(version[1]&255):Amiibo.hex(version)));});report("Chameleon через USB-мост готов. Выберите слот.");});
    }
    private void confirmUpload(){
        final int slot=selectedSlot;final byte[] data=prepared.clone();final String name=amiiboName;final Chameleon.Link transport=link;
        new AlertDialog.Builder(this).setTitle("Записать в слот "+slot+"?").setMessage(name+"\n\nТекущее содержимое этого слота будет заменено. Перед записью будет сохранена резервная копия.").setNegativeButton("Отмена",null).setPositiveButton("Записать",(d,w)->runWork(()->{new Chameleon(transport).upload(slot,data,name,this::saveBackup,this::report);int[] types=new Chameleon(transport).slots();ui(()->slotTypes=types);})).show();
    }
    private File backupDir(){File dir=new File(getFilesDir(),"backups");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Не удалось создать папку резервных копий");return dir;}
    private void saveBackup(int slot,Map<String,byte[]> entries)throws Exception{
        File dest=new File(backupDir(),"slot-"+slot+"-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date())+".zip");
        File temp=new File(dest.getPath()+".tmp");try(FileOutputStream file=new FileOutputStream(temp);ZipOutputStream zip=new ZipOutputStream(file)){
            for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}
            zip.finish();file.getFD().sync();
        }if(!temp.renameTo(dest))throw new IOException("Не удалось сохранить резервную копию");
    }
    private void showBackups(){
        File[] found=backupDir().listFiles((d,n)->n.endsWith(".zip"));if(found==null||found.length==0){report("Пока нет резервных копий. Они создаются перед записью слота.");return;}
        Arrays.sort(found,(a,b)->b.getName().compareTo(a.getName()));String[] names=new String[found.length];for(int i=0;i<names.length;i++)names[i]=found[i].getName();
        new AlertDialog.Builder(this).setTitle("Резервные копии").setItems(names,(d,which)->{File f=found[which];new AlertDialog.Builder(this).setTitle(f.getName()).setItems(new String[]{"Открыть исходный BIN для восстановления","Экспортировать ZIP с настройками"},(dialog,action)->{
            if(action==1){exportBackup=f;if(Build.VERSION.SDK_INT>=29)runWork(()->saveToDownloads(f.getName(),"application/zip",readLimited(new FileInputStream(f),2_000_000)));else createDocument(EXPORT_BACKUP,f.getName(),"application/zip");}
            else runWork(()->{byte[] bytes=null;try(ZipFile zip=new ZipFile(f)){ZipEntry e=zip.getEntry("original.bin");if(e!=null)bytes=readLimited(zip.getInputStream(e),540);}if(bytes==null)throw new IOException("Слот был пустым — BIN в этой копии отсутствует.");Amiibo a=new Amiibo(bytes);ui(()->{amiibo=a;prepared=null;fileName=f.getName()+" / original.bin";renderFile();});report("Исходный BIN открыт. Подготовьте его и запишите в нужный слот. Параметры будут настроены для Amiibo.");});
        }).show();}).setNegativeButton("Закрыть",null).show();
    }
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("slot",selectedSlot);out.putString("name",fileName);if(amiibo!=null)out.putByteArray("original",amiibo.original);if(prepared!=null)out.putByteArray("prepared",prepared);}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"Дождитесь завершения операции",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    @Override public void onDestroy(){if(scanner!=null)stopScan();if(link!=null)link.close();if(keys!=null)Arrays.fill(keys,(byte)0);worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}

