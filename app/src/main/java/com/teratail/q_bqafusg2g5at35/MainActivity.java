package com.teratail.q_bqafusg2g5at35;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Bundle;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.*;

import java.util.*;
import java.util.function.*;

public class MainActivity extends AppCompatActivity {
  private Logger logger;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    TextView textView = findViewById(R.id.textView);
    textView.setText("start\n");
    logger = new Logger() {
      @Override
      public void log(String s) {
        textView.append("I:");
        textView.append(s);
        textView.append("\n");
      }
      @Override
      public void debug(String s) {
        textView.append("D:");
        textView.append(s);
        textView.append("\n");
      }
      @Override
      public void error(String s) {
        textView.append("E:");
        textView.append(s);
        textView.append("\n");
      }
    };

    new UsbDeviceManager(this, getLifecycle(),
            (vendarId, productId) -> vendarId == 0x054c && productId == 0x06c3,
            this::showCardInfo
    );
  }

  private void showCardInfo(UsbManager manager, UsbDevice device) {
    if(device == null) return;
    if(manager == null) throw new NullPointerException("manager");
    try(Device rcs380 = new Device(new Chipset(new Transport(manager, device), logger), logger)) {
      byte[] data = rcs380.sense_ttf("212F");
      if(data == null) {
        logger.log("!!no data!!");
      } else {
        String idm = Bytes.toString(data, 1, 1 + 8);
        String pmm = Bytes.toString(data, 9, 9 + 8);
        logger.log("IDm:" + idm);
        logger.log("PMm:" + pmm);
      }
    } catch(Exception e) {
      logger.error(Arrays.toString(e.getStackTrace()));
    }
  }
}

class UsbDeviceManager {
  private static final String ACTION_USB_PERMISSION = UsbDeviceManager.class.getCanonicalName();

  private final BiFunction<Integer,Integer,Boolean> selector;
  private final BiConsumer<UsbManager,UsbDevice> action;
  private final UsbManager manager;

  UsbDeviceManager(Context context, Lifecycle lifecycle, BiFunction<Integer,Integer,Boolean> selector, BiConsumer<UsbManager, UsbDevice> action) {
    this.selector = selector;
    this.action = action;
    this.manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);

    UsbBroadcastReceiver revceiver = new UsbBroadcastReceiver();
    lifecycle.addObserver(new DefaultLifecycleObserver() {
      @Override
      public void onDestroy(@NonNull LifecycleOwner owner) {
        context.unregisterReceiver(revceiver);
      }
    });
    context.registerReceiver(revceiver, revceiver.getIntentFilter());

    //USB接続済みかもしれない?
    discoverDevices(context);
  }

  void discoverDevices(Context context) {
    Map<String, UsbDevice> map = manager.getDeviceList();
    if(map != null) {
      for(UsbDevice device : map.values()) {
        if(selector.apply(device.getVendorId(), device.getProductId())) {
          if(manager.hasPermission(device)) {
            action.accept(manager, device); //有った&パーミッションも有る
          } else {
            manager.requestPermission(device, //パーミッションを得てから
                    PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
          }
          return;
        }
      }
    }
    action.accept(manager, null); //無かった
  }

  private class UsbBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      if(intent.getAction().equals(ACTION_USB_PERMISSION)) {
        action.accept(manager,
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) //パーミッションが得られた
                        : null); //得られなかった
        return;
      }
      //USB抜き差し
      switch(intent.getAction()) {
        case UsbManager.ACTION_USB_DEVICE_ATTACHED:
        case UsbManager.ACTION_USB_DEVICE_DETACHED:
          discoverDevices(context);
          break;
      }
    }

    IntentFilter getIntentFilter() {
      IntentFilter filter = new IntentFilter();
      filter.addAction(ACTION_USB_PERMISSION);
      filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
      filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
      return filter;
    }
  }
}
