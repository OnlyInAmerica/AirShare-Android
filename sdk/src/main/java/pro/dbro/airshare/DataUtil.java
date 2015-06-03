package pro.dbro.airshare;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Utilities for converting between Java and Database friendly types
 *
 * Created by davidbrodsky on 10/13/14.
 */
public class DataUtil {

    public static SimpleDateFormat storedDateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, 0, bytes.length);
    }

    /**
     * Pretty print bytes as Hex string in form '0x00 0x01 0x02'
     */
    public static String bytesToHex(byte[] bytes, int offset, int maxLen) {
        int bytesToPrint = Math.min(bytes.length - offset, maxLen);
        char[] hexChars = new char[bytesToPrint * 3];
        for ( int j = offset; j < bytesToPrint; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 3 ] = hexArray[v >>> 4];
            hexChars[j * 3 + 1] = hexArray[v & 0x0F];
            hexChars[j * 3 + 2] = ' ';
        }
        String rawHex = new String(hexChars);
        return rawHex;
//        String blobLiteral = "X'" + rawHex + "'";
//        return blobLiteral;
    }

    public static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

}
