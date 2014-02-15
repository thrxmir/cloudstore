package co.codewizards.cloudstore.core;

import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Assert;
import org.junit.Before;

import co.codewizards.cloudstore.core.config.ConfigDir;
import co.codewizards.cloudstore.core.persistence.RepoFile;
import co.codewizards.cloudstore.core.persistence.RepoFileDAO;
import co.codewizards.cloudstore.core.repo.local.FilenameFilterSkipMetaDir;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManagerFactory;
import co.codewizards.cloudstore.core.repo.local.LocalRepoTransaction;
import co.codewizards.cloudstore.core.util.IOUtil;

public abstract class AbstractTest {

	static {
		System.setProperty(ConfigDir.SYSTEM_PROPERTY_CONFIG_DIR, "target/.cloudstore");
		System.setProperty(LocalRepoManager.SYSTEM_PROPERTY_KEY_SIZE, "1024");
	}

	protected static final Random random = new Random();
	protected static LocalRepoManagerFactory localRepoManagerFactory = LocalRepoManagerFactory.getInstance();
	private Map<File, Set<File>> localRoot2FilesInRepo = new HashMap<File, Set<File>>();

	protected File newTestRepositoryLocalRoot(String suffix) throws IOException {
		assertThat(suffix).isNotNull();
		long timestamp = System.currentTimeMillis();
		int randomNumber = random.nextInt(BigInteger.valueOf(36).pow(5).intValue());
		String repoName = Long.toString(timestamp, 36) + '-' + Integer.toString(randomNumber, 36) + (suffix.isEmpty() ? "" : "-") + suffix;
		File localRoot = new File(getTestRepositoryBaseDir(), repoName);
		addToFilesInRepo(localRoot, localRoot);
		return localRoot;
	}

	protected File getTestRepositoryBaseDir() {
		File dir = new File(new File("target"), "repo");
		dir.mkdirs();
		return dir;
	}

	@Before
	public void before() {
		localRoot2FilesInRepo.clear();
	}

	protected File createDirectory(File parent, String name) throws IOException {
		File dir = new File(parent, name);
		return createDirectory(dir);
	}
	protected File createDirectory(File dir) throws IOException {
		assertThat(dir).doesNotExist();
		dir.mkdir();
		assertThat(dir).isDirectory();
		addToFilesInRepo(dir);
		return dir;
	}

	protected void addToFilesInRepo(File file) throws IOException {
		file = file.getAbsoluteFile();
		File localRoot = getLocalRootOrFail(file);
		addToFilesInRepo(localRoot, file);
	}
	protected void addToFilesInRepo(File localRoot, File file) throws IOException {
		localRoot = localRoot.getCanonicalFile();
		file = file.getAbsoluteFile();
		Set<File> filesInRepo = localRoot2FilesInRepo.get(localRoot);
		if (filesInRepo == null) {
			filesInRepo = new HashSet<File>();
			localRoot2FilesInRepo.put(localRoot, filesInRepo);
		}
		filesInRepo.add(file);
	}

	protected File createFileWithRandomContent(File parent, String name) throws IOException {
		File file = new File(parent, name);
		return createFileWithRandomContent(file);
	}

	protected File createFileWithRandomContent(File file) throws IOException {
		assertThat(file).doesNotExist(); // prevent accidentally overwriting important data ;-)
		OutputStream out = new FileOutputStream(file);
		byte[] buf = new byte[1 + random.nextInt(10241)];
		int loops = 1 + random.nextInt(100);
		for (int i = 0; i < loops; ++i) {
			random.nextBytes(buf);
			out.write(buf);
		}
		out.close();
		assertThat(file).isFile();
		addToFilesInRepo(file);
		return file;
	}

	protected void deleteFile(File file) throws IOException {
		file = file.getAbsoluteFile();
		assertThat(file).exists();
		file.delete();
		assertThat(file).doesNotExist();

		File localRoot = getLocalRootOrFail(file);
		Set<File> filesInRepo = localRoot2FilesInRepo.get(localRoot);
		if (filesInRepo == null)
			throw new IllegalStateException("No filesInRepo for localRoot: " + localRoot);

		if (!filesInRepo.remove(file))
			throw new IllegalStateException("File did not exist in filesInRepo: " + file);
	}

	private File getLocalRootOrFail(File file) throws IOException {
		String filePath = file.getCanonicalPath();
		Set<File> localRoots = localRepoManagerFactory.getLocalRoots();
		for (File localRoot : localRoots) {
			String localRootPath = localRoot.getPath();
			if (filePath.startsWith(localRootPath)) {
				return localRoot;
			}
		}
		throw new IllegalArgumentException("file is not contained in any open repository: " + filePath);
	}

	protected void assertThatFilesInRepoAreCorrect(File localRoot) {
		LocalRepoManager localRepoManager = LocalRepoManagerFactory.getInstance().createLocalRepoManagerForExistingRepository(localRoot);
		localRoot = localRepoManager.getLocalRoot(); // get canonical File
		LocalRepoTransaction transaction = localRepoManager.beginReadTransaction();
		try {
			RepoFileDAO repoFileDAO = transaction.getDAO(RepoFileDAO.class);
			Set<File> filesInRepo = localRoot2FilesInRepo.get(localRoot);
			assertThat(filesInRepo).isNotNull();

			for (File file : filesInRepo) {
				RepoFile repoFile = repoFileDAO.getRepoFile(localRoot, file);
				if (repoFile == null) {
					Assert.fail("Corresponding RepoFile missing in repository for file: " + file);
				}
			}

			filesInRepo = new HashSet<File>(filesInRepo);
			Collection<RepoFile> repoFiles = repoFileDAO.getObjects();
			Map<File, RepoFile> file2RepoFile = new HashMap<File, RepoFile>();
			for (RepoFile repoFile : repoFiles) {
				File file = repoFile.getFile(localRoot);
				RepoFile duplicateRepoFile = file2RepoFile.put(file, repoFile);
				if (duplicateRepoFile != null)
					Assert.fail("There are 2 RepoFile instances for the same file! " + repoFile + " " + duplicateRepoFile + " " + file);

				if (!filesInRepo.remove(file))
					Assert.fail("Corresponding file in file-system missing for RepoFile: " + repoFile + " " + file);
			}
		} finally {
			transaction.rollbackIfActive();
			localRepoManager.close();
		}
	}

	protected void assertDirectoriesAreEqualRecursively(File dir1, File dir2) throws IOException {
		assertThat(dir1).isDirectory();
		assertThat(dir2).isDirectory();

		String[] children1 = dir1.list(new FilenameFilterSkipMetaDir());
		assertThat(children1).isNotNull();

		String[] children2 = dir2.list(new FilenameFilterSkipMetaDir());
		assertThat(children2).isNotNull();

		Arrays.sort(children1);
		Arrays.sort(children2);

		assertThat(children1).containsOnly(children2);

		for (String childName : children1) {
			File child1 = new File(dir1, childName);
			File child2 = new File(dir2, childName);

			if (child1.isFile()) {
				assertThat(child2.isFile());
				assertThat(IOUtil.compareFiles(child1, child2)).isTrue();
			}

			if (child1.isDirectory())
				assertDirectoriesAreEqualRecursively(child1, child2);
		}
	}

}
