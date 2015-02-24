package pro.dbro.airshare.transport.ble;

import java.util.Map;

import pro.dbro.airshare.transport.Transport;

/**
 * Created by davidbrodsky on 2/23/15.
 */
public interface BLETransportCallback {

    public static enum DeviceType { CENTRAL, PERIPHERAL }

    public void dataReceivedFromIdentifier(DeviceType deviceType,
                                           byte[] data,
                                           String identifier);

    public void dataSentToIdentifier(DeviceType deviceType,
                                     byte[] data,
                                     String identifier);

    public void identifierUpdated(DeviceType deviceType,
                                  String identifier,
                                  Transport.ConnectionStatus status,
                                  Map<String, Object> extraInfo);

}