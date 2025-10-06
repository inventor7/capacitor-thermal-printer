package com.malik12tree.bluetooth_print;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.rt.printerlibrary.bean.BluetoothEdrConfigBean;
import com.rt.printerlibrary.cmd.Cmd;
import com.rt.printerlibrary.cmd.EscCmd;
import com.rt.printerlibrary.cmd.EscFactory;
import com.rt.printerlibrary.connect.PrinterInterface;
import com.rt.printerlibrary.enumerate.BarcodeStringPosition;
import com.rt.printerlibrary.enumerate.BarcodeType;
import com.rt.printerlibrary.enumerate.BmpPrintMode;
import com.rt.printerlibrary.enumerate.CommonEnum;
import com.rt.printerlibrary.enumerate.ConnectStateEnum;
import com.rt.printerlibrary.enumerate.ESCBarcodeFontTypeEnum;
import com.rt.printerlibrary.enumerate.ESCFontTypeEnum;
import com.rt.printerlibrary.enumerate.SettingEnum;
import com.rt.printerlibrary.exception.SdkException;
import com.rt.printerlibrary.factory.cmd.CmdFactory;
import com.rt.printerlibrary.factory.connect.BluetoothFactory;
import com.rt.printerlibrary.factory.connect.PIFactory;
import com.rt.printerlibrary.factory.printer.ThermalPrinterFactory;
import com.rt.printerlibrary.observer.PrinterObserver;
import com.rt.printerlibrary.observer.PrinterObserverManager;
import com.rt.printerlibrary.printer.RTPrinter;
import com.rt.printerlibrary.setting.BarcodeSetting;
import com.rt.printerlibrary.setting.BitmapSetting;
import com.rt.printerlibrary.setting.TextSetting;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.json.JSONException;

@CapacitorPlugin(
    name = "CapacitorThermalPrinter",
    permissions = {
        @Permission(strings = { Manifest.permission.ACCESS_COARSE_LOCATION }, alias = "ACCESS_COARSE_LOCATION"),
        @Permission(strings = { Manifest.permission.ACCESS_FINE_LOCATION }, alias = "ACCESS_FINE_LOCATION"),
        @Permission(strings = { Manifest.permission.BLUETOOTH }, alias = "BLUETOOTH"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_ADMIN }, alias = "BLUETOOTH_ADMIN"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_SCAN }, alias = "BLUETOOTH_SCAN"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_CONNECT }, alias = "BLUETOOTH_CONNECT")
    }
)
public class CapacitorThermalPrinterPlugin extends Plugin implements PrinterObserver {

    private static final String TAG = "CapacitorThermalPrinterPlugin";
    static final List<String> alignments = Arrays.asList("left", "center", "right");
    static final List<String> fonts = Arrays.asList("A", "B");
    static final List<String> placements = Arrays.asList("none", "above", "below", "both");
    static final ESCFontTypeEnum[] fontEnumValues = ESCFontTypeEnum.values();
    static final ESCBarcodeFontTypeEnum[] dataFontEnumValues = ESCBarcodeFontTypeEnum.values();
    static final BarcodeStringPosition[] placementEnumValues = BarcodeStringPosition.values();

    BluetoothManager bluetoothManager = null;
    BluetoothAdapter mBluetoothAdapter = null;
    ArrayList<String> bluetoothPermissions = new ArrayList<>();

    ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private RTPrinter rtPrinter = null;
    BluetoothEdrConfigBean bluetoothEdrConfigBean = null;
    BroadcastReceiver mBluetoothReceiver = null;
    boolean mRegistered = false;

    Cmd cmd = new EscCmd();
    TextSetting textSetting = new TextSetting();
    BitmapSetting bitmapSetting = new BitmapSetting();
    BarcodeSetting dataCodeSetting = new BarcodeSetting();

    private class BluetoothDeviceReceiver extends BroadcastReceiver {

        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (device == null) return;
                int devType = device.getBluetoothClass().getMajorDeviceClass();
                if (devType != BluetoothClass.Device.Major.IMAGING) {
                    return;
                }

                if (!devices.contains(device)) {
                    devices.add(device);
                }

                CapacitorThermalPrinterPlugin.this.notifyListeners(
                        "discoverDevices",
                        new JSObject() {
                            {
                                put("devices", getJsonDevices());
                            }
                        }
                    );
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                notifyListeners("discoveryFinish", null);
                mBluetoothAdapter.cancelDiscovery();
                getContext().unregisterReceiver(mBluetoothReceiver);
                mRegistered = false;
            }
        }
    }

    public CapacitorThermalPrinterPlugin() {
        super();
        PrinterObserverManager.getInstance().add(this);
        ThermalPrinterFactory printerFactory = new ThermalPrinterFactory();
        rtPrinter = printerFactory.create();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bluetoothPermissions.add("BLUETOOTH");
            bluetoothPermissions.add("BLUETOOTH_ADMIN");
            bluetoothPermissions.add("ACCESS_FINE_LOCATION");
            bluetoothPermissions.add("ACCESS_COARSE_LOCATION");
        } else {
            bluetoothPermissions.add("BLUETOOTH_CONNECT");
            bluetoothPermissions.add("BLUETOOTH_SCAN");
            bluetoothPermissions.add("ACCESS_FINE_LOCATION");
        }

        Log.d(TAG, "Loading Bluetooth Permissions: " + bluetoothPermissions);
    }

    @Override
    protected void handleOnDestroy() {

        try {
            super.handleOnDestroy();

            getContext().unregisterReceiver(mBluetoothReceiver);
            mRegistered = false;

            PrinterObserverManager.getInstance().remove(this);
        } catch (Exception e) {
            Logger.info("OnDestroy : mBluetoothReceiver was not registered Yet" , e + "");
        }

    }

    @PluginMethod
    @SuppressLint("MissingPermission")
    public void startScan(PluginCall call) {
        if (!bluetoothCheck(call)) return;

        if (mRegistered) {
            call.reject("Already Scanning!");
            return;
        }

        devices = new ArrayList<>();
        boolean success = mBluetoothAdapter.startDiscovery();
        mRegistered = success;

        if (success) {
            mBluetoothReceiver = new BluetoothDeviceReceiver();
            IntentFilter mBluetoothIntentFilter = new IntentFilter();
            mBluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
            mBluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

            getContext().registerReceiver(mBluetoothReceiver, mBluetoothIntentFilter);
            call.resolve();
        } else {
            call.reject("Failed to start scan!");
        }
    }

    @PluginMethod
    @SuppressLint("MissingPermission")
    public void stopScan(PluginCall call) {
        if (!bluetoothCheck(call)) return;

        boolean success = mBluetoothAdapter.cancelDiscovery();

        if (success) {
            call.resolve();
        } else {
            call.reject("Failed to stop scan!");
        }
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        boolean state = rtPrinter.getConnectState() == ConnectStateEnum.Connected;

        if (state) {
            rtPrinter.writeMsg(new byte[]{});

            state = rtPrinter.getConnectState() == ConnectStateEnum.Connected;
        }

        boolean finalState = state;
        call.resolve(new JSObject() {{
            put("state", finalState);
        }});
    }


    private PluginCall currentConnectCallbacks = null;

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void connect(PluginCall call) {
        if (!bluetoothCheck(call)) return;
        if (currentConnectCallbacks != null) {
            call.reject("Printer already connecting!");
            return;
        }

        String address = call.getString("address");
        if (address == null) {
            call.reject("Please provide address!");
            return;
        }

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        Log.d(TAG, "Connecting to " + device);

        bluetoothEdrConfigBean = new BluetoothEdrConfigBean(device);

        PIFactory piFactory = new BluetoothFactory();
        PrinterInterface printerInterface = piFactory.create();
        printerInterface.setConfigObject(bluetoothEdrConfigBean);
        rtPrinter.setPrinterInterface(printerInterface);
        try {
            currentConnectCallbacks = call;
            rtPrinter.connect(bluetoothEdrConfigBean);
        } catch (Exception e) {
            // unreachable!
            call.reject("Failed to connect!");
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        if (rtPrinter != null && rtPrinter.getConnectState() == ConnectStateEnum.Connected) {
            rtPrinter.disConnect();
            call.resolve();
        } else {
            call.reject("Not Connected!");
        }
    }

    //region Text Formatting
    @PluginMethod
    public void bold(PluginCall call) {
        textSetting.setBold(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void underline(PluginCall call) {
        textSetting.setUnderline(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void doubleWidth(PluginCall call) {
        textSetting.setDoubleWidth(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void doubleHeight(PluginCall call) {
        textSetting.setDoubleHeight(parseIsEnabled(call));
        call.resolve();
    }

    @PluginMethod
    public void inverse(PluginCall call) {
        textSetting.setIsAntiWhite(parseIsEnabled(call));
        call.resolve();
    }

    //endregion

    //region Image Formatting
    @PluginMethod
    public void dpi(PluginCall call) {
        Integer dpi = call.getInt("dpi");
        if (dpi == null) {
            dpi = 0;
        }

        bitmapSetting.setBmpDpi(dpi);
        call.resolve();
    }

    @PluginMethod
    public void limitWidth(PluginCall call) {
        Integer width = call.getInt("width");
        if (width == null) {
            width = 0;
        }

        bitmapSetting.setBimtapLimitWidth(width * 8);
        call.resolve();
    }

    //    endregion

    //region Hybrid Formatting
    public void align() {
        align(CommonEnum.ALIGN_LEFT);
    }

    public void lineSpacing() {
        lineSpacing(30);
    }

    public void charSpacing() {
        charSpacing(1);
    }

    public void align(int alignment) {
        if (alignment > 2 || alignment < 0) alignment = 0;

        cmd.append(new byte[] { 27, 97, (byte) alignment });
    }

    public void lineSpacing(int spacing) {
        if (spacing < 0) spacing = 0;
        if (spacing > 255) spacing = 255;

        cmd.append(new byte[] { 27, 51, (byte) spacing });
    }

    public void charSpacing(int spacing) {
        if (spacing < 0) spacing = 0;
        if (spacing > 30) spacing = 30;

        cmd.append(new byte[] { 27, 32, (byte) spacing });
    }

    @PluginMethod
    public void align(PluginCall call) {
        String alignmentName = call.getString("alignment");
        int alignment = alignments.indexOf(alignmentName);
        if (alignment == -1) {
            call.reject("Invalid Alignment");
            return;
        }

        this.align(alignment);
        call.resolve();
    }

    @PluginMethod
    public void lineSpacing(PluginCall call) {
        int spacing = call.getInt("lineSpacing", 0);
        lineSpacing(spacing);
        call.resolve();
    }

    @PluginMethod
    public void charSpacing(PluginCall call) {
        int spacing = call.getInt("charSpacing", 0);
        charSpacing(spacing);
        call.resolve();
    }

    @PluginMethod
    public void font(PluginCall call) {
        String fontName = call.getString("font", "A");
        int font = fonts.indexOf(fontName);
        if (font == -1) {
            call.reject("Invalid Font");
            return;
        }

        textSetting.setEscFontType(fontEnumValues[font]);
        dataCodeSetting.setEscBarcodFont(dataFontEnumValues[font]);
        call.resolve();
    }

    @PluginMethod
    public void clearFormatting(PluginCall call) {
        textSetting = new TextSetting();
        bitmapSetting = new BitmapSetting();
        dataCodeSetting = new BarcodeSetting();

        bitmapSetting.setBimtapLimitWidth(48 * 8);
        // Reset Action Formatters
        this.align();
        this.lineSpacing();
        this.charSpacing();
        call.resolve();
    }

    //endregion

    //region Data Code Formatting

    @PluginMethod
    public void barcodeWidth(PluginCall call) {
        Integer width = call.getInt("width", 0);
        if (width != null) dataCodeSetting.setBarcodeWidth(width);
        call.resolve();
    }

    @PluginMethod
    public void barcodeHeight(PluginCall call) {
        Integer height = call.getInt("height");
        if (height != null) dataCodeSetting.setHeightInDot(height);

        call.resolve();
    }

    @PluginMethod
    public void barcodeTextPlacement(PluginCall call) {
        String placementName = call.getString("placement");
        int placement = placements.indexOf(placementName);
        if (placement == -1) {
            call.reject("Invalid Placement");
            return;
        }

        dataCodeSetting.setBarcodeStringPosition(placementEnumValues[placement]);
        call.resolve();
    }

    //endregion

    //region Content
    @PluginMethod
    public void text(PluginCall call) {
        String text = call.getString("text");

        try {
            if (text != null) cmd.append(cmd.getTextCmd(textSetting, text, "UTF-8"));
        } catch (UnsupportedEncodingException ignored) {}
        call.resolve();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @PluginMethod
    public void image(PluginCall call) {
        String image = call.getString("image");

        bitmapSetting.setBmpPrintMode(BmpPrintMode.MODE_SINGLE_COLOR);

        try {
            if (image != null) {
                byte[] d = Base64.getDecoder().decode(image.substring(image.indexOf(",") + 1));
                cmd.append(cmd.getBitmapCmd(bitmapSetting, BitmapFactory.decodeByteArray(d, 0, d.length)));
            }
        } catch (SdkException ignored) {}
        call.resolve();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @PluginMethod
    public void raw(PluginCall call) {
        String base64 = call.getString("data");
        if (base64 != null) {
            try {
                cmd.append(Base64.getDecoder().decode(base64));
            } catch (Exception ignored) {
                call.reject("Invalid Base64");
                return;
            }
            call.resolve();
            return;
        }

        JSArray dataArray = call.getArray("data");
        if (dataArray == null) {
            call.reject("Invalid Data");
            return;
        }

        byte[] data = new byte[dataArray.length()];
        for (int i = 0; i < dataArray.length(); i++) {
            try {
                data[i] = (byte) (dataArray.getInt(i) & 0xff);
            } catch (JSONException e) {
                call.reject("Invalid Data");
                return;
            }
        }

        cmd.append(data);
        call.resolve();
    }

    @PluginMethod
    public void qr(PluginCall call) {
        String data = call.getString("data", "");
        try {
            cmd.append(cmd.getBarcodeCmd(BarcodeType.QR_CODE, dataCodeSetting, data));
        } catch (SdkException ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void barcode(PluginCall call) {
        String typeName = call.getString("type");

        BarcodeType type;
        try {
            type = BarcodeType.valueOf(typeName);
        } catch (Exception ignored) {
            call.reject("Invalid Type");
            return;
        }

        if (type == BarcodeType.QR_CODE) {
            call.reject("Invalid Type");
            return;
        }
        String data = call.getString("data", "");

        try {
            cmd.append(cmd.getBarcodeCmd(type, dataCodeSetting, data));
        } catch (SdkException ignored) {}

        call.resolve();
    }

    @PluginMethod
    public void selfTest(PluginCall call) {
        cmd.append(cmd.getSelfTestCmd());
        call.resolve();
    }

    //endregion

    //region Content Actions
    @PluginMethod
    public void beep(PluginCall call) {
        cmd.append(cmd.getBeepCmd());
        call.resolve();
    }

    @PluginMethod
    public void openDrawer(PluginCall call) {
        cmd.append(cmd.getOpenMoneyBoxCmd());
        call.resolve();
    }

    @PluginMethod
    public void cutPaper(PluginCall call) {
        boolean half = Boolean.TRUE.equals(call.getBoolean("half", false));
        cmd.append(half ? cmd.getHalfCutCmd() : cmd.getAllCutCmd());

        call.resolve();
    }

    @PluginMethod
    public void feedCutPaper(PluginCall call) {
        cmd.append(new byte[] { (byte) '\n' });
        cutPaper(call);
    }

    //endregion

    //region Printing Actions
    @PluginMethod
    public void begin(PluginCall call) {
        cmd = new EscCmd();
        clearFormatting(call);
    }

    @PluginMethod
    public void write(PluginCall call) {
        _writeRaw(call, cmd.getAppendCmds());
    }

    //endregion

    //region Utils
    SettingEnum parseIsEnabled(PluginCall call) {
        if ("default".equals(call.getString("enabled"))) return SettingEnum.NoSetting;

        Boolean enabled = call.getBoolean("enabled", true);
        if (enabled == null) return SettingEnum.NoSetting;

        return enabled ? SettingEnum.Enable : SettingEnum.Disable;
    }

    private void _writeRaw(PluginCall call, byte[] data) {
        if (rtPrinter.getPrinterInterface() == null || rtPrinter.getPrinterInterface().getConnectState() != ConnectStateEnum.Connected) {
            call.reject("Printer is not connected!");
            return;
        }

        CmdFactory escFac = new EscFactory();
        Cmd escCmd = escFac.create();
        escCmd.append(escCmd.getHeaderCmd());
        escCmd.setChartsetName("UTF-8");
        escCmd.append(data);
        escCmd.append(escCmd.getLFCRCmd());
        escCmd.append(escCmd.getLFCRCmd());
        escCmd.append(escCmd.getLFCRCmd());
        escCmd.append(escCmd.getEndCmd());
        rtPrinter.writeMsgAsync(escCmd.getAppendCmds());
        call.resolve();
    }

    private boolean bluetoothCheck(PluginCall call) {
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        Log.d(TAG, "Has Bluetooth Permissions: " + hasBluetoothPermission());
        if (!hasBluetoothPermission()) {
            requestPermissionForAliases(bluetoothPermissions.toArray(new String[] {}), call, "permissionCallback");

            return false;
        }

        Log.d(TAG, "Is Bluetooth Enabled: " + mBluetoothAdapter.isEnabled());
        if (mBluetoothAdapter.isEnabled()) {
            return true;
        }

        call.reject("Please enable bluetooth!");
        return false;
    }

    private boolean hasBluetoothPermission() {
        //        return getPermissionState("bluetooth") == PermissionState.GRANTED;
        for (String permission : bluetoothPermissions) {
            if (getPermissionState(permission) != PermissionState.GRANTED) {
                return false;
            }
        }

        return true;
    }

    @PermissionCallback
    protected void permissionCallback(PluginCall call) {
        if (hasBluetoothPermission()) {
            try {
                CapacitorThermalPrinterPlugin.class.getMethod(call.getMethodName(), PluginCall.class).invoke(this, call);
            } catch (Exception e) {
                call.reject("Bluetooth method doesn't exit?!");
            }
        } else {
            call.reject("Permission is required to continue!");
        }
    }

    @SuppressLint("MissingPermission")
    private JSArray getJsonDevices() {
        JSArray array = new JSArray();
        for (BluetoothDevice device : devices) {
            array.put(
                new JSObject() {
                    {
                        put("name", device.getName());
                        put("address", device.getAddress());
                    }
                }
            );
        }

        return array;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void printerObserverCallback(PrinterInterface printerInterface, int state) {
        JSObject deviceJSON = printerInterface != null ? new JSObject() {{
            BluetoothEdrConfigBean config = ((BluetoothEdrConfigBean)printerInterface.getConfigObject());
            put("address", config.mBluetoothDevice.getAddress());
            put("name", config.mBluetoothDevice.getName());
        }}: null;

        Log.d(TAG, "STATE CHANGE " + state);
        switch (state) {
            case CommonEnum.CONNECT_STATE_SUCCESS:
                rtPrinter.setPrinterInterface(printerInterface);

                if (currentConnectCallbacks != null) {
                    currentConnectCallbacks.resolve(deviceJSON);
                    currentConnectCallbacks = null;
                }

                notifyListeners("connected", deviceJSON);

                break;
            case CommonEnum.CONNECT_STATE_INTERRUPTED:
                rtPrinter.setPrinterInterface(null);

                if (currentConnectCallbacks != null) {
                    currentConnectCallbacks.resolve(null);
                    currentConnectCallbacks = null;
                } else {
                    notifyListeners("disconnected", null);
                }
                break;
        }
    }

    @Override
    public void printerReadMsgCallback(PrinterInterface printerInterface, byte[] bytes) {}
    //endregion
}
