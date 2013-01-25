package net.sf.briar.protocol;

import static net.sf.briar.api.protocol.ProtocolConstants.MAX_BODY_LENGTH;
import static net.sf.briar.api.protocol.ProtocolConstants.MAX_PACKET_LENGTH;
import static net.sf.briar.api.protocol.ProtocolConstants.MAX_SIGNATURE_LENGTH;
import static net.sf.briar.api.protocol.ProtocolConstants.MAX_SUBJECT_LENGTH;
import static net.sf.briar.api.protocol.ProtocolConstants.SALT_LENGTH;
import static net.sf.briar.api.protocol.Types.AUTHOR;
import static net.sf.briar.api.protocol.Types.GROUP;
import static net.sf.briar.api.protocol.Types.MESSAGE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;

import net.sf.briar.api.clock.Clock;
import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.crypto.MessageDigest;
import net.sf.briar.api.protocol.Author;
import net.sf.briar.api.protocol.AuthorId;
import net.sf.briar.api.protocol.Group;
import net.sf.briar.api.protocol.GroupId;
import net.sf.briar.api.protocol.Message;
import net.sf.briar.api.protocol.MessageFactory;
import net.sf.briar.api.protocol.MessageId;
import net.sf.briar.api.serial.Consumer;
import net.sf.briar.api.serial.CountingConsumer;
import net.sf.briar.api.serial.DigestingConsumer;
import net.sf.briar.api.serial.SigningConsumer;
import net.sf.briar.api.serial.Writer;
import net.sf.briar.api.serial.WriterFactory;

import com.google.inject.Inject;

class MessageFactoryImpl implements MessageFactory {

	private final Signature authorSignature, groupSignature;
	private final SecureRandom random;
	private final MessageDigest messageDigest;
	private final WriterFactory writerFactory;
	private final Clock clock;

	@Inject
	MessageFactoryImpl(CryptoComponent crypto, WriterFactory writerFactory,
			Clock clock) {
		authorSignature = crypto.getSignature();
		groupSignature = crypto.getSignature();
		random = crypto.getSecureRandom();
		messageDigest = crypto.getMessageDigest();
		this.writerFactory = writerFactory;
		this.clock = clock;
	}

	public Message createMessage(MessageId parent, String subject, byte[] body)
	throws IOException, GeneralSecurityException {
		return createMessage(parent, null, null, null, null, subject, body);
	}

	public Message createMessage(MessageId parent, Group group, String subject,
			byte[] body) throws IOException, GeneralSecurityException {
		return createMessage(parent, group, null, null, null, subject, body);
	}

	public Message createMessage(MessageId parent, Group group,
			PrivateKey groupKey, String subject, byte[] body)
	throws IOException, GeneralSecurityException {
		return createMessage(parent, group, groupKey, null, null, subject,
				body);
	}

	public Message createMessage(MessageId parent, Group group, Author author,
			PrivateKey authorKey, String subject, byte[] body)
	throws IOException, GeneralSecurityException {
		return createMessage(parent, group, null, author, authorKey, subject,
				body);
	}

	public Message createMessage(MessageId parent, Group group,
			PrivateKey groupKey, Author author, PrivateKey authorKey,
			String subject, byte[] body) throws IOException,
			GeneralSecurityException {

		if((author == null) != (authorKey == null))
			throw new IllegalArgumentException();
		if((group == null || group.getPublicKey() == null)
				!= (groupKey == null))
			throw new IllegalArgumentException();
		if(subject.getBytes("UTF-8").length > MAX_SUBJECT_LENGTH)
			throw new IllegalArgumentException();
		if(body.length > MAX_BODY_LENGTH)
			throw new IllegalArgumentException();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Writer w = writerFactory.createWriter(out);
		// Initialise the consumers
		CountingConsumer counting = new CountingConsumer(MAX_PACKET_LENGTH);
		w.addConsumer(counting);
		Consumer digestingConsumer = new DigestingConsumer(messageDigest);
		w.addConsumer(digestingConsumer);
		Consumer authorConsumer = null;
		if(authorKey != null) {
			authorSignature.initSign(authorKey);
			authorConsumer = new SigningConsumer(authorSignature);
			w.addConsumer(authorConsumer);
		}
		Consumer groupConsumer = null;
		if(groupKey != null) {
			groupSignature.initSign(groupKey);
			groupConsumer = new SigningConsumer(groupSignature);
			w.addConsumer(groupConsumer);
		}
		// Write the message
		w.writeStructId(MESSAGE);
		if(parent == null) w.writeNull();
		else w.writeBytes(parent.getBytes());
		if(group == null) w.writeNull();
		else writeGroup(w, group);
		if(author == null) w.writeNull();
		else writeAuthor(w, author);
		w.writeString(subject);
		long timestamp = clock.currentTimeMillis();
		w.writeInt64(timestamp);
		byte[] salt = new byte[SALT_LENGTH];
		random.nextBytes(salt);
		w.writeBytes(salt);
		w.writeBytes(body);
		int bodyStart = (int) counting.getCount() - body.length;
		// Sign the message with the author's private key, if there is one
		if(authorKey == null) {
			w.writeNull();
		} else {
			w.removeConsumer(authorConsumer);
			byte[] sig = authorSignature.sign();
			if(sig.length > MAX_SIGNATURE_LENGTH)
				throw new IllegalArgumentException();
			w.writeBytes(sig);
		}
		// Sign the message with the group's private key, if there is one
		if(groupKey == null) {
			w.writeNull();
		} else {
			w.removeConsumer(groupConsumer);
			byte[] sig = groupSignature.sign();
			if(sig.length > MAX_SIGNATURE_LENGTH)
				throw new IllegalArgumentException();
			w.writeBytes(sig);
		}
		// Hash the message, including the signatures, to get the message ID
		w.removeConsumer(digestingConsumer);
		byte[] raw = out.toByteArray();
		MessageId id = new MessageId(messageDigest.digest());
		GroupId groupId = group == null ? null : group.getId();
		AuthorId authorId = author == null ? null : author.getId();
		return new MessageImpl(id, parent, groupId, authorId, subject,
				timestamp, raw, bodyStart, body.length);
	}

	private void writeGroup(Writer w, Group g) throws IOException {
		w.writeStructId(GROUP);
		w.writeString(g.getName());
		byte[] publicKey = g.getPublicKey();
		if(publicKey == null) w.writeNull();
		else w.writeBytes(publicKey);
	}

	private void writeAuthor(Writer w, Author a) throws IOException {
		w.writeStructId(AUTHOR);
		w.writeString(a.getName());
		w.writeBytes(a.getPublicKey());
	}
}