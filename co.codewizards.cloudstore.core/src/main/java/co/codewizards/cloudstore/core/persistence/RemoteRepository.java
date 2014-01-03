package co.codewizards.cloudstore.core.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;

import javax.jdo.annotations.Column;
import javax.jdo.annotations.Discriminator;
import javax.jdo.annotations.DiscriminatorStrategy;
import javax.jdo.annotations.Inheritance;
import javax.jdo.annotations.InheritanceStrategy;
import javax.jdo.annotations.NullValue;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.Query;
import javax.jdo.annotations.Unique;

import co.codewizards.cloudstore.core.dto.EntityID;
import co.codewizards.cloudstore.core.util.HashUtil;
import co.codewizards.cloudstore.core.util.IOUtil;

@PersistenceCapable
@Inheritance(strategy=InheritanceStrategy.SUPERCLASS_TABLE)
@Discriminator(strategy=DiscriminatorStrategy.VALUE_MAP, value="RemoteRepository")
//@Unique(name="RemoteRepository_remoteRoot", members="remoteRoot") // Indexing a CLOB with Derby throws an exception :-( [should be a warning, IMHO for portability reasons]
@Unique(name="RemoteRepository_remoteRootSha1", members="remoteRootSha1")
@Query(name="getRemoteRepository_remoteRootSha1", value="SELECT UNIQUE WHERE this.remoteRootSha1 == :remoteRootSha1")
public class RemoteRepository extends Repository {

	@Persistent(nullValue=NullValue.EXCEPTION)
	@Column(jdbcType="CLOB")
	private URL remoteRoot;

	@Persistent(nullValue=NullValue.EXCEPTION)
	private String remoteRootSha1;

	public RemoteRepository() { }

	public RemoteRepository(EntityID entityID) {
		super(entityID);
	}

	public URL getRemoteRoot() {
		return remoteRoot;
	}

	public void setRemoteRoot(URL remoteRoot) {
		this.remoteRoot = remoteRoot;
		this.remoteRootSha1 = sha1(remoteRoot);
	}

	public static String sha1(URL remoteRoot) {
		if (remoteRoot == null)
			return null;

		byte[] remoteRootBytes = remoteRoot.toExternalForm().getBytes(IOUtil.CHARSET_UTF_8);
		byte[] hash;
		try {
			hash = HashUtil.hash(HashUtil.HASH_ALGORITHM_SHA, new ByteArrayInputStream(remoteRootBytes));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return HashUtil.encodeHexStr(hash, 0, hash.length);
	}

	public String getRemoteRootSha1() {
		return remoteRootSha1;
	}
}