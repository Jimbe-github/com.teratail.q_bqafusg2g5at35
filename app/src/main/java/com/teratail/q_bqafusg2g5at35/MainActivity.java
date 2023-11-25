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
import java.util.function.Consumer;

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

    UsbManager manager = (UsbManager)getSystemService(Context.USB_SERVICE);

    UsbPermissionReceiver usbPermissionReceiver = new UsbPermissionReceiver(device -> showCardInfo(device, manager));
    PendingIntent pi = usbPermissionReceiver.getPendingIntent();
    getLifecycle().addObserver(usbPermissionReceiver);
    getLifecycle().addObserver(new UsbDeviceReceiver(() -> discoverDevices(manager, pi, textView)));

    discoverDevices(manager, pi, textView);
  }

  private void discoverDevices(UsbManager manager, PendingIntent pi, TextView textView) {
    textView.append("== discoverDevices ==\n");

    Map<String, UsbDevice> map = manager.getDeviceList();
    if(map == null) return;

    UsbDevice rcs380 = null;
    for(UsbDevice device : map.values()) {
      if(device.getVendorId() == 0x054c && device.getProductId() == 0x06c3) {
        rcs380 = device;
        break;
      }
    }
    if(rcs380 == null || manager.hasPermission(rcs380)) {
      showCardInfo(rcs380, manager);
    } else {
      manager.requestPermission(rcs380, pi);
    }
  }

  private void showCardInfo(UsbDevice device, UsbManager manager) {
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

  private class UsbPermissionReceiver extends BroadcastReceiver implements DefaultLifecycleObserver {
    private final String ACTION_USB_PERMISSION = UsbPermissionReceiver.class.getCanonicalName();
    private final Consumer<UsbDevice> listener;

    UsbPermissionReceiver(Consumer<UsbDevice> listener) {
      this.listener = listener;

      IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
      registerReceiver(this, filter);
    }
    PendingIntent getPendingIntent() {
      return PendingIntent.getBroadcast(MainActivity.this, 0,
              new Intent(ACTION_USB_PERMISSION),
              PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    @Override
    public void onReceive(Context context, Intent intent) {
      if(intent.getAction().equals(ACTION_USB_PERMISSION)) {
        if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
          UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
          listener.accept(device);
        } else {
          logger.error("Permission denied");
        }
      }
    }
    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
      unregisterReceiver(this);
    }
  }

  private class UsbDeviceReceiver extends BroadcastReceiver implements DefaultLifecycleObserver {
    private final Runnable action;

    UsbDeviceReceiver(Runnable action) {
      this.action = action;

      IntentFilter filter = new IntentFilter();
      filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
      filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
      registerReceiver(this, filter);
    }
    @Override
    public void onReceive(Context context, Intent intent) {
      switch(intent.getAction()) {
        case UsbManager.ACTION_USB_DEVICE_ATTACHED:
        case UsbManager.ACTION_USB_DEVICE_DETACHED:
          action.run();
          break;
      }
    }
    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
      unregisterReceiver(this);
    }
  }
}
