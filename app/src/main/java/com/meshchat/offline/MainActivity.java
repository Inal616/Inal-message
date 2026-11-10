package com.meshchat.offline;
import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
 private static final UUID CHAT_UUID = UUID.fromString("704e8cc3-c492-458e-bb8e-487ac5218b1a");
 private BluetoothAdapter bluetooth;
 private volatile BluetoothSocket socket;
 private volatile DataOutputStream output;
 private volatile boolean alive = true;
 private final Object writeLock = new Object();
 private LinearLayout root, messages, deviceList;
 private ScrollView scroll;
 private EditText name, phone, message;
 private TextView status;
 private SharedPreferences prefs;
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  prefs = getSharedPreferences("profile", MODE_PRIVATE);
  BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
  bluetooth = manager == null ? null : manager.getAdapter();
  root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,20,24,16); root.setBackgroundColor(Color.rgb(247,249,252)); setContentView(root);
  title("MeshChat 0.1 • без интернета");
  status = new TextView(this); status.setText("Не подключено"); root.addView(status);
  name = field("Имя",prefs.getString("name",""));
  phone = field("Номер телефона (+7...)",prefs.getString("phone",""));
  button("Сохранить профиль",v->saveProfile());
  button("Разрешить Bluetooth / включить",v->permissionsAndEnable());
  button("Показать сопряжённые телефоны",v->refreshDevices());
  deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); root.addView(deviceList);
  title("Сообщения");
  scroll = new ScrollView(this); messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); scroll.addView(messages);
  root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  message = field("Сообщение","");
  button("Отправить",v->sendMessage());
  text("Сначала сопрягите телефоны через настройки Android. На обоих откройте MeshChat. На одном выберите второй телефон из списка. Номера не подтверждаются через SMS.");
  permissionsAndEnable();
 }
 private void title(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(22);t.setTextColor(Color.rgb(20,40,70));t.setPadding(0,12,0,8);root.addView(t);}
 private void text(String s){TextView t=new TextView(this);t.setText(s);t.setPadding(0,7,0,7);root.addView(t);}
 private EditText field(String hint,String value){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setText(value);root.addView(e);return e;}
 private void button(String label,View.OnClickListener click){Button b=new Button(this);b.setText(label);b.setOnClickListener(click);root.addView(b);}
 private void saveProfile(){String p=phone.getText().toString().trim();String n=name.getText().toString().trim();if(n.isEmpty()||!p.matches("\\+[0-9]{7,15}")){status.setText("Введите имя и номер в формате +7940...");return;}prefs.edit().putString("name",n).putString("phone",p).apply();status.setText("Профиль сохранён (номер не подтверждён)");}
 private boolean permitted(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
 private void permissionsAndEnable(){
  if(bluetooth==null){status.setText("Bluetooth на устройстве отсутствует");return;}
  if(!permitted()){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},33);return;}
  if(!bluetooth.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),5);return;}
  startServer();
 }
 @Override public void onRequestPermissionsResult(int code,String[] p,int[] results){super.onRequestPermissionsResult(code,p,results);if(code==33&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)permissionsAndEnable();}
 @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==5&&res==RESULT_OK)startServer();}
 private void refreshDevices(){deviceList.removeAllViews();if(!permitted()||bluetooth==null||!bluetooth.isEnabled()){status.setText("Разрешите и включите Bluetooth");return;}
  Set<BluetoothDevice> devices=bluetooth.getBondedDevices();if(devices.isEmpty()){status.setText("Нет сопряжённых устройств. Сопрягите телефоны в настройках Android");return;}
  for(BluetoothDevice d:devices){Button b=new Button(this);String label=d.getName()==null?d.getAddress():d.getName();b.setText("Подключиться: "+label);b.setOnClickListener(v->connect(d));deviceList.addView(b);}
 }
 private void startServer(){if(!permitted())return;new Thread(()->{try(BluetoothServerSocket server=bluetooth.listenUsingRfcommWithServiceRecord("MeshChat",CHAT_UUID)){
  while(alive){BluetoothSocket incoming=server.accept();attach(incoming);}
 }catch(Exception e){if(alive)state("Ожидание соединения: "+e.getMessage());}}).start();}
 private void connect(BluetoothDevice device){state("Подключение...");new Thread(()->{try{BluetoothSocket s=device.createRfcommSocketToServiceRecord(CHAT_UUID);s.connect();attach(s);}catch(Exception e){state("Ошибка соединения: "+e.getMessage());}}).start();}
 private void attach(BluetoothSocket s){try{BluetoothSocket old=socket;socket=s;if(old!=null&&old!=s)old.close();output=new DataOutputStream(s.getOutputStream());state("Подключено: "+s.getRemoteDevice().getAddress());
  DataInputStream input=new DataInputStream(s.getInputStream());while(alive&&s==socket){int size=input.readInt();if(size<=0||size>65536)throw new IOException("Bad message length");byte[] data=new byte[size];input.readFully(data);display("Собеседник: "+new String(data,StandardCharsets.UTF_8));}
 }catch(Exception e){state("Соединение прервано: "+e.getMessage());}finally{if(socket==s){socket=null;output=null;}try{s.close();}catch(Exception ignored){}}}
 private void sendMessage(){String txt=message.getText().toString().trim();if(txt.isEmpty())return;if(output==null){state("Сначала подключитесь к телефону");return;}
  String from=prefs.getString("name","Гость");String phoneId=prefs.getString("phone","");String full=from+" ("+phoneId+"): "+txt;byte[] bytes=full.getBytes(StandardCharsets.UTF_8);
  new Thread(()->{try{synchronized(writeLock){DataOutputStream out=output;if(out==null)throw new IOException("Нет соединения");out.writeInt(bytes.length);out.write(bytes);out.flush();}runOnUiThread(()->{display("Вы: "+txt);message.setText("");});}catch(Exception e){state("Ошибка отправки: "+e.getMessage());}}).start();}
 private void display(String s){runOnUiThread(()->{TextView t=new TextView(this);t.setText(s);t.setTextSize(16);t.setPadding(8,10,8,10);messages.addView(t);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));});}
 private void state(String s){runOnUiThread(()->status.setText(s));}
 @Override protected void onDestroy(){alive=false;try{if(socket!=null)socket.close();}catch(Exception ignored){}super.onDestroy();}
}
