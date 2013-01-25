package net.sf.briar.protocol;

import static net.sf.briar.api.protocol.Types.AUTHOR;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.crypto.MessageDigest;
import net.sf.briar.api.protocol.Author;
import net.sf.briar.api.protocol.AuthorFactory;
import net.sf.briar.api.protocol.AuthorId;
import net.sf.briar.api.serial.Writer;
import net.sf.briar.api.serial.WriterFactory;

import com.google.inject.Inject;

class AuthorFactoryImpl implements AuthorFactory {

	private final CryptoComponent crypto;
	private final WriterFactory writerFactory;

	@Inject
	AuthorFactoryImpl(CryptoComponent crypto, WriterFactory writerFactory) {
		this.crypto = crypto;
		this.writerFactory = writerFactory;
	}

	public Author createAuthor(String name, byte[] publicKey)
			throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Writer w = writerFactory.createWriter(out);
		w.writeStructId(AUTHOR);
		w.writeString(name);
		w.writeBytes(publicKey);
		MessageDigest messageDigest = crypto.getMessageDigest();
		messageDigest.update(out.toByteArray());
		AuthorId id = new AuthorId(messageDigest.digest());
		return new Author(id, name, publicKey);
	}
}