package com.dosse.airpods.pods;

import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.os.SystemClock;

import com.dosse.airpods.utils.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * TODO: proper javadoc
 * On a normal OS, we would use the bluetooth address of the device to filter out beacons from other devices.
 * UNFORTUNATELY, someone at google was so concerned about privacy (yea, as if they give a shit) that he decided it was a good idea to not allow access to the bluetooth address of incoming BLE beacons.
 * As a result, we have no reliable way to make sure that the beacon comes from YOUR airpods and not the guy sitting next to you on the bus.
 * What we did to workaround this issue is this:
 * - When a beacon arrives that looks like a pair of AirPods, look at the other beacons received in the last 10 seconds and get the strongest one
 * - If the strongest beacon's fake address is the same as this, use this beacon; otherwise use the strongest beacon
 * - Filter for signals stronger than -60db
 * - Decode...
 */
public abstract class PodsStatusScanCallback extends ScanCallback {
    private static final long RECENT_BEACONS_MAX_T_NS = 10000000000L; //10s

    private static final int AIRPODS_MANUFACTURER = 76;
    private static final int AIRPODS_DATA_LENGTH = 27;
    private static final int SHORT_AIRPODS_DATA_LENGTH = 17;
    private static final int MIN_RSSI = -60;

    private final List<ScanResult> mRecentBeacons = new ArrayList<>();

    public abstract void onStatus(PodsStatus status);

    public static List<ScanFilter> getScanFilters() {
        byte[] longData = new byte[] {7, 25};
        byte[] longMask = new byte[] {-1, -1};
        byte[] shortData = new byte[] {7, 15, 0};
        byte[] shortMask = new byte[] {-1, -1, -1};

        return Arrays.asList(
                new ScanFilter.Builder().setManufacturerData(AIRPODS_MANUFACTURER, longData, longMask).build(),
                new ScanFilter.Builder().setManufacturerData(AIRPODS_MANUFACTURER, shortData, shortMask).build());
    }

    @Override
    public void onBatchScanResults(List<ScanResult> scanResults) {
        for (ScanResult result : scanResults) {
            onScanResult(-1, result);
        }

        super.onBatchScanResults(scanResults);
    }

    @Override
    public void onScanResult(int callbackType, ScanResult result) {
        try {
            if (!isAirpodsResult(result)) {
                return;
            }

            result.getDevice().getAddress();

            Logger.debug(result.getRssi() + "db");
            Logger.debug(decodeResult(result));

            result = getBestResult(result);
            if (result == null || result.getRssi() < MIN_RSSI) {
                return;
            }

            PodsStatus status = new PodsStatus(decodeResult(result));
            onStatus(status);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    private ScanResult getBestResult(ScanResult result) {
        mRecentBeacons.add(result);
        ScanResult strongestBeacon = null;
        ScanResult strongestShortBeacon = null;

        for (int i = 0; i < mRecentBeacons.size(); i++) {
            if (SystemClock.elapsedRealtimeNanos() - mRecentBeacons.get(i).getTimestampNanos() > RECENT_BEACONS_MAX_T_NS) {
                mRecentBeacons.remove(i--);
                continue;
            }

            if (strongestBeacon == null || strongestBeacon.getRssi() < mRecentBeacons.get(i).getRssi()) {
                strongestBeacon = mRecentBeacons.get(i);
            }
            if (isShortFormat(mRecentBeacons.get(i)) && (strongestShortBeacon == null || strongestShortBeacon.getRssi() < mRecentBeacons.get(i).getRssi())) {
                strongestShortBeacon = mRecentBeacons.get(i);
            }
        }

        // Prefer the replica's short advertisement because it carries the accurate case battery.
        if (strongestShortBeacon != null) {
            return strongestShortBeacon;
        }

        if (strongestBeacon != null && Objects.equals(strongestBeacon.getDevice().getAddress(), result.getDevice().getAddress())) {
            strongestBeacon = result;
        }

        return strongestBeacon;
    }

    private static boolean isAirpodsResult(ScanResult result) {
        return result != null
                && result.getScanRecord() != null
                && isDataValid(result.getScanRecord().getManufacturerSpecificData(AIRPODS_MANUFACTURER));
    }

    private static boolean isDataValid(byte[] data) {
        return data != null && (data.length == AIRPODS_DATA_LENGTH
                || (data.length == SHORT_AIRPODS_DATA_LENGTH && data[0] == 7 && data[1] == 15 && data[2] == 0));
    }

    private static boolean isShortFormat(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return false;
        byte[] data = result.getScanRecord().getManufacturerSpecificData(AIRPODS_MANUFACTURER);
        return data != null && data.length == SHORT_AIRPODS_DATA_LENGTH && data[0] == 7 && data[1] == 15 && data[2] == 0;
    }

    private static String decodeResult(ScanResult result) {
        if (result != null && result.getScanRecord() != null) {
            byte[] data = result.getScanRecord().getManufacturerSpecificData(AIRPODS_MANUFACTURER);
            if (isDataValid(data)) {
                return decodeHex(data);
            }
        }

        return null;
    }

    private static String decodeHex(byte[] byteArray) {
        StringBuilder ret = new StringBuilder();

        for (byte b : byteArray) {
            ret.append(String.format("%02X", b));
        }

        return ret.toString();
    }
}
