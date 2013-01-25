package net.sf.briar.crypto;

import static javax.crypto.Cipher.ENCRYPT_MODE;
import static net.sf.briar.api.plugins.InvitationConstants.CODE_BITS;
import static net.sf.briar.api.transport.TransportConstants.TAG_LENGTH;
import static net.sf.briar.util.ByteUtils.MAX_32_BIT_UNSIGNED;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECField;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.EllipticCurve;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;

import net.sf.briar.api.crypto.AuthenticatedCipher;
import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.crypto.ErasableKey;
import net.sf.briar.api.crypto.KeyParser;
import net.sf.briar.api.crypto.MessageDigest;
import net.sf.briar.api.crypto.PseudoRandom;
import net.sf.briar.util.ByteUtils;

import org.spongycastle.crypto.engines.AESEngine;
import org.spongycastle.crypto.modes.AEADBlockCipher;
import org.spongycastle.crypto.modes.GCMBlockCipher;
import org.spongycastle.jce.provider.BouncyCastleProvider;

import com.google.inject.Inject;

class CryptoComponentImpl implements CryptoComponent {

	private static final String PROVIDER = "SC";
	private static final String AGREEMENT_KEY_PAIR_ALGO = "ECDH";
	private static final int AGREEMENT_KEY_PAIR_BITS = 384;
	private static final String AGREEMENT_ALGO = "ECDHC";
	private static final String SECRET_KEY_ALGO = "AES";
	private static final int SECRET_KEY_BYTES = 32; // 256 bits
	private static final String KEY_DERIVATION_ALGO = "AES/CTR/NoPadding";
	private static final int KEY_DERIVATION_IV_BYTES = 16; // 128 bits
	private static final String DIGEST_ALGO = "SHA-384";
	private static final String SIGNATURE_KEY_PAIR_ALGO = "ECDSA";
	private static final int SIGNATURE_KEY_PAIR_BITS = 384;
	private static final String SIGNATURE_ALGO = "ECDSA";
	private static final String TAG_CIPHER_ALGO = "AES/ECB/NoPadding";
	private static final int GCM_MAC_LENGTH = 16; // 128 bits

	// Labels for key derivation
	private static final byte[] A_TAG = { 'A', '_', 'T', 'A', 'G', '\0' };
	private static final byte[] B_TAG = { 'B', '_', 'T', 'A', 'G', '\0' };
	private static final byte[] A_FRAME_A =
		{ 'A', '_', 'F', 'R', 'A', 'M', 'E', '_', 'A', '\0' };
	private static final byte[] A_FRAME_B =
		{ 'A', '_', 'F', 'R', 'A', 'M', 'E', '_', 'B', '\0' };
	private static final byte[] B_FRAME_A =
		{ 'B', '_', 'F', 'R', 'A', 'M', 'E', '_', 'A', '\0' };
	private static final byte[] B_FRAME_B =
		{ 'B', '_', 'F', 'R', 'A', 'M', 'E', '_', 'B', '\0' };
	// Labels for secret derivation
	private static final byte[] FIRST = { 'F', 'I', 'R', 'S', 'T', '\0' };
	private static final byte[] ROTATE = { 'R', 'O', 'T', 'A', 'T', 'E', '\0' };
	// Label for confirmation code derivation
	private static final byte[] CODE = { 'C', 'O', 'D', 'E', '\0' };
	// Blank plaintext for key derivation
	private static final byte[] KEY_DERIVATION_BLANK_PLAINTEXT =
			new byte[SECRET_KEY_BYTES];

	// Parameters for NIST elliptic curve P-384 - see "Suite B Implementer's
	// Guide to NIST SP 800-56A", section A.2
	private static final BigInteger P_384_Q = new BigInteger("FFFFFFFF" +
			"FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" +
			"FFFFFFFF" + "FFFFFFFE" + "FFFFFFFF" + "00000000" + "00000000" +
			"FFFFFFFF", 16);
	private static final BigInteger P_384_A = new BigInteger("FFFFFFFF" +
			"FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" +
			"FFFFFFFF" + "FFFFFFFE" + "FFFFFFFF" + "00000000" + "00000000" +
			"FFFFFFFC", 16);
	private static final BigInteger P_384_B = new BigInteger("B3312FA7" +
			"E23EE7E4" + "988E056B" + "E3F82D19" + "181D9C6E" + "FE814112" +
			"0314088F" + "5013875A" + "C656398D" + "8A2ED19D" + "2A85C8ED" +
			"D3EC2AEF", 16);
	private static final BigInteger P_384_G_X = new BigInteger("AA87CA22" +
			"BE8B0537" + "8EB1C71E" + "F320AD74" + "6E1D3B62" + "8BA79B98" +
			"59F741E0" + "82542A38" + "5502F25D" + "BF55296C" + "3A545E38" +
			"72760AB7", 16);
	private static final BigInteger P_384_G_Y = new BigInteger("3617DE4A" +
			"96262C6F" + "5D9E98BF" + "9292DC29" + "F8F41DBD" + "289A147C" +
			"E9DA3113" + "B5F0B8C0" + "0A60B1CE" + "1D7E819D" + "7A431D7C" +
			"90EA0E5F", 16);
	private static final BigInteger P_384_N = new BigInteger("FFFFFFFF" +
			"FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" + "FFFFFFFF" +
			"C7634D81" + "F4372DDF" + "581A0DB2" + "48B0A77A" + "ECEC196A" +
			"CCC52973", 16);
	private static final int P_384_H = 1;
	// Static parameter objects derived from the above parameters
	private static final ECField P_384_FIELD = new ECFieldFp(P_384_Q);
	private static final EllipticCurve P_384_CURVE =
			new EllipticCurve(P_384_FIELD, P_384_A, P_384_B);
	private static final ECPoint P_384_G = new ECPoint(P_384_G_X, P_384_G_Y);
	private static final ECParameterSpec P_384_PARAMS =
			new ECParameterSpec(P_384_CURVE, P_384_G, P_384_N, P_384_H);

	private final KeyParser agreementKeyParser, signatureKeyParser;
	private final KeyPairGenerator agreementKeyPairGenerator;
	private final KeyPairGenerator signatureKeyPairGenerator;
	private final SecureRandom secureRandom;

	@Inject
	CryptoComponentImpl() {
		Security.addProvider(new BouncyCastleProvider());
		try {
			KeyFactory agreementKeyFactory = KeyFactory.getInstance(
					AGREEMENT_KEY_PAIR_ALGO, PROVIDER);
			agreementKeyParser = new Sec1KeyParser(agreementKeyFactory,
					P_384_PARAMS, P_384_Q, AGREEMENT_KEY_PAIR_BITS);
			KeyFactory signatureKeyFactory = KeyFactory.getInstance(
					SIGNATURE_KEY_PAIR_ALGO, PROVIDER);
			signatureKeyParser = new Sec1KeyParser(signatureKeyFactory,
					P_384_PARAMS, P_384_Q, SIGNATURE_KEY_PAIR_BITS);
			agreementKeyPairGenerator = KeyPairGenerator.getInstance(
					AGREEMENT_KEY_PAIR_ALGO, PROVIDER);
			agreementKeyPairGenerator.initialize(AGREEMENT_KEY_PAIR_BITS);
			signatureKeyPairGenerator = KeyPairGenerator.getInstance(
					SIGNATURE_KEY_PAIR_ALGO, PROVIDER);
			signatureKeyPairGenerator.initialize(SIGNATURE_KEY_PAIR_BITS);
		} catch(GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
		secureRandom = new SecureRandom();
	}

	public ErasableKey deriveTagKey(byte[] secret, boolean alice) {
		if(alice) return deriveKey(secret, A_TAG, 0L);
		else return deriveKey(secret, B_TAG, 0L);
	}

	public ErasableKey deriveFrameKey(byte[] secret, long connection,
			boolean alice, boolean initiator) {
		if(alice) {
			if(initiator) return deriveKey(secret, A_FRAME_A, connection);
			else return deriveKey(secret, A_FRAME_B, connection);
		} else {
			if(initiator) return deriveKey(secret, B_FRAME_A, connection);
			else return deriveKey(secret, B_FRAME_B, connection);
		}
	}

	private ErasableKey deriveKey(byte[] secret, byte[] label, long context) {
		byte[] key = counterModeKdf(secret, label, context);
		return new ErasableKeyImpl(key, SECRET_KEY_ALGO);
	}

	// Key derivation function based on a block cipher in CTR mode - see
	// NIST SP 800-108, section 5.1
	private byte[] counterModeKdf(byte[] secret, byte[] label, long context) {
		// The secret must be usable as a key
		if(secret.length != SECRET_KEY_BYTES)
			throw new IllegalArgumentException();
		// The label and context must leave a byte free for the counter
		if(label.length + 4 >= KEY_DERIVATION_IV_BYTES)
			throw new IllegalArgumentException();
		byte[] ivBytes = new byte[KEY_DERIVATION_IV_BYTES];
		System.arraycopy(label, 0, ivBytes, 0, label.length);
		ByteUtils.writeUint32(context, ivBytes, label.length);
		// Use the secret and the IV to encrypt a blank plaintext
		IvParameterSpec iv = new IvParameterSpec(ivBytes);
		ErasableKey key = new ErasableKeyImpl(secret, SECRET_KEY_ALGO);
		try {
			Cipher cipher = Cipher.getInstance(KEY_DERIVATION_ALGO, PROVIDER);
			cipher.init(Cipher.ENCRYPT_MODE, key, iv);
			byte[] output = cipher.doFinal(KEY_DERIVATION_BLANK_PLAINTEXT);
			assert output.length == SECRET_KEY_BYTES;
			return output;
		} catch(GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	public byte[] deriveInitialSecret(byte[] theirPublicKey,
			KeyPair ourKeyPair, boolean alice) throws GeneralSecurityException {
		PublicKey theirPublic = agreementKeyParser.parsePublicKey(
				theirPublicKey);
		MessageDigest messageDigest = getMessageDigest();
		byte[] ourPublicKey = ourKeyPair.getPublic().getEncoded();
		byte[] ourHash = messageDigest.digest(ourPublicKey);
		byte[] theirHash = messageDigest.digest(theirPublicKey);
		byte[] aliceInfo, bobInfo;
		if(alice) {
			aliceInfo = ourHash;
			bobInfo = theirHash;
		} else {
			aliceInfo = theirHash;
			bobInfo = ourHash;
		}
		// The raw secret comes from the key agreement algorithm
		KeyAgreement keyAgreement = KeyAgreement.getInstance(AGREEMENT_ALGO,
				PROVIDER);
		keyAgreement.init(ourKeyPair.getPrivate());
		keyAgreement.doPhase(theirPublic, true);
		byte[] rawSecret = keyAgreement.generateSecret();
		// Derive the cooked secret from the raw secret using the
		// concatenation KDF
		byte[] cookedSecret = concatenationKdf(rawSecret, FIRST, aliceInfo,
				bobInfo);
		ByteUtils.erase(rawSecret);
		return cookedSecret;
	}

	// Key derivation function based on a hash function - see NIST SP 800-56A,
	// section 5.8
	private byte[] concatenationKdf(byte[] rawSecret, byte[] label,
			byte[] initiatorInfo, byte[] responderInfo) {
		// The output of the hash function must be long enough to use as a key
		MessageDigest messageDigest = getMessageDigest();
		if(messageDigest.getDigestLength() < SECRET_KEY_BYTES)
			throw new RuntimeException();
		// All fields are length-prefixed
		byte[] length = new byte[1];
		ByteUtils.writeUint8(rawSecret.length, length, 0);
		messageDigest.update(length);
		messageDigest.update(rawSecret);
		ByteUtils.writeUint8(label.length, length, 0);
		messageDigest.update(length);
		messageDigest.update(label);
		ByteUtils.writeUint8(initiatorInfo.length, length, 0);
		messageDigest.update(length);
		messageDigest.update(initiatorInfo);
		ByteUtils.writeUint8(responderInfo.length, length, 0);
		messageDigest.update(length);
		messageDigest.update(responderInfo);
		byte[] hash = messageDigest.digest();
		// The secret is the first SECRET_KEY_BYTES bytes of the hash
		byte[] output = new byte[SECRET_KEY_BYTES];
		System.arraycopy(hash, 0, output, 0, SECRET_KEY_BYTES);
		ByteUtils.erase(hash);
		return output;
	}

	public byte[] deriveNextSecret(byte[] secret, long period) {
		if(period < 0 || period > MAX_32_BIT_UNSIGNED)
			throw new IllegalArgumentException();
		return counterModeKdf(secret, ROTATE, period);
	}

	public void encodeTag(byte[] tag, Cipher tagCipher, ErasableKey tagKey,
			long connection) {
		if(tag.length < TAG_LENGTH) throw new IllegalArgumentException();
		if(connection < 0 || connection > MAX_32_BIT_UNSIGNED)
			throw new IllegalArgumentException();
		for(int i = 0; i < TAG_LENGTH; i++) tag[i] = 0;
		ByteUtils.writeUint32(connection, tag, 0);
		try {
			tagCipher.init(ENCRYPT_MODE, tagKey);
			int encrypted = tagCipher.doFinal(tag, 0, TAG_LENGTH, tag);
			if(encrypted != TAG_LENGTH) throw new IllegalArgumentException();
		} catch(GeneralSecurityException e) {
			// Unsuitable cipher or key
			throw new IllegalArgumentException(e);
		}
	}

	public int generateInvitationCode() {
		int codeBytes = (int) Math.ceil(CODE_BITS / 8.0);
		byte[] random = new byte[codeBytes];
		secureRandom.nextBytes(random);
		return ByteUtils.readUint(random, CODE_BITS);
	}

	public int[] deriveConfirmationCodes(byte[] secret) {
		byte[] alice = counterModeKdf(secret, CODE, 0);
		byte[] bob = counterModeKdf(secret, CODE, 1);
		int[] codes = new int[2];
		codes[0] = ByteUtils.readUint(alice, CODE_BITS);
		codes[1] = ByteUtils.readUint(bob, CODE_BITS);
		ByteUtils.erase(alice);
		ByteUtils.erase(bob);
		return codes;
	}

	public KeyPair generateAgreementKeyPair() {
		KeyPair keyPair = agreementKeyPairGenerator.generateKeyPair();
		// Check that the key pair uses NIST curve P-384
		ECPublicKey ecPublicKey = checkP384Params(keyPair.getPublic());
		// Return a public key that uses the SEC 1 encoding
		ecPublicKey = new Sec1PublicKey(ecPublicKey, AGREEMENT_KEY_PAIR_BITS);
		return new KeyPair(ecPublicKey, keyPair.getPrivate());
	}

	private ECPublicKey checkP384Params(PublicKey publicKey) {
		if(!(publicKey instanceof ECPublicKey)) throw new RuntimeException();
		ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
		ECParameterSpec params = ecPublicKey.getParams();
		EllipticCurve curve = params.getCurve();
		ECField field = curve.getField();
		if(!(field instanceof ECFieldFp)) throw new RuntimeException();
		BigInteger q = ((ECFieldFp) field).getP();
		if(!q.equals(P_384_Q)) throw new RuntimeException();
		if(!curve.getA().equals(P_384_A)) throw new RuntimeException();
		if(!curve.getB().equals(P_384_B)) throw new RuntimeException();
		if(!params.getGenerator().equals(P_384_G)) throw new RuntimeException();
		if(!params.getOrder().equals(P_384_N)) throw new RuntimeException();
		if(!(params.getCofactor() == P_384_H)) throw new RuntimeException();
		return ecPublicKey;
	}

	public KeyParser getAgreementKeyParser() {
		return agreementKeyParser;
	}

	public KeyPair generateSignatureKeyPair() {
		KeyPair keyPair = signatureKeyPairGenerator.generateKeyPair();
		// Check that the key pair uses NIST curve P-384
		ECPublicKey ecPublicKey = checkP384Params(keyPair.getPublic());
		// Return a public key that uses the SEC 1 encoding
		ecPublicKey = new Sec1PublicKey(ecPublicKey, SIGNATURE_KEY_PAIR_BITS);
		return new KeyPair(ecPublicKey, keyPair.getPrivate());
	}

	public KeyParser getSignatureKeyParser() {
		return signatureKeyParser;
	}

	public ErasableKey generateTestKey() {
		byte[] b = new byte[SECRET_KEY_BYTES];
		secureRandom.nextBytes(b);
		return new ErasableKeyImpl(b, SECRET_KEY_ALGO);
	}

	public MessageDigest getMessageDigest() {
		try {
			return new DoubleDigest(java.security.MessageDigest.getInstance(
					DIGEST_ALGO, PROVIDER));
		} catch(GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	public PseudoRandom getPseudoRandom(int seed1, int seed2) {
		return new PseudoRandomImpl(getMessageDigest(), seed1, seed2);
	}

	public SecureRandom getSecureRandom() {
		return secureRandom;
	}

	public Signature getSignature() {
		try {
			return Signature.getInstance(SIGNATURE_ALGO, PROVIDER);
		} catch(GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	public Cipher getTagCipher() {
		try {
			return Cipher.getInstance(TAG_CIPHER_ALGO, PROVIDER);
		} catch(GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	public AuthenticatedCipher getFrameCipher() {
		// This code is specific to BouncyCastle because javax.crypto.Cipher
		// doesn't support additional authenticated data until Java 7
		AEADBlockCipher cipher = new GCMBlockCipher(new AESEngine());
		return new AuthenticatedCipherImpl(cipher, GCM_MAC_LENGTH);
	}
}