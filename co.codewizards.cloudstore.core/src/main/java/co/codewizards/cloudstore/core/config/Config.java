package co.codewizards.cloudstore.core.config;

import static co.codewizards.cloudstore.core.oio.OioFileFactory.*;
import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import static co.codewizards.cloudstore.core.util.PropertiesUtil.*;
import static co.codewizards.cloudstore.core.util.StringUtil.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.appid.AppIdRegistry;
import co.codewizards.cloudstore.core.io.LockFile;
import co.codewizards.cloudstore.core.io.LockFileFactory;
import co.codewizards.cloudstore.core.oio.File;
import co.codewizards.cloudstore.core.repo.local.LocalRepoHelper;
import co.codewizards.cloudstore.core.util.AssertUtil;

/**
 * Configuration of CloudStore supporting inheritance of settings.
 * <p>
 * There is one {@code Config} instance available (lazily created, cached temporarily) for every
 * directory and every file in a repository. Each {@code Config} inherits the settings from the
 * parent-directory, if not explicitly overwritten.
 * <p>
 * The configuration is based on {@link Properties} files. Every property file is optional. If it
 * does not exist, all settings are inherited. If it does exist, only those properties contained in
 * the file are overriden. All properties not contained in the file are still inherited. Inheritance
 * is thus applicable on every individual property.
 * <p>
 * Modifications, deletions, creations of properties files are detected during runtime (pretty immediately).
 * Note, that this detection is based on the files' timestamps. Since most file systems have a granularity
 * of 1 second (some even 2) for the last-modified-timestamp, multiple modifications in the same second might
 * not be detected.
 * <p>
 * There is a global properties file in the user's home directory (or wherever {@link ConfigDir}
 * points to): <code>&#36;{user.home}/.cloudstore/cloudstore.properties</code>
 * <p>
 * Additionally, every directory can optionally contain the following files:
 * <ol>
 * <li><code>.cloudstore.properties</code>
 * <li><code>cloudstore.properties</code>
 * <li><code>.&#36;{anyFileName}.cloudstore.properties</code>
 * <li><code>&#36;{anyFileName}.cloudstore.properties</code>
 * </ol>
 * <p>
 * The files 1. and 2. are applicable to the entire directory and all sub-directories and files in it.
 * Usually, on GNU/Linux people will prefer 1., but when using Windows, files starting with a "." are
 * sometimes a bit hard to deal with. Therefore, we support both. The file 2. overrides the settings of file 1..
 * <p>
 * The files 3. and 4. are applicable only to the file <code>&#36;{anyFileName}</code>. Thus, if you want
 * to set special behaviour for the file <code>example.db</code> only, you can create the file
 * <code>.example.db.cloudstore.properties</code> in the same directory.
 *
 * @author Marco หงุ่ยตระกูล-Schulze - marco at codewizards dot co
 */
public class Config {
	private static final Logger logger = LoggerFactory.getLogger(Config.class);

	private static final String APP_ID_SIMPLE_ID = AppIdRegistry.getInstance().getAppIdOrFail().getSimpleId();

	private static final long fileRefsCleanPeriod = 60000L;
	private static long fileRefsCleanLastTimestamp;

	private static final String PROPERTIES_FILE_NAME_FOR_DIRECTORY_HIDDEN = '.' + APP_ID_SIMPLE_ID + ".properties";
	private static final String PROPERTIES_FILE_NAME_FOR_DIRECTORY_VISIBLE = APP_ID_SIMPLE_ID + ".properties";

	private static final String PROPERTIES_TEMPLATE_FILE_NAME = "cloudstore.properties"; // *NOT* dependent on AppId!

	private static final String PROPERTIES_FILE_FORMAT_FOR_FILE_HIDDEN = ".%s." + APP_ID_SIMPLE_ID + ".properties";
	private static final String PROPERTIES_FILE_FORMAT_FOR_FILE_VISIBLE = "%s." + APP_ID_SIMPLE_ID + ".properties";

	private static final String TRUE_STRING = Boolean.TRUE.toString();
	private static final String FALSE_STRING = Boolean.FALSE.toString();

	/**
	 * Prefix used for system properties overriding configuration entries.
	 * <p>
	 * Every property in the configuration (i.e. in its properties files) can be overridden
	 * by a corresponding system property. The system property must be prefixed.
	 * <p>
	 * For example, to override the configuration property with the key "deferrableExecutor.timeout",
	 * you can pass the system property "cloudstore.deferrableExecutor.timeout" to the JVM. If the
	 * system property exists, the configuration is not consulted, but the system property value is
	 * used as shortcut.
	 * <p>
	 * Additionally, it is possible to override configuration entries via OS environment variables.
	 * Since an env var's name must not contain a dot ("."), all dots are replaced by underscores ("_").
	 */
	public static final String SYSTEM_PROPERTY_PREFIX = APP_ID_SIMPLE_ID + '.';

	private static final LinkedHashSet<File> fileHardRefs = new LinkedHashSet<>();
	private static final int fileHardRefsMaxSize = 30;
	/**
	 * {@link SoftReference}s to the files used in {@link #file2Config}.
	 * <p>
	 * There is no {@code SoftHashMap}, hence we use a WeakHashMap combined with the {@code SoftReference}s here.
	 * @see #file2Config
	 */
	private static final LinkedList<SoftReference<File>> fileSoftRefs = new LinkedList<>();
	/**
	 * @see #fileSoftRefs
	 */
	private static final Map<File, Config> file2Config = new WeakHashMap<File, Config>();

	private static final class ConfigHolder {
		public static final Config instance = new Config(
				null, null,
				new File[] { createFile(ConfigDir.getInstance().getFile(), PROPERTIES_FILE_NAME_FOR_DIRECTORY_VISIBLE) });
	}

	private final Config parentConfig;
	private final WeakReference<File> fileRef;
	protected final File[] propertiesFiles;
	private final long[] propertiesFilesLastModified;
	private final Properties properties;

	private static final Object classMutex = Config.class;
	private final Object instanceMutex;

	protected Config(final Config parentConfig, final File file, final File [] propertiesFiles) {
		this.parentConfig = parentConfig;

		if (parentConfig == null)
			fileRef = null;
		else
			fileRef = new WeakReference<File>(AssertUtil.assertNotNull("file", file));

		this.propertiesFiles = AssertUtil.assertNotNullAndNoNullElement("propertiesFiles", propertiesFiles);
		properties = new Properties(parentConfig == null ? null : parentConfig.properties);
		propertiesFilesLastModified = new long[propertiesFiles.length];
		instanceMutex = properties;

		// Create the default global configuration (it's an empty template with some comments).
		if (parentConfig == null && !propertiesFiles[0].exists()) {
			try {
				AppIdRegistry.getInstance().copyResourceResolvingAppId(
						Config.class, "/" + PROPERTIES_TEMPLATE_FILE_NAME, propertiesFiles[0]);
			} catch (final IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Get the directory or file for which this Config instance is responsible.
	 * @return the directory or file for which this Config instance is responsible. Might be <code>null</code>, if already
	 * garbage-collected or if this is the root-parent-Config. We try to make garbage-collection extremely unlikely
	 * as long as the Config is held in memory.
	 */
	protected File getFile() {
		return fileRef == null ? null : fileRef.get();
	}

	private static void cleanFileRefs() {
		synchronized (classMutex) {
			if (System.currentTimeMillis() - fileRefsCleanLastTimestamp < fileRefsCleanPeriod)
				return;

			for (final Iterator<SoftReference<File>> it = fileSoftRefs.iterator(); it.hasNext(); ) {
				final SoftReference<File> fileRef = it.next();
				if (fileRef.get() == null)
					it.remove();
			}
			fileRefsCleanLastTimestamp = System.currentTimeMillis();
		}
	}

	/**
	 * Gets the global {@code Config} for the current user.
	 * @return the global {@code Config} for the current user. Never <code>null</code>.
	 */
	public static Config getInstance() {
		return ConfigHolder.instance;
	}

	/**
	 * Gets the {@code Config} for the given {@code directory}.
	 * @param directory a directory inside a repository. Must not be <code>null</code>.
	 * The directory does not need to exist (it may be created later).
	 * @return the {@code Config} for the given {@code directory}. Never <code>null</code>.
	 */
	public static Config getInstanceForDirectory(final File directory) {
		return getInstance(directory, true);
	}

	/**
	 * Gets the {@code Config} for the given {@code file}.
	 * @param file a file inside a repository. Must not be <code>null</code>.
	 * The file does not need to exist (it may be created later).
	 * @return the {@code Config} for the given {@code file}. Never <code>null</code>.
	 */
	public static Config getInstanceForFile(final File file) {
		return getInstance(file, false);
	}

	private static Config getInstance(final File file, final boolean isDirectory) {
		AssertUtil.assertNotNull("file", file);
		cleanFileRefs();

		File config_file = null;
		Config config;
		synchronized (classMutex) {
			config = file2Config.get(file);
			if (config != null) {
				config_file = config.getFile();
				if (config_file == null) // very unlikely, but it actually *can* happen.
					config = null; // we try to make it extremely probable that the Config we return does have a valid file reference.
			}

			if (config == null) {
				final File localRoot = LocalRepoHelper.getLocalRootContainingFile(file);
				if (localRoot == null)
					throw new IllegalArgumentException("file is not inside a repository: " + file.getAbsolutePath());

				final Config parentConfig = localRoot == file ? getInstance() : getInstance(file.getParentFile(), true);
				config = new Config(parentConfig, file, createPropertiesFiles(file, isDirectory));
				file2Config.put(file, config);
				fileSoftRefs.add(new SoftReference<File>(file));
				config_file = config.getFile();
			}
			AssertUtil.assertNotNull("config_file", config_file);
		}
		refreshFileHardRefAndCleanOldHardRefs(config_file);
		return config;
	}

	private static File[] createPropertiesFiles(final File file, final boolean isDirectory) {
		if (isDirectory) {
			return new File[] {
				createFile(file, PROPERTIES_FILE_NAME_FOR_DIRECTORY_HIDDEN),
				createFile(file, PROPERTIES_FILE_NAME_FOR_DIRECTORY_VISIBLE)
			};
		}
		else {
			return new File[] {
				createFile(file.getParentFile(), String.format(PROPERTIES_FILE_FORMAT_FOR_FILE_HIDDEN, file.getName())),
				createFile(file.getParentFile(), String.format(PROPERTIES_FILE_FORMAT_FOR_FILE_VISIBLE, file.getName()))
			};
		}
	}

	private Config readIfNeeded() {
		synchronized (instanceMutex) {
			for (int i = 0; i < propertiesFiles.length; i++) {
				final File propertiesFile = propertiesFiles[i];
				final long lastModified = propertiesFilesLastModified[i];
				if (propertiesFile.lastModified() != lastModified) {
					read();
					break;
				}
			}
		}

		if (parentConfig != null)
			parentConfig.readIfNeeded();

		return this;
	}

	private void read() {
		synchronized (instanceMutex) {
			logger.trace("read: Entered instanceMutex.");
			try {
				properties.clear();
				for (int i = 0; i < propertiesFiles.length; i++) {
					final File propertiesFile = propertiesFiles[i];
					logger.debug("read: Reading propertiesFile '{}'.", propertiesFile.getAbsolutePath());
					final long lastModified = getLastModifiedAndWaitIfNeeded(propertiesFile);
					if (propertiesFile.exists()) { // prevent the properties file from being modified while we're reading it.
						try ( LockFile lockFile = LockFileFactory.getInstance().acquire(propertiesFile, 10000); ) { // TODO maybe system property for timeout?
							final InputStream in = lockFile.createInputStream();
							try {
								properties.load(in);
							} finally {
								in.close();
							}
						}
					}
					propertiesFilesLastModified[i] = lastModified;
				}
			} catch (final IOException e) {
				properties.clear();
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Gets the {@link File#lastModified() lastModified} timestamp of the given {@code file}
	 * and waits if needed.
	 * <p>
	 * Waiting is needed, if the modification's age is shorter than the file system's time granularity.
	 * Since we do not know the file system's time granularity, we assume 2 seconds. Thus, if the file
	 * was changed e.g. 600 ms before invoking this method, the method will wait for 1400 ms to make sure
	 * the modification is at least as old as the assumed file system's temporal granularity.
	 * <p>
	 * This waiting strategy makes sure that a future modification of the file, after the file was read,
	 * is reliably detected - causing the file to be read again.
	 * @param file the file whose {@link File#lastModified() lastModified} timestamp to obtain. Must not be <code>null</code>.
	 * @return the {@link File#lastModified() lastModified} timestamp. 0, if the specified {@code file}
	 * does not exist.
	 */
	private long getLastModifiedAndWaitIfNeeded(final File file) {
		assertNotNull("file", file);
		long lastModified = file.lastModified(); // is 0 for non-existing file
		final long now = System.currentTimeMillis();

		// Check and handle timestamp in the future.
		if (lastModified > now) {
			file.setLastModified(now);
			logger.warn("getLastModifiedAndWaitIfNeeded: lastModified of '{}' was in the future! Changed it to now!", file.getAbsolutePath());

			lastModified = file.lastModified();
			if (lastModified > now) {
				logger.error("getLastModifiedAndWaitIfNeeded: lastModified of '{}' is in the future! Changing it FAILED! Permissions?!", file.getAbsolutePath());
				return lastModified;
			}
		}

		// Wait, if the modification is not yet older than the file system's (assumed!) granularity.
		// No file system should have a granularity worse than 2 seconds. Waiting max. 2 seconds in this use-case
		// in this rare situation is acceptable. After all, this is a config file which isn't changed often.
		final long fileSystemTemporalGranularity = 2000; // TODO maybe make this configurable?! Warning: we are in the config here - accessing the config is thus not so easy (=> recursion).
		final long modificationAge = now - lastModified;
		final long waitPeriod = fileSystemTemporalGranularity - modificationAge;
		if (waitPeriod > 0) {
			logger.info("getLastModifiedAndWaitIfNeeded: Waiting {} ms.", waitPeriod);
			try { Thread.sleep(waitPeriod); } catch (InterruptedException e) { }
		}

		return lastModified;
	}

	/**
	 * Gets the property identified by the given key.
	 * <p>
	 * This method directly delegates to {@link Properties#getProperty(String, String)}.
	 * Thus, an empty String in the internal {@code Properties} is returned instead of the
	 * given {@code defaultValue}. The {@code defaultValue} is only returned, if neither
	 * the internal {@code Properties} of this {@code Config} nor any of its parents contains
	 * the entry.
	 * <p>
	 * <b>Important:</b> This is often not the desired behaviour. You might want to use
	 * {@link #getPropertyAsNonEmptyTrimmedString(String, String)} instead!
	 * <p>
	 * Every property can be overwritten by a system property prefixed with {@value #SYSTEM_PROPERTY_PREFIX}.
	 * If - for example - the key "updater.force" is to be read and a system property
	 * named "cloudstore.updater.force" is set, this system property is returned instead!
	 * @param key the key identifying the property. Must not be <code>null</code>.
	 * @param defaultValue the default value to fall back to, if neither this {@code Config}'s
	 * internal {@code Properties} nor any of its parents contains a matching entry.
	 * May be <code>null</code>.
	 * @return the property's value. Never <code>null</code> unless {@code defaultValue} is <code>null</code>.
	 * @see #getPropertyAsNonEmptyTrimmedString(String, String)
	 */
	public String getProperty(final String key, final String defaultValue) {
		AssertUtil.assertNotNull("key", key);
		refreshFileHardRefAndCleanOldHardRefs();

		final String sysPropKey = SYSTEM_PROPERTY_PREFIX + key;
		final String sysPropVal = System.getProperty(sysPropKey);
		if (sysPropVal != null) {
			logger.debug("getProperty: System property with key='{}' and value='{}' overrides config (config is not queried).", sysPropKey, sysPropVal);
			return sysPropVal;
		}

		final String envVarKey = systemPropertyToEnvironmentVariable(sysPropKey);
		final String envVarVal = System.getenv(envVarKey);
		if (envVarVal != null) {
			logger.debug("getProperty: Environment variable with key='{}' and value='{}' overrides config (config is not queried).", envVarKey, envVarVal);
			return envVarVal;
		}

		logger.debug("getProperty: System property with key='{}' is not set (config is queried next).", sysPropKey);

		synchronized (instanceMutex) {
			readIfNeeded();
			return properties.getProperty(key, defaultValue);
		}
	}

	/**
	 * Gets the property identified by the given key; {@linkplain String#trim() trimmed}.
	 * <p>
	 * In contrast to {@link #getProperty(String, String)}, this method falls back to the given
	 * {@code defaultValue}, if the internal {@code Properties} contains an empty {@code String}
	 * (after trimming) as value for the given {@code key}.
	 * <p>
	 * It therefore means that a value set to an empty {@code String} in the properties file means
	 * to use the program's default instead. It is therefore consistent with
	 * {@link #getPropertyAsLong(String, long)} and all other {@code getPropertyAs...(...)}
	 * methods.
	 * <p>
	 * The same rules apply to the fall-back-strategy from system property to environment variable and
	 * finally config files.
	 * <p>
	 * Every property can be overwritten by a system property prefixed with {@value #SYSTEM_PROPERTY_PREFIX}.
	 * If - for example - the key "updater.force" is to be read and a system property
	 * named "cloudstore.updater.force" is set, this system property is returned instead!
	 * @param key the key identifying the property. Must not be <code>null</code>.
	 * @param defaultValue the default value to fall back to, if neither this {@code Config}'s
	 * internal {@code Properties} nor any of its parents contains a matching entry or
	 * if this entry's value is an empty {@code String}.
	 * May be <code>null</code>.
	 * @return the property's value. Never <code>null</code> unless {@code defaultValue} is <code>null</code>.
	 */
	public String getPropertyAsNonEmptyTrimmedString(final String key, final String defaultValue) {
		AssertUtil.assertNotNull("key", key);
		refreshFileHardRefAndCleanOldHardRefs();

		final String sysPropKey = SYSTEM_PROPERTY_PREFIX + key;
		final String sysPropVal = trim(System.getProperty(sysPropKey));
		if (! isEmpty(sysPropVal)) {
			logger.debug("getPropertyAsNonEmptyTrimmedString: System property with key='{}' and value='{}' overrides config (config is not queried).", sysPropKey, sysPropVal);
			return sysPropVal;
		}

		final String envVarKey = systemPropertyToEnvironmentVariable(sysPropKey);
		final String envVarVal = trim(System.getenv(envVarKey));
		if (! isEmpty(envVarVal)) {
			logger.debug("getPropertyAsNonEmptyTrimmedString: Environment variable with key='{}' and value='{}' overrides config (config is not queried).", envVarKey, envVarVal);
			return envVarVal;
		}

		logger.debug("getPropertyAsNonEmptyTrimmedString: System property with key='{}' is not set (config is queried next).", sysPropKey);

		synchronized (instanceMutex) {
			readIfNeeded();
			String sval = trim(properties.getProperty(key));
			if (isEmpty(sval))
				return defaultValue;

			return sval;
		}
	}

	public long getPropertyAsLong(final String key, final long defaultValue) {
		final String sval = getPropertyAsNonEmptyTrimmedString(key, null);
		if (sval == null)
			return defaultValue;

		try {
			final long lval = Long.parseLong(sval);
			return lval;
		} catch (final NumberFormatException x) {
			logger.warn("getPropertyAsLong: One of the properties files %s contains the key '%s' (or the system properties override it) with the illegal value '%s'. Falling back to default value '%s'!", propertiesFiles, key, sval, defaultValue);
			return defaultValue;
		}
	}

	public long getPropertyAsPositiveOrZeroLong(final String key, final long defaultValue) {
		final long value = getPropertyAsLong(key, defaultValue);
		if (value < 0) {
			logger.warn("getPropertyAsPositiveOrZeroLong: One of the properties files %s contains the key '%s' (or the system properties override it) with the negative value '%s' (only values >= 0 are allowed). Falling back to default value '%s'!", propertiesFiles, key, value, defaultValue);
			return defaultValue;
		}
		return value;
	}

	public int getPropertyAsInt(final String key, final int defaultValue) {
		final String sval = getPropertyAsNonEmptyTrimmedString(key, null);
		if (sval == null)
			return defaultValue;

		try {
			final int ival = Integer.parseInt(sval);
			return ival;
		} catch (final NumberFormatException x) {
			logger.warn("getPropertyAsInt: One of the properties files %s contains the key '%s' (or the system properties override it) with the illegal value '%s'. Falling back to default value '%s'!", propertiesFiles, key, sval, defaultValue);
			return defaultValue;
		}
	}

	public int getPropertyAsPositiveOrZeroInt(final String key, final int defaultValue) {
		final int value = getPropertyAsInt(key, defaultValue);
		if (value < 0) {
			logger.warn("getPropertyAsPositiveOrZeroInt: One of the properties files %s contains the key '%s' (or the system properties override it) with the negative value '%s' (only values >= 0 are allowed). Falling back to default value '%s'!", propertiesFiles, key, value, defaultValue);
			return defaultValue;
		}
		return value;
	}

	/**
	 * Gets the property identified by the given key.
	 * @param key the key identifying the property. Must not be <code>null</code>.
	 * @param defaultValue the default value to fall back to, if neither this {@code Config}'s
	 * internal {@code Properties} nor any of its parents contains a matching entry or
	 * if this entry's value does not match any possible enum value. Must not be <code>null</code>.
	 * If a <code>null</code> default value is required, use {@link #getPropertyAsEnum(String, Class, Enum)}
	 * instead!
	 * @return the property's value. Never <code>null</code>.
	 * @see #getPropertyAsEnum(String, Class, Enum)
	 * @see #getPropertyAsNonEmptyTrimmedString(String, String)
	 */
	public <E extends Enum<E>> E getPropertyAsEnum(final String key, final E defaultValue) {
		AssertUtil.assertNotNull("defaultValue", defaultValue);
		@SuppressWarnings("unchecked")
		final
		Class<E> enumClass = (Class<E>) defaultValue.getClass();
		return getPropertyAsEnum(key, enumClass, defaultValue);
	}

	/**
	 * Gets the property identified by the given key.
	 * @param key the key identifying the property. Must not be <code>null</code>.
	 * @param enumClass the enum's type. Must not be <code>null</code>.
	 * @param defaultValue the default value to fall back to, if neither this {@code Config}'s
	 * internal {@code Properties} nor any of its parents contains a matching entry or
	 * if this entry's value does not match any possible enum value. May be <code>null</code>.
	 * @return the property's value. Never <code>null</code> unless {@code defaultValue} is <code>null</code>.
	 * @see #getPropertyAsEnum(String, Enum)
	 * @see #getPropertyAsNonEmptyTrimmedString(String, String)
	 */
	public <E extends Enum<E>> E getPropertyAsEnum(final String key, final Class<E> enumClass, final E defaultValue) {
		AssertUtil.assertNotNull("enumClass", enumClass);
		final String sval = getPropertyAsNonEmptyTrimmedString(key, null);
		if (sval == null)
			return defaultValue;

		try {
			return Enum.valueOf(enumClass, sval);
		} catch (final IllegalArgumentException x) {
			logger.warn("getPropertyAsEnum: One of the properties files %s contains the key '%s' with the illegal value '%s'. Falling back to default value '%s'!", propertiesFiles, key, sval, defaultValue);
			return defaultValue;
		}
	}

	public boolean getPropertyAsBoolean(final String key, final boolean defaultValue) {
		final String sval = getPropertyAsNonEmptyTrimmedString(key, null);
		if (sval == null)
			return defaultValue;

		if (TRUE_STRING.equalsIgnoreCase(sval))
			return true;
		else if (FALSE_STRING.equalsIgnoreCase(sval))
			return false;
		else {
			logger.warn("getPropertyAsBoolean: One of the properties files %s contains the key '%s' with the illegal value '%s'. Falling back to default value '%s'!", propertiesFiles, key, sval, defaultValue);
			return defaultValue;
		}
	}

	private static final void refreshFileHardRefAndCleanOldHardRefs(final Config config) {
		final File config_file = AssertUtil.assertNotNull("config", config).getFile();
		if (config_file != null)
			refreshFileHardRefAndCleanOldHardRefs(config_file);
	}

	private final void refreshFileHardRefAndCleanOldHardRefs() {
		if (parentConfig != null)
			parentConfig.refreshFileHardRefAndCleanOldHardRefs();

		refreshFileHardRefAndCleanOldHardRefs(this);
	}

	private static final void refreshFileHardRefAndCleanOldHardRefs(final File config_file) {
		AssertUtil.assertNotNull("config_file", config_file);
		synchronized (fileHardRefs) {
			// make sure the config_file is at the end of fileHardRefs
			fileHardRefs.remove(config_file);
			fileHardRefs.add(config_file);

			// remove the first entry until size does not exceed limit anymore.
			while (fileHardRefs.size() > fileHardRefsMaxSize)
				fileHardRefs.remove(fileHardRefs.iterator().next());
		}
	}
}
