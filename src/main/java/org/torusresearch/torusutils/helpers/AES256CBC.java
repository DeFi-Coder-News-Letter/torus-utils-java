package org.torusresearch.torusutils.helpers;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECFieldFp;
import java.security.spec.EllipticCurve;
import java.util.Arrays;

public class AES256CBC {
    private BigInteger AES_ENCRYPTION_KEY;
    private BigInteger ENCRYPTION_IV;

    public AES256CBC(String privateKeyHex, String ephemPublicKeyHex, String encryptionIvHex) {
        byte[] hash = SHA512.digest(toByteArray(ecdh(privateKeyHex, ephemPublicKeyHex)));
        byte[] encKeyBytes = Arrays.copyOfRange(hash, 0, 32);
        AES_ENCRYPTION_KEY = new BigInteger(encKeyBytes);
        ENCRYPTION_IV = new BigInteger(encryptionIvHex, 16);
    }

    /**
     * Utility method to convert a BigInteger to a byte array in unsigned
     * format as needed in the handshake messages. BigInteger uses
     * 2's complement format, i.e. it prepends an extra zero if the MSB
     * is set. We remove that.
     */
    public static byte[] toByteArray(BigInteger bi) {
        byte[] b = bi.toByteArray();
        if ((b.length > 1) && (b[0] == 0)) {
            int n = b.length - 1;
            byte[] newarray = new byte[n];
            System.arraycopy(b, 1, newarray, 0, n);
            b = newarray;
        }
        return b;
    }

    public String encrypt(byte[] src) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, makeKey(), makeIv());
            return Base64.encodeBytes(cipher.doFinal(src));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] decrypt(String src) {
        byte[] decrypted;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, makeKey(), makeIv());
            decrypted = cipher.doFinal(Base64.decode(src));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return decrypted;
    }

    private BigInteger ecdh(String privateKeyHex, String ephemPublicKeyHex) {
        String affineX = ephemPublicKeyHex.substring(2, 66);
        String affineY = ephemPublicKeyHex.substring(66);

        ECPointArithmetic ecPoint = new ECPointArithmetic(new EllipticCurve(
                new ECFieldFp(new BigInteger("115792089237316195423570985008687907853269984665640564039457584007908834671663")),
                new BigInteger("0"),
                new BigInteger("7")), new BigInteger(affineX, 16), new BigInteger(affineY, 16), null);
        return ecPoint.multiply(new BigInteger(privateKeyHex, 16)).getX();
    }

    private Key makeKey() {
        return new SecretKeySpec(toByteArray(AES_ENCRYPTION_KEY), "AES");
    }

    private AlgorithmParameterSpec makeIv() {
        return new IvParameterSpec(toByteArray(ENCRYPTION_IV));
    }
}
