package co.codewizards.cloudstore.client;

import java.net.URL;
import java.util.UUID;

import org.kohsuke.args4j.Argument;

import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManagerFactory;
import co.codewizards.cloudstore.core.repo.transport.RepoTransport;
import co.codewizards.cloudstore.core.repo.transport.RepoTransportFactoryRegistry;
import co.codewizards.cloudstore.core.util.HashUtil;

/**
 * {@link SubCommand} implementation for requesting a connection at a remote repository.
 *
 * @author Marco หงุ่ยตระกูล-Schulze - marco at nightlabs dot de
 */
public class RequestRepoConnectionSubCommand extends SubCommandWithExistingLocalRepo
{
	@Argument(metaVar="<remote>", index=1, required=true, usage="A URL to a remote repository. This may be the remote repository's root or any sub-directory. If a sub-directory is specified here, only this sub-directory is connected with the local repository.")
	private String remote;

	private URL remoteURL;

	@Override
	public String getSubCommandDescription() {
		return "Request a remote repository to allow a connection with a local repository.";
	}

	@Override
	public void prepare() throws Exception {
		super.prepare();

		remoteURL = new URL(remote);
	}

	@Override
	public void run() throws Exception {
		UUID localRepositoryId;
		UUID remoteRepositoryId;
		byte[] localPublicKey;
		byte[] remotePublicKey;
//		String remotePathPrefix;
		try (
			final LocalRepoManager localRepoManager = LocalRepoManagerFactory.Helper.getInstance().createLocalRepoManagerForExistingRepository(localRoot);
		) {
			localRepositoryId = localRepoManager.getRepositoryId();
			localPublicKey = localRepoManager.getPublicKey();
			try (
				final RepoTransport repoTransport = RepoTransportFactoryRegistry.getInstance().getRepoTransportFactory(remoteURL).createRepoTransport(remoteURL, localRepositoryId);
			) {
				remoteRepositoryId = repoTransport.getRepositoryId();
				remotePublicKey = repoTransport.getPublicKey();
//				remotePathPrefix = repoTransport.getPathPrefix();
				localRepoManager.putRemoteRepository(remoteRepositoryId, remoteURL, remotePublicKey, localPathPrefix);
				repoTransport.requestRepoConnection(localRepoManager.getPublicKey());
			}
		}

		System.out.println("Successfully requested to connect the following local and remote repositories:");
		System.out.println();
		System.out.println("  localRepository.repositoryId = " + localRepositoryId);
		System.out.println("  localRepository.localRoot = " + localRoot);
		System.out.println("  localRepository.publicKeySha1 = " + HashUtil.sha1ForHuman(localPublicKey));
		System.out.println();
		System.out.println("  remoteRepository.repositoryId = " + remoteRepositoryId);
		System.out.println("  remoteRepository.remoteRoot = " + remoteURL);
		System.out.println("  remoteRepository.publicKeySha1 = " + HashUtil.sha1ForHuman(remotePublicKey));
		System.out.println();
//		System.out.println("  localPathPrefix = " + localPathPrefix);
//		System.out.println("  remotePathPrefix = " + remotePathPrefix);
//		System.out.println();
		System.out.println("Please verify the 'publicKeySha1' fingerprints! If they do not match the fingerprints shown on the server, someone is attacking you and you must cancel this request immediately! To cancel the request, use this command:");
		System.out.println();
		System.out.println(String.format("  cloudstore dropRepoConnection %s %s", localRepositoryId, remoteRepositoryId));
	}
}
