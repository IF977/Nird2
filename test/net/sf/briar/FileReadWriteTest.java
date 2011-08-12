package net.sf.briar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import junit.framework.TestCase;
import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.protocol.Ack;
import net.sf.briar.api.protocol.Author;
import net.sf.briar.api.protocol.AuthorFactory;
import net.sf.briar.api.protocol.Batch;
import net.sf.briar.api.protocol.BatchId;
import net.sf.briar.api.protocol.Group;
import net.sf.briar.api.protocol.GroupFactory;
import net.sf.briar.api.protocol.Message;
import net.sf.briar.api.protocol.MessageEncoder;
import net.sf.briar.api.protocol.MessageId;
import net.sf.briar.api.protocol.Offer;
import net.sf.briar.api.protocol.Request;
import net.sf.briar.api.protocol.SubscriptionUpdate;
import net.sf.briar.api.protocol.TransportUpdate;
import net.sf.briar.api.protocol.UniqueId;
import net.sf.briar.api.protocol.writers.AckWriter;
import net.sf.briar.api.protocol.writers.BatchWriter;
import net.sf.briar.api.protocol.writers.OfferWriter;
import net.sf.briar.api.protocol.writers.ProtocolWriterFactory;
import net.sf.briar.api.protocol.writers.RequestWriter;
import net.sf.briar.api.protocol.writers.SubscriptionWriter;
import net.sf.briar.api.protocol.writers.TransportWriter;
import net.sf.briar.api.transport.PacketReader;
import net.sf.briar.api.transport.PacketReaderFactory;
import net.sf.briar.api.transport.PacketWriter;
import net.sf.briar.api.transport.PacketWriterFactory;
import net.sf.briar.crypto.CryptoModule;
import net.sf.briar.protocol.ProtocolModule;
import net.sf.briar.protocol.writers.WritersModule;
import net.sf.briar.serial.SerialModule;
import net.sf.briar.transport.TransportModule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class FileReadWriteTest extends TestCase {

	private final File testDir = TestUtils.getTestDirectory();
	private final File file = new File(testDir, "foo");

	private final BatchId ack = new BatchId(TestUtils.getRandomId());
	private final long start = System.currentTimeMillis();

	private final PacketReaderFactory packetReaderFactory;
	private final PacketWriterFactory packetWriterFactory;
	private final ProtocolWriterFactory protocolWriterFactory;
	private final CryptoComponent crypto;
	private final byte[] secret = new byte[45];
	private final int transportId = 123;
	private final long connection = 234L;
	private final Author author;
	private final Group group, group1;
	private final Message message, message1, message2, message3;
	private final String authorName = "Alice";
	private final String messageBody = "Hello world";
	private final Map<String, Map<String, String>> transports;

	public FileReadWriteTest() throws Exception {
		super();
		Injector i = Guice.createInjector(new CryptoModule(),
				new ProtocolModule(), new SerialModule(), new TransportModule(),
				new WritersModule());
		packetReaderFactory = i.getInstance(PacketReaderFactory.class);
		packetWriterFactory = i.getInstance(PacketWriterFactory.class);
		protocolWriterFactory = i.getInstance(ProtocolWriterFactory.class);
		crypto = i.getInstance(CryptoComponent.class);
		assertEquals(crypto.getMessageDigest().getDigestLength(),
				UniqueId.LENGTH);
		// Create two groups: one restricted, one unrestricted
		GroupFactory groupFactory = i.getInstance(GroupFactory.class);
		group = groupFactory.createGroup("Unrestricted group", null);
		KeyPair groupKeyPair = crypto.generateKeyPair();
		group1 = groupFactory.createGroup("Restricted group",
				groupKeyPair.getPublic().getEncoded());
		// Create an author
		AuthorFactory authorFactory = i.getInstance(AuthorFactory.class);
		KeyPair authorKeyPair = crypto.generateKeyPair();
		author = authorFactory.createAuthor(authorName,
				authorKeyPair.getPublic().getEncoded());
		// Create two messages to each group: one anonymous, one pseudonymous
		MessageEncoder messageEncoder = i.getInstance(MessageEncoder.class);
		message = messageEncoder.encodeMessage(MessageId.NONE, group,
				messageBody.getBytes("UTF-8"));
		message1 = messageEncoder.encodeMessage(MessageId.NONE, group1,
				groupKeyPair.getPrivate(), messageBody.getBytes("UTF-8"));
		message2 = messageEncoder.encodeMessage(MessageId.NONE, group, author,
				authorKeyPair.getPrivate(), messageBody.getBytes("UTF-8"));
		message3 = messageEncoder.encodeMessage(MessageId.NONE, group1,
				groupKeyPair.getPrivate(), author, authorKeyPair.getPrivate(),
				messageBody.getBytes("UTF-8"));
		transports = Collections.singletonMap("foo",
				Collections.singletonMap("bar", "baz"));
	}

	@Before
	public void setUp() {
		testDir.mkdirs();
	}

	@Test
	public void testWriteFile() throws Exception {
		OutputStream out = new FileOutputStream(file);
		PacketWriter p = packetWriterFactory.createPacketWriter(out,
				transportId, connection, secret);
		out = p.getOutputStream();

		AckWriter a = protocolWriterFactory.createAckWriter(out);
		assertTrue(a.writeBatchId(ack));
		a.finish();
		p.nextPacket();

		BatchWriter b = protocolWriterFactory.createBatchWriter(out);
		assertTrue(b.writeMessage(message.getBytes()));
		assertTrue(b.writeMessage(message1.getBytes()));
		assertTrue(b.writeMessage(message2.getBytes()));
		assertTrue(b.writeMessage(message3.getBytes()));
		b.finish();
		p.nextPacket();

		OfferWriter o = protocolWriterFactory.createOfferWriter(out);
		assertTrue(o.writeMessageId(message.getId()));
		assertTrue(o.writeMessageId(message1.getId()));
		assertTrue(o.writeMessageId(message2.getId()));
		assertTrue(o.writeMessageId(message3.getId()));
		o.finish();
		p.nextPacket();

		RequestWriter r = protocolWriterFactory.createRequestWriter(out);
		BitSet requested = new BitSet(4);
		requested.set(1);
		requested.set(3);
		r.writeBitmap(requested, 4);
		p.nextPacket();

		SubscriptionWriter s =
			protocolWriterFactory.createSubscriptionWriter(out);
		// Use a LinkedHashMap for predictable iteration order
		Map<Group, Long> subs = new LinkedHashMap<Group, Long>();
		subs.put(group, 0L);
		subs.put(group1, 0L);
		s.writeSubscriptions(subs);
		p.nextPacket();

		TransportWriter t = protocolWriterFactory.createTransportWriter(out);
		t.writeTransports(transports);
		p.nextPacket();

		out.flush();
		out.close();
		assertTrue(file.exists());
		assertTrue(file.length() > message.getSize());
	}

	@Test
	public void testWriteAndReadFile() throws Exception {

		testWriteFile();

		FileInputStream in = new FileInputStream(file);
		byte[] firstTag = new byte[16];
		int offset = 0;
		while(offset < 16) {
			int read = in.read(firstTag, offset, firstTag.length - offset);
			if(read == -1) break;
			offset += read;
		}
		assertEquals(16, offset);
		PacketReader p = packetReaderFactory.createPacketReader(firstTag, in,
				transportId, connection, secret);

		// Read the ack
		assertTrue(p.hasAck());
		Ack a = p.readAck();
		assertEquals(Collections.singletonList(ack), a.getBatchIds());

		// Read the batch
		assertTrue(p.hasBatch());
		Batch b = p.readBatch();
		Collection<Message> messages = b.getMessages();
		assertEquals(4, messages.size());
		Iterator<Message> it = messages.iterator();
		checkMessageEquality(message, it.next());
		checkMessageEquality(message1, it.next());
		checkMessageEquality(message2, it.next());
		checkMessageEquality(message3, it.next());

		// Read the offer
		assertTrue(p.hasOffer());
		Offer o = p.readOffer();
		Collection<MessageId> offered = o.getMessageIds();
		assertEquals(4, offered.size());
		Iterator<MessageId> it1 = offered.iterator();
		assertEquals(message.getId(), it1.next());
		assertEquals(message1.getId(), it1.next());
		assertEquals(message2.getId(), it1.next());
		assertEquals(message3.getId(), it1.next());

		// Read the request
		assertTrue(p.hasRequest());
		Request r = p.readRequest();
		BitSet requested = r.getBitmap();
		assertFalse(requested.get(0));
		assertTrue(requested.get(1));
		assertFalse(requested.get(2));
		assertTrue(requested.get(3));
		// If there are any padding bits, they should all be zero
		assertEquals(2, requested.cardinality());

		// Read the subscription update
		assertTrue(p.hasSubscriptionUpdate());
		SubscriptionUpdate s = p.readSubscriptionUpdate();
		Map<Group, Long> subs = s.getSubscriptions();
		assertEquals(2, subs.size());
		assertEquals(Long.valueOf(0L), subs.get(group));
		assertEquals(Long.valueOf(0L), subs.get(group1));
		assertTrue(s.getTimestamp() > start);
		assertTrue(s.getTimestamp() <= System.currentTimeMillis());

		// Read the transport update
		assertTrue(p.hasTransportUpdate());
		TransportUpdate t = p.readTransportUpdate();
		assertEquals(transports, t.getTransports());
		assertTrue(t.getTimestamp() > start);
		assertTrue(t.getTimestamp() <= System.currentTimeMillis());
	}

	@After
	public void tearDown() {
		TestUtils.deleteTestDirectory(testDir);
	}

	private void checkMessageEquality(Message m1, Message m2) {
		assertEquals(m1.getId(), m2.getId());
		assertEquals(m1.getParent(), m2.getParent());
		assertEquals(m1.getGroup(), m2.getGroup());
		assertEquals(m1.getAuthor(), m2.getAuthor());
		assertEquals(m1.getTimestamp(), m2.getTimestamp());
		assertTrue(Arrays.equals(m1.getBytes(), m2.getBytes()));
	}
}