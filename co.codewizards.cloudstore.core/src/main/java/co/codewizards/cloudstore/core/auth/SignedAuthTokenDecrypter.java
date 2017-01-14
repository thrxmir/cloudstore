package co.codewizards.cloudstore.core.auth;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import co.codewizards.cloudstore.core.util.AssertUtil;

public class SignedAuthTokenDecrypter {
	private PrivateKey privateKey;

	public SignedAuthTokenDecrypter(final byte[] privateKeyData) {
		AssertUtil.assertNotNull(privateKeyData, "privateKeyData");
		BouncyCastleRegistrationUtil.registerBouncyCastleIfNeeded();
		try {
			final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			final EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyData);
			this.privateKey = keyFactory.generatePrivate(privateKeySpec);
		} catch (final RuntimeException e) {
			throw e;
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	public byte[] decrypt(final EncryptedSignedAuthToken encryptedSignedAuthToken) {
		AssertUtil.assertNotNull(encryptedSignedAuthToken, "encryptedSignedAuthToken");
		AssertUtil.assertNotNull(encryptedSignedAuthToken.getEncryptedSignedAuthTokenData(), "encryptedSignedAuthToken.encryptedSignedAuthTokenData");
		AssertUtil.assertNotNull(encryptedSignedAuthToken.getEncryptedSymmetricKey(), "encryptedSignedAuthToken.encryptedSymmetricKey");
		try {
			final Cipher asymCipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA1ANDMGF1PADDING");
			asymCipher.init(Cipher.DECRYPT_MODE, privateKey);
			final byte[] symKey = asymCipher.doFinal(encryptedSignedAuthToken.getEncryptedSymmetricKey());

			final Cipher symCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			symCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symKey, "AES"),
					new IvParameterSpec(encryptedSignedAuthToken.getEncryptedSignedAuthTokenDataIV()));

			final byte[] signedAuthTokenData = symCipher.doFinal(encryptedSignedAuthToken.getEncryptedSignedAuthTokenData());

			return signedAuthTokenData;
		} catch (final RuntimeException e) {
			throw e;
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}
}
