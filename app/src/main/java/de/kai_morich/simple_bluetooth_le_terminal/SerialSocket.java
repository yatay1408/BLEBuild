package de.kai_morich.simple_bluetooth_le_terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

@SuppressLint("MissingPermission") // various BluetoothGatt, BluetoothDevice methods
class SerialSocket extends BluetoothGattCallback {

    private static class DeviceDelegate {
        boolean connectCharacteristics(BluetoothGattService s) { return true; }
        void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { /*nop*/ }
        void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {/*nop*/ }
        void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { /*nop*/ }
        boolean canWrite() { return true; }
        void disconnect() {/*nop*/ }
    }

    private static final UUID BLUETOOTH_LE_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MAX_MTU = 512;
    private static final int DEFAULT_MTU = 23;
    private static final String TAG = "SerialSocket";

    private final ArrayList<byte[]> writeBuffer;
    private final IntentFilter pairingIntentFilter;
    private final BroadcastReceiver pairingBroadcastReceiver;
    private final BroadcastReceiver disconnectBroadcastReceiver;

    private final Context context;
    private SerialListener listener;
    private DeviceDelegate delegate;
    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic readCharacteristic, writeCharacteristic;

    private boolean writePending;
    private boolean canceled;
    private boolean connected;
    private int payloadSize = DEFAULT_MTU - 3;

    // Thêm các biến UUID
    private final UUID serviceUUID;
    private final UUID readUUID;
    private final UUID writeUUID;

    // Cập nhật constructor để nhận các UUID
    SerialSocket(Context context, BluetoothDevice device, UUID serviceUUID, UUID readUUID, UUID writeUUID) {
        if (context instanceof Activity)
            throw new InvalidParameterException("expected non UI context");
        this.context = context;
        this.device = device;
        this.serviceUUID = serviceUUID;
        this.readUUID = readUUID;
        this.writeUUID = writeUUID;
        writeBuffer = new ArrayList<>();
        pairingIntentFilter = new IntentFilter();
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onPairingBroadcastReceive(context, intent);
            }
        };
        disconnectBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (listener != null)
                    listener.onSerialIoError(new IOException("background disconnect"));
                disconnect(); // disconnect now, else would be queued until UI re-attached
            }
        };
    }

    String getName() {
        return device.getName() != null ? device.getName() : device.getAddress();
    }

    void disconnect() {
        Log.d(TAG, "disconnect");
        listener = null; // ignore remaining data and errors
        device = null;
        canceled = true;
        synchronized (writeBuffer) {
            writePending = false;
            writeBuffer.clear();
        }
        readCharacteristic = null;
        writeCharacteristic = null;
        if (delegate != null)
            delegate.disconnect();
        if (gatt != null) {
            Log.d(TAG, "gatt.disconnect");
            gatt.disconnect();
            Log.d(TAG, "gatt.close");
            try {
                gatt.close();
            } catch (Exception ignored) {}
            gatt = null;
            connected = false;
        }
        try {
            context.unregisterReceiver(pairingBroadcastReceiver);
        } catch (Exception ignored) {
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver);
        } catch (Exception ignored) {
        }
    }

    void connect(SerialListener listener) throws IOException {
        if (connected || gatt != null)
            throw new IOException("already connected");
        canceled = false;
        this.listener = listener;
        ContextCompat.registerReceiver(context, disconnectBroadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_DISCONNECT), ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "connect " + device);
        context.registerReceiver(pairingBroadcastReceiver, pairingIntentFilter);
        if (Build.VERSION.SDK_INT < 23) {
            Log.d(TAG, "connectGatt");
            gatt = device.connectGatt(context, false, this);
        } else {
            Log.d(TAG, "connectGatt,LE");
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        }
        if (gatt == null)
            throw new IOException("connectGatt failed");
    }

    private void onPairingBroadcastReceive(Context context, Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !device.equals(this.device))
            return;
        switch (intent.getAction()) {
            case BluetoothDevice.ACTION_PAIRING_REQUEST:
                final int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                Log.d(TAG, "pairing request " + pairingVariant);
                onSerialConnectError(new IOException(context.getString(R.string.pairing_request)));
                break;
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                Log.d(TAG, "bond state " + previousBondState + "->" + bondState);
                break;
            default:
                Log.d(TAG, "unknown broadcast " + intent.getAction());
                break;
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "connect status " + status + ", discoverServices");
            if (!gatt.discoverServices())
                onSerialConnectError(new IOException("discoverServices failed"));
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (connected)
                onSerialIoError(new IOException("gatt status " + status));
            else
                onSerialConnectError(new IOException("gatt status " + status));
        } else {
            Log.d(TAG, "unknown connect state " + newState + " " + status);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        Log.d(TAG, "servicesDiscovered, status " + status);
        if (canceled)
            return;
        connectCharacteristics1(gatt);
    }

    private void connectCharacteristics1(BluetoothGatt gatt) {
        boolean sync = true;
        writePending = false;
        BluetoothGattService gattService = gatt.getService(serviceUUID);
        if (gattService != null) {
            readCharacteristic = gattService.getCharacteristic(readUUID);
            writeCharacteristic = gattService.getCharacteristic(writeUUID);
            if (readCharacteristic != null && writeCharacteristic != null) {
                sync = delegate == null || delegate.connectCharacteristics(gattService);
            }
        }

        if (canceled)
            return;
        if (readCharacteristic == null || writeCharacteristic == null) {
            onSerialConnectError(new IOException("no serial profile found"));
            return;
        }
        if (sync)
            connectCharacteristics2(gatt);
    }

    private void connectCharacteristics2(BluetoothGatt gatt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "request max MTU");
            if (!gatt.requestMtu(MAX_MTU))
                onSerialConnectError(new IOException("request MTU failed"));
        } else {
            connectCharacteristics3(gatt);
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        Log.d(TAG, "mtu size " + mtu + ", status=" + status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            payloadSize = mtu - 3;
            Log.d(TAG, "payload size " + payloadSize);
        }
        connectCharacteristics3(gatt);
    }

    private void connectCharacteristics3(BluetoothGatt gatt) {
        int writeProperties = writeCharacteristic.getProperties();
        if ((writeProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE +
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            onSerialConnectError(new IOException("write characteristic not writable"));
            return;
        }
        if (!gatt.setCharacteristicNotification(readCharacteristic, true)) {
            onSerialConnectError(new IOException("no notification for read characteristic"));
            return;
        }
        BluetoothGattDescriptor readDescriptor = readCharacteristic.getDescriptor(BLUETOOTH_LE_CCCD);
        if (readDescriptor == null) {
            onSerialConnectError(new IOException("no CCCD descriptor for read characteristic"));
            return;
        }
        int readProperties = readCharacteristic.getProperties();
        if ((readProperties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d(TAG, "enable read indication");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        } else if ((readProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            Log.d(TAG, "enable read notification");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            onSerialConnectError(new IOException("no indication/notification for read characteristic (" + readProperties + ")"));
            return;
        }
        Log.d(TAG, "writing read characteristic descriptor");
        if (!gatt.writeDescriptor(readDescriptor)) {
            onSerialConnectError(new IOException("read characteristic CCCD descriptor not writable"));
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        delegate.onDescriptorWrite(gatt, descriptor, status);
        if (canceled)
            return;
        if (descriptor.getCharacteristic() == readCharacteristic) {
            Log.d(TAG, "writing read characteristic descriptor finished, status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(new IOException("write descriptor failed"));
            } else {
                onSerialConnect();
                connected = true;
                Log.d(TAG, "connected");
            }
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (canceled)
            return;
        delegate.onCharacteristicChanged(gatt, characteristic);
        if (canceled)
            return;
        if (characteristic == readCharacteristic) {
            byte[] data = readCharacteristic.getValue();
            onSerialRead(data);
            Log.d(TAG, "read, len=" + data.length);
        }
    }

    void write(byte[] data) throws IOException {
        if (canceled || !connected || writeCharacteristic == null)
            throw new IOException("not connected");
        byte[] data0;
        synchronized (writeBuffer) {
            if (data.length <= payloadSize) {
                data0 = data;
            } else {
                data0 = Arrays.copyOfRange(data, 0, payloadSize);
            }
            if (!writePending && writeBuffer.isEmpty() && delegate.canWrite()) {
                writePending = true;
            } else {
                writeBuffer.add(data0);
                Log.d(TAG, "write queued, len=" + data0.length);
                data0 = null;
            }
            if (data.length > payloadSize) {
                for (int i = 1; i < (data.length + payloadSize - 1) / payloadSize; i++) {
                    int from = i * payloadSize;
                    int to = Math.min(from + payloadSize, data.length);
                    writeBuffer.add(Arrays.copyOfRange(data, from, to));
                    Log.d(TAG, "write queued, len=" + (to - from));
                }
            }
        }
        if (data0 != null) {
            writeCharacteristic.setValue(data0);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(new IOException("write failed"));
            } else {
                Log.d(TAG, "write started, len=" + data0.length);
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (canceled || !connected || writeCharacteristic == null)
            return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(new IOException("write failed"));
            return;
        }
        delegate.onCharacteristicWrite(gatt, characteristic, status);
        if (canceled)
            return;
        if (characteristic == writeCharacteristic) {
            Log.d(TAG, "write finished, status=" + status);
            writeNext();
        }
    }

    private void writeNext() {
        final byte[] data;
        synchronized (writeBuffer) {
            if (!writeBuffer.isEmpty() && delegate.canWrite()) {
                writePending = true;
                data = writeBuffer.remove(0);
            } else {
                writePending = false;
                data = null;
            }
        }
        if (data != null) {
            writeCharacteristic.setValue(data);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(new IOException("write failed"));
            } else {
                Log.d(TAG, "write started, len=" + data.length);
            }
        }
    }

    private void onSerialConnect() {
        if (listener != null)
            listener.onSerialConnect();
    }

    private void onSerialConnectError(Exception e) {
        canceled = true;
        if (listener != null)
            listener.onSerialConnectError(e);
    }

    private void onSerialRead(byte[] data) {
        if (listener != null)
            listener.onSerialRead(data);
    }

    private void onSerialIoError(Exception e) {
        writePending = false;
        canceled = true;
        if (listener != null)
            listener.onSerialIoError(e);
    }

    private class Cc245XDelegate extends DeviceDelegate {
        @Override
        boolean connectCharacteristics(BluetoothGattService gattService) {
            Log.d(TAG, "service cc254x uart");
            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW);
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW);
            return true;
        }
    }

    private class MicrochipDelegate extends DeviceDelegate {
        @Override
        boolean connectCharacteristics(BluetoothGattService gattService) {
            Log.d(TAG, "service microchip uart");
            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_RW);
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_W);
            if (writeCharacteristic == null)
                writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_RW);
            return true;
        }
    }

    private class NrfDelegate extends DeviceDelegate {
        @Override
        boolean connectCharacteristics(BluetoothGattService gattService) {
            Log.d(TAG, "service nrf uart");
            BluetoothGattCharacteristic rw2 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2);
            BluetoothGattCharacteristic rw3 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3);
            if (rw2 != null && rw3 != null) {
                int rw2prop = rw2.getProperties();
                int rw3prop = rw3.getProperties();
                boolean rw2write = (rw2prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                boolean rw3write = (rw3prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                Log.d(TAG, "characteristic properties " + rw2prop + "/" + rw3prop);
                if (rw2write && rw3write) {
                    onSerialConnectError(new IOException("multiple write characteristics (" + rw2prop + "/" + rw3prop + ")"));
                } else if (rw2write) {
                    writeCharacteristic = rw2;
                    readCharacteristic = rw3;
                } else if (rw3write) {
                    writeCharacteristic = rw3;
                    readCharacteristic = rw2;
                } else {
                    onSerialConnectError(new IOException("no write characteristic (" + rw2prop + "/" + rw3prop + ")"));
                }
            }
            return true;
        }
    }

    private class TelitDelegate extends DeviceDelegate {
        private BluetoothGattCharacteristic readCreditsCharacteristic, writeCreditsCharacteristic;
        private int readCredits, writeCredits;

        @Override
        boolean connectCharacteristics(BluetoothGattService gattService) {
            Log.d(TAG, "service telit tio 2.0");
            readCredits = 0;
            writeCredits = 0;
            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_RX);
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_TX);
            readCreditsCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_RX_CREDITS);
            writeCreditsCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_TX_CREDITS);
            if (readCharacteristic == null) {
                onSerialConnectError(new IOException("read characteristic not found"));
                return false;
            }
            if (writeCharacteristic == null) {
                onSerialConnectError(new IOException("write characteristic not found"));
                return false;
            }
            if (readCreditsCharacteristic == null) {
                onSerialConnectError(new IOException("read credits characteristic not found"));
                return false;
            }
            if (writeCreditsCharacteristic == null) {
                onSerialConnectError(new IOException("write credits characteristic not found"));
                return false;
            }
            if (!gatt.setCharacteristicNotification(readCreditsCharacteristic, true)) {
                onSerialConnectError(new IOException("no notification for read credits characteristic"));
                return false;
            }
            BluetoothGattDescriptor readCreditsDescriptor = readCreditsCharacteristic.getDescriptor(BLUETOOTH_LE_CCCD);
            if (readCreditsDescriptor == null) {
                onSerialConnectError(new IOException("no CCCD descriptor for read credits characteristic"));
                return false;
            }
            readCreditsDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            Log.d(TAG, "writing read credits characteristic descriptor");
            if (!gatt.writeDescriptor(readCreditsDescriptor)) {
                onSerialConnectError(new IOException("read credits characteristic CCCD descriptor not writable"));
                return false;
            }
            Log.d(TAG, "writing read credits characteristic descriptor");
            return false;
        }

        @Override
        void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor.getCharacteristic() == readCreditsCharacteristic) {
                Log.d(TAG, "writing read credits characteristic descriptor finished, status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onSerialConnectError(new IOException("write credits descriptor failed"));
                } else {
                    connectCharacteristics2(gatt);
                }
            }
            if (descriptor.getCharacteristic() == readCharacteristic) {
                Log.d(TAG, "writing read characteristic descriptor finished, status=" + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    readCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    grantReadCredits();
                }
            }
        }

        @Override
        void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == readCreditsCharacteristic) {
                int newCredits = readCreditsCharacteristic.getValue()[0];
                synchronized (writeBuffer) {
                    writeCredits += newCredits;
                }
                Log.d(TAG, "got write credits +" + newCredits + " =" + writeCredits);

                if (!writePending && !writeBuffer.isEmpty()) {
                    Log.d(TAG, "resume blocked write");
                    writeNext();
                }
            }
            if (characteristic == readCharacteristic) {
                grantReadCredits();
                Log.d(TAG, "read, credits=" + readCredits);
            }
        }

        @Override
        void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic == writeCharacteristic) {
                synchronized (writeBuffer) {
                    if (writeCredits > 0)
                        writeCredits -= 1;
                }
                Log.d(TAG, "write finished, credits=" + writeCredits);
            }
            if (characteristic == writeCreditsCharacteristic) {
                Log.d(TAG, "write credits finished, status=" + status);
            }
        }

        @Override
        boolean canWrite() {
            if (writeCredits > 0)
                return true;
            Log.d(TAG, "no write credits");
            return false;
        }

        @Override
        void disconnect() {
            readCreditsCharacteristic = null;
            writeCreditsCharacteristic = null;
        }

        private void grantReadCredits() {
            final int minReadCredits = 16;
            final int maxReadCredits = 64;
            if (readCredits > 0)
                readCredits -= 1;
            if (readCredits <= minReadCredits) {
                int newCredits = maxReadCredits - readCredits;
                readCredits += newCredits;
                byte[] data = new byte[]{(byte) newCredits};
                Log.d(TAG, "grant read credits +" + newCredits + " =" + readCredits);
                writeCreditsCharacteristic.setValue(data);
                if (!gatt.writeCharacteristic(writeCreditsCharacteristic)) {
                    if (connected)
                        onSerialIoError(new IOException("write read credits failed"));
                    else
                        onSerialConnectError(new IOException("write read credits failed"));
                }
            }
        }
    }
}
