package org.briarproject.sync;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.briarproject.BriarTestCase;
import org.briarproject.TestDatabaseModule;
import org.briarproject.TestLifecycleModule;
import org.briarproject.TestSystemModule;
import org.briarproject.TestUtils;
import org.briarproject.api.UniqueId;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.KeyPair;
import org.briarproject.api.crypto.PrivateKey;
import org.briarproject.api.crypto.Signature;
import org.briarproject.api.forum.ForumConstants;
import org.briarproject.api.forum.ForumPost;
import org.briarproject.api.forum.ForumPostFactory;
import org.briarproject.api.identity.Author;
import org.briarproject.api.identity.AuthorFactory;
import org.briarproject.api.messaging.MessagingConstants;
import org.briarproject.api.messaging.PrivateMessage;
import org.briarproject.api.messaging.PrivateMessageFactory;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;
import org.briarproject.contact.ContactModule;
import org.briarproject.crypto.CryptoModule;
import org.briarproject.data.DataModule;
import org.briarproject.db.DatabaseModule;
import org.briarproject.event.EventModule;
import org.briarproject.forum.ForumModule;
import org.briarproject.identity.IdentityModule;
import org.briarproject.messaging.MessagingModule;
import org.junit.Test;

import java.util.Random;

import static org.briarproject.api.forum.ForumConstants.MAX_FORUM_POST_BODY_LENGTH;
import static org.briarproject.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.api.identity.AuthorConstants.MAX_PUBLIC_KEY_LENGTH;
import static org.briarproject.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_BODY_LENGTH;
import static org.briarproject.api.sync.SyncConstants.MAX_PACKET_PAYLOAD_LENGTH;
import static org.junit.Assert.assertTrue;

public class ConstantsTest extends BriarTestCase {

	// TODO: Break this up into tests that are relevant for each package

	private final CryptoComponent crypto;
	private final AuthorFactory authorFactory;
	private final PrivateMessageFactory privateMessageFactory;
	private final ForumPostFactory forumPostFactory;

	public ConstantsTest() throws Exception {
		Injector i = Guice.createInjector(new TestDatabaseModule(),
				new TestLifecycleModule(), new TestSystemModule(),
				new ContactModule(), new CryptoModule(), new DatabaseModule(),
				new DataModule(), new EventModule(), new ForumModule(),
				new IdentityModule(), new MessagingModule(), new SyncModule());
		crypto = i.getInstance(CryptoComponent.class);
		authorFactory = i.getInstance(AuthorFactory.class);
		privateMessageFactory = i.getInstance(PrivateMessageFactory.class);
		forumPostFactory = i.getInstance(ForumPostFactory.class);
	}

	@Test
	public void testAgreementPublicKeys() throws Exception {
		// Generate 10 agreement key pairs
		for (int i = 0; i < 10; i++) {
			KeyPair keyPair = crypto.generateSignatureKeyPair();
			// Check the length of the public key
			byte[] publicKey = keyPair.getPublic().getEncoded();
			assertTrue(publicKey.length <= MAX_PUBLIC_KEY_LENGTH);
		}
	}

	@Test
	public void testSignaturePublicKeys() throws Exception {
		Random random = new Random();
		Signature sig = crypto.getSignature();
		// Generate 10 signature key pairs
		for (int i = 0; i < 10; i++) {
			KeyPair keyPair = crypto.generateSignatureKeyPair();
			// Check the length of the public key
			byte[] publicKey = keyPair.getPublic().getEncoded();
			assertTrue(publicKey.length <= MAX_PUBLIC_KEY_LENGTH);
			// Sign some random data and check the length of the signature
			byte[] toBeSigned = new byte[1234];
			random.nextBytes(toBeSigned);
			sig.initSign(keyPair.getPrivate());
			sig.update(toBeSigned);
			byte[] signature = sig.sign();
			assertTrue(signature.length <= MAX_SIGNATURE_LENGTH);
		}
	}

	@Test
	public void testPrivateMessageFitsIntoPacket() throws Exception {
		// Create a maximum-length private message
		GroupId groupId = new GroupId(TestUtils.getRandomId());
		long timestamp = Long.MAX_VALUE;
		MessageId parent = new MessageId(TestUtils.getRandomId());
		String contentType = TestUtils.createRandomString(
				MessagingConstants.MAX_CONTENT_TYPE_LENGTH);
		byte[] body = new byte[MAX_PRIVATE_MESSAGE_BODY_LENGTH];
		PrivateMessage message = privateMessageFactory.createPrivateMessage(
				groupId, timestamp, parent, contentType, body);
		// Check the size of the serialised message
		int length = message.getMessage().getRaw().length;
		assertTrue(length > UniqueId.LENGTH + 8 + UniqueId.LENGTH
				+ MessagingConstants.MAX_CONTENT_TYPE_LENGTH
				+ MAX_PRIVATE_MESSAGE_BODY_LENGTH);
		assertTrue(length <= MAX_PACKET_PAYLOAD_LENGTH);
	}

	@Test
	public void testForumPostFitsIntoPacket() throws Exception {
		// Create a maximum-length author
		String authorName = TestUtils.createRandomString(
				MAX_AUTHOR_NAME_LENGTH);
		byte[] authorPublic = new byte[MAX_PUBLIC_KEY_LENGTH];
		Author author = authorFactory.createAuthor(authorName, authorPublic);
		// Create a maximum-length forum post
		GroupId groupId = new GroupId(TestUtils.getRandomId());
		long timestamp = Long.MAX_VALUE;
		MessageId parent = new MessageId(TestUtils.getRandomId());
		String contentType = TestUtils.createRandomString(
				ForumConstants.MAX_CONTENT_TYPE_LENGTH);
		byte[] body = new byte[MAX_FORUM_POST_BODY_LENGTH];
		PrivateKey privateKey = crypto.generateSignatureKeyPair().getPrivate();
		ForumPost post = forumPostFactory.createPseudonymousPost(groupId,
				timestamp, parent, author, contentType, body, privateKey);
		// Check the size of the serialised message
		int length = post.getMessage().getRaw().length;
		assertTrue(length > UniqueId.LENGTH + 8 + UniqueId.LENGTH
				+ MAX_AUTHOR_NAME_LENGTH + MAX_PUBLIC_KEY_LENGTH
				+ ForumConstants.MAX_CONTENT_TYPE_LENGTH
				+ MAX_FORUM_POST_BODY_LENGTH);
		assertTrue(length <= MAX_PACKET_PAYLOAD_LENGTH);
	}
}
