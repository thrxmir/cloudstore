package co.codewizards.cloudstore.rest.client.request;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.util.UUID;

import javax.ws.rs.core.MediaType;

import co.codewizards.cloudstore.core.auth.EncryptedSignedAuthToken;
import co.codewizards.cloudstore.core.util.AssertUtil;

public class GetEncryptedSignedAuthToken extends AbstractRequest<EncryptedSignedAuthToken> {

	private final String repositoryName;
	private final UUID clientRepositoryId;

	public GetEncryptedSignedAuthToken(final String repositoryName, final UUID clientRepositoryId) {
		this.repositoryName = AssertUtil.assertNotNull("repositoryName", repositoryName);
		this.clientRepositoryId = AssertUtil.assertNotNull("clientRepositoryId", clientRepositoryId);
	}

	@Override
	public EncryptedSignedAuthToken execute() {
		final EncryptedSignedAuthToken encryptedSignedAuthToken = createWebTarget(
				getPath(EncryptedSignedAuthToken.class), urlEncode(repositoryName), clientRepositoryId.toString())
				.request(MediaType.APPLICATION_XML).get(EncryptedSignedAuthToken.class);
		return encryptedSignedAuthToken;
	}

	@Override
	public boolean isResultNullable() {
		return false;
	}
}
