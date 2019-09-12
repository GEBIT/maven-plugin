//
// RedeploymentFixUtils.java
//
// Copyright (C) 2018
// GEBIT Solutions GmbH,
// Berlin, Duesseldorf, Stuttgart (Germany)
// All rights reserved.
//
package hudson.maven.util;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.cli.transfer.BatchModeMavenTransferListener;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.maven.MavenBuild;
import hudson.maven.MavenEmbedder;
import hudson.maven.MavenEmbedderException;
import hudson.maven.MavenEmbedderRequest;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.MavenUtil;
import hudson.maven.RedeployPublisher.WrappedArtifactRepository;
import hudson.maven.reporters.MavenAbstractArtifactRecord;
import hudson.maven.reporters.MavenAggregatedArtifactRecord;
import hudson.maven.reporters.MavenArtifactRecord;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.NodeProperty;
import hudson.slaves.SlaveComputer;
import hudson.tasks.BuildWrapper;
import hudson.tasks.Maven.MavenInstallation;
import jenkins.model.Jenkins;
import jenkins.mvn.DefaultSettingsProvider;
import jenkins.mvn.GlobalSettingsProvider;
import jenkins.mvn.SettingsProvider;
import jenkins.security.MasterToSlaveCallable;
import jenkins.util.BuildListenerAdapter;

/**
 * Utilities class provides helper method to fix redeployment bugs or add
 * redeployment features.
 *
 * @author Volodymyr Medvid
 */
public class RedeployFixUtils {

	private static final String ARCHIVED_REDEPLOY_SETTINGS_RELATIVE_PATH = "redeploy/settings.xml";
	private static final String ARCHIVED_REDEPLOY_GLOBAL_SETTINGS_RELATIVE_PATH = "redeploy/gl-settings.xml";

	/**
	 * Archive settings.xml that can be used later by manual redeployment.
	 *
	 * @param settingsLoc the path to the settings file to be archived
	 * @param build       the build where settings were used to deploy artefacts
	 * @param launcher    the launcher to use if external processes need to be
	 *                    forked
	 * @param listener    a way to print messages about progress or problems
	 * @throws InterruptedException if transfer was interrupted
	 */
	public static void archiveMavenSettings(FilePath settingsLoc, MavenModuleSetBuild build, Launcher launcher,
			BuildListener listener) throws InterruptedException {
		try {
			if (archiveFile(settingsLoc, ARCHIVED_REDEPLOY_SETTINGS_RELATIVE_PATH, build, launcher, listener)) {
				listener.getLogger().println("Maven settings.xml archived");
			}
		} catch (IOException e) {
			listener.getLogger().println("Maven settings.xml couldn't be archived.");
		}
	}

	/**
	 * Archive global settings.xml that can be used later by manual redeployment.
	 *
	 * @param settingsLoc the path to the global settings file to be archived
	 * @param build       the build where settings were used to deploy artefacts
	 * @param launcher    the launcher to use if external processes need to be
	 *                    forked
	 * @param listener    a way to print messages about progress or problems
	 * @throws InterruptedException if transfer was interrupted
	 */
	public static void archiveMavenGlobalSettings(FilePath settingsLoc, MavenModuleSetBuild build, Launcher launcher,
			BuildListener listener) throws InterruptedException {
		try {
			if (archiveFile(settingsLoc, ARCHIVED_REDEPLOY_GLOBAL_SETTINGS_RELATIVE_PATH, build, launcher, listener)) {
				listener.getLogger().println("Maven global settings.xml archived");
			}
		} catch (IOException e) {
			listener.getLogger().println("Maven global settings.xml couldn't be archived");
		}
	}

	private static boolean archiveFile(FilePath file, String targetPath, MavenModuleSetBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		SettingsProvider settingsProvider = build.getProject().getSettings();
		if (settingsProvider != null && !settingsProvider.getClass().equals(DefaultSettingsProvider.class)) {
			FilePath buildRootDir = getBuildDirOnMaster(build);
			FilePath remoteSettingsFile = new FilePath(buildRootDir, targetPath);
			remoteSettingsFile.copyFrom(file);
			return true;
		}
		return false;
	}

	public static FilePath getBuildDirOnMaster(AbstractBuild<?, ?> build) {
		VirtualChannel masterChannel = SlaveComputer.getChannelToMaster();
		return new FilePath(masterChannel, build.getRootDir().getAbsolutePath());
	}

	/**
	 * Check if fixed redeployment should be used. Currently returns always
	 * <code>true</code>.
	 *
	 * @return <code>true</code> if fixed redeployment should be used
	 */
	public static boolean useFixedRedeploy() {
		return true;
	}

	/**
	 * Redeploy artefcts. Fixed several problems:<br>
	 * <ul>
	 * <li>use repo id and url from initail deploy if no id and url specified by
	 * query</li>
	 * <li>redeploy even if the slave where build was executed doesn't exist
	 * anymore</li>
	 * <li>fixed redeployment of artefacts from single modules</li>
	 * </ul>
	 *
	 * @param artifactRecord the deploy record to be redeployed
	 * @param build          the build which artifacts have to be redeployed
	 * @param listener       a way to print messages about progress or problems
	 * @param id             the repo id passed by the query
	 * @param repositoryUrl  the repo url passed by the query
	 * @param uniqueVersion  unique version flag passed by the query
	 * @return the result of redeployment execution
	 * @throws Exception if redeployment failed in any way
	 */
	public static Result performRedeploy(MavenAbstractArtifactRecord artifactRecord, AbstractBuild<?, ?> build,
			TaskListener listener, String id, String repositoryUrl, boolean uniqueVersion) throws Exception {
		AbstractBuild<?, ?> rootBuild = null;
		if (artifactRecord instanceof MavenArtifactRecord) {
			// if redeploy of sub module, use the build of parent/main project to create
			// MavenEmbedder with correct settings.xml (in other case credentials for
			// repository are missing)
			rootBuild = getRootMavenBuild(build, listener);
		}
		if (rootBuild == null) {
			rootBuild = build;
		}

		Field builtOnField = null;
		String oldBuildOn = rootBuild.getBuiltOnStr();
		Node buildNode = rootBuild.getBuiltOn();
		if (buildNode == null) {
			// Temporary reset buildOn (slave where job was built) for the case if slave
			// (docker container) doesn't exist anymore.
			builtOnField = AbstractBuild.class.getDeclaredField("builtOn");
			builtOnField.setAccessible(true);
			builtOnField.set(rootBuild, "");
		}
		// workaround to set up build environments that solves the problem with sensible
		// variables
		// that are not valid after serialization
		loadBuildEnvironments(rootBuild, listener);

		MavenEmbedder embedder = createEmbedder(listener, rootBuild);
		if (builtOnField != null) {
			builtOnField.set(rootBuild, oldBuildOn);
		}

		ArtifactRepositoryLayout layout = embedder.lookup(ArtifactRepositoryLayout.class, "default");
		ArtifactRepositoryFactory factory = (ArtifactRepositoryFactory) embedder.lookup(ArtifactRepositoryFactory.ROLE);

		String repoId = id;
		String repoUrl = repositoryUrl;
		if (repoUrl == null || repoUrl.isEmpty()) {
			// If no repository definition is set on the job level we try to take it from
			// the POM
			if (artifactRecord instanceof MavenAggregatedArtifactRecord) {
				List<MavenArtifactRecord> moduelRecords = ((MavenAggregatedArtifactRecord) artifactRecord)
						.getModuleRecords();
				if (moduelRecords != null && !moduelRecords.isEmpty()) {
					MavenArtifactRecord firstRecord = moduelRecords.get(0);
					repoId = firstRecord.repositoryId;
					repoUrl = firstRecord.repositoryUrl;
				}
			} else if (artifactRecord instanceof MavenArtifactRecord) {
				MavenArtifactRecord mavenArtifactRecord = (MavenArtifactRecord) artifactRecord;
				repoId = mavenArtifactRecord.repositoryId;
				repoUrl = mavenArtifactRecord.repositoryUrl;
			}
		}
		if (repoUrl == null || repoUrl.isEmpty()) {
			listener.getLogger().println("[ERROR] No Repository settings defined in the job "
					+ "configuration or distributionManagement of the module.");
		} else {
			ArtifactRepository repo = getDeploymentRepository(factory, layout, repoId, repoUrl, uniqueVersion);
			artifactRecord.deploy(embedder, repo, listener);
			return Result.SUCCESS;
		}
		return null;
	}

	private static ArtifactRepository getDeploymentRepository(ArtifactRepositoryFactory factory,
			ArtifactRepositoryLayout layout, String repositoryId, String repositoryUrl, boolean uniqueVersion)
			throws ComponentLookupException {
		if (repositoryUrl == null || repositoryUrl.isEmpty()) {
			return null;
		}
		ArtifactRepository repository = factory.createDeploymentArtifactRepository(repositoryId, repositoryUrl, layout,
				uniqueVersion);
		return new WrappedArtifactRepository(repository, uniqueVersion);
	}

	private static MavenModuleSetBuild getRootMavenBuild(AbstractBuild<?, ?> build, TaskListener listener)
			throws IOException, InterruptedException {
		if (build instanceof MavenModuleSetBuild) {
			return (MavenModuleSetBuild) build;
		}
		if (build instanceof MavenBuild) {
			MavenModule mavenModule = ((MavenBuild) build).getProject();
			MavenModuleSet parentModule = null;
			try {
				parentModule = mavenModule.getParent();
			} catch (Exception e) {
			}
			if (parentModule != null) {
				return parentModule.getBuildByNumber(build.getNumber());
			}
		}
		return null;
	}

	private static void loadBuildEnvironments(AbstractBuild<?, ?> build, TaskListener taskListener) throws Exception {
		Field buildEnvironmentsField = AbstractBuild.class.getDeclaredField("buildEnvironments");
		buildEnvironmentsField.setAccessible(true);
		if (buildEnvironmentsField.get(build) == null) {
			BuildListenerAdapter buildListener = new BuildListenerAdapter(taskListener);
			Node buildNode = build.getBuiltOn();
			Launcher l = buildNode.createLauncher(buildListener);

			ArrayList<Environment> buildEnvironments = new ArrayList<Environment>();

			for (RunListener rl : RunListener.all()) {
				Environment environment = rl.setUpEnvironment(build, l, buildListener);
				if (environment != null) {
					buildEnvironments.add(environment);
				}
			}

			for (NodeProperty nodeProperty : Jenkins.getInstance().getGlobalNodeProperties()) {
				Environment environment = nodeProperty.setUp(build, l, buildListener);
				if (environment != null) {
					buildEnvironments.add(environment);
				}
			}

			for (NodeProperty nodeProperty : buildNode.getNodeProperties()) {
				Environment environment = nodeProperty.setUp(build, l, buildListener);
				if (environment != null) {
					buildEnvironments.add(environment);
				}
			}

			if (build instanceof MavenModuleSetBuild) {
				MavenModuleSetBuild mvnBuild = (MavenModuleSetBuild) build;
				List<BuildWrapper> wrappers = new ArrayList<BuildWrapper>();
				for (BuildWrapper w : mvnBuild.getProject().getBuildWrappersList()) {
					wrappers.add(w);
				}
				for (BuildWrapper w : wrappers) {
					// skip EnvInjectBuildWrapper that can overwrite sensible environments with
					// masked
					// values from the file
					if (!w.getClass().getSimpleName().equals("EnvInjectBuildWrapper")) {
						Environment e = w.setUp(mvnBuild, l, buildListener);
						if (e != null) {
							buildEnvironments.add(e);
						}
					}
				}
			}

			buildEnvironmentsField.set(build, buildEnvironments);
		}
	}

	/**
	 * copy from RedeployPublisher
	 */
	private static MavenEmbedder createEmbedder(TaskListener listener, AbstractBuild<?, ?> build)
			throws MavenEmbedderException, IOException, InterruptedException {
		MavenInstallation m = null;
		File settingsLoc = null, remoteGlobalSettingsFromConfig = null;
		String profiles = null;
		Properties systemProperties = null;
		String privateRepository = null;
		File workspace = null;

		File tmpSettings = File.createTempFile("jenkins", "temp-settings.xml");
		File tmpSettingsGlobal = File.createTempFile("jenkins", "temp-global-settings.xml");
		try {
			AbstractProject project = build.getProject();

			// JENKINS-7010: If the publisher is used inside a promotion
			// we need to retrieve the settings from the parent project
			// NOTE: Do not use instanceof to not have a dependency to the promotion plugin
			if ("hudson.plugins.promoted_builds.PromotionProcess".equals(project.getClass().getName())) {
				project = project.getRootProject();
			}

			if (project instanceof MavenModuleSet) {
				MavenModuleSet mavenModuleSet = ((MavenModuleSet) project);
				profiles = mavenModuleSet.getProfiles();
				systemProperties = mavenModuleSet.getMavenProperties();

				Node buildNode = build.getBuiltOn();

				if (buildNode == null) {
					// assume that build was made on master
					buildNode = Jenkins.getInstance();
				}

				EnvVars envVars = build.getEnvironment(listener);
				for (Entry<Object, Object> entry : systemProperties.entrySet()) {
					// expand variables in goals
					Object value = entry.getValue();
					if (value instanceof String) {
						value = envVars.expand((String) value);
						entry.setValue(value);
					}
				}
				if (envVars != null && !envVars.isEmpty()) {
					// define all EnvVars as "env.xxx" maven properties
					for (Entry<String, String> entry : envVars.entrySet()) {
						if (entry.getKey() != null && entry.getValue() != null) {
							systemProperties.put("env." + entry.getKey(), entry.getValue());
						}
					}
				}

				FilePath buildRootDir = getBuildDirOnMaster(build);
				workspace = new File(buildRootDir.getRemote());
				FilePath archivedSettingsFile = new FilePath(buildRootDir, ARCHIVED_REDEPLOY_SETTINGS_RELATIVE_PATH);
				if (archivedSettingsFile.exists()) {
					listener.getLogger().println("Using archived maven settings.xml");
					FilePath filePath = new FilePath(tmpSettings);
					archivedSettingsFile.copyTo(filePath);
					settingsLoc = tmpSettings;
				} else {
					// olamy see
					// we have to take about the settings use for the project
					// order tru configuration
					// TODO maybe in goals with -s,--settings last wins but not done in during pom
					// parsing
					// or -Dmaven.repo.local
					// if not we must get ~/.m2/settings.xml then $M2_HOME/conf/settings.xml

					// TODO check if the remoteSettings has a localRepository configured and
					// disabled it

					String altSettingsPath = SettingsProvider
							.getSettingsRemotePath(((MavenModuleSet) project).getSettings(), build, listener);

					if (StringUtils.isBlank(altSettingsPath)) {
						// get userHome from the node where job has been executed
						String remoteUserHome = build.getWorkspace().act(new GetUserHome());
						altSettingsPath = remoteUserHome + "/.m2/settings.xml";
					}

					// we copy this file in the master in a temporary file
					FilePath filePath = new FilePath(tmpSettings);
					FilePath remoteSettings = build.getWorkspace().child(altSettingsPath);
					if (!remoteSettings.exists()) {
						// JENKINS-9084 we finally use $M2_HOME/conf/settings.xml as maven does

						String mavenHome = ((MavenModuleSet) project).getMaven().forNode(buildNode, listener).getHome();
						String settingsPath = mavenHome + "/conf/settings.xml";
						remoteSettings = build.getWorkspace().child(settingsPath);
					}
					if (remoteSettings.exists()) {
						listener.getLogger().println("Using maven settings from: " + remoteSettings.getRemote());
						remoteSettings.copyTo(filePath);
						settingsLoc = tmpSettings;
					} else {
						listener.getLogger().println("Using maven default settings.xml");
					}
				}

				FilePath archivedGlobalSettingsFile = new FilePath(buildRootDir,
						ARCHIVED_REDEPLOY_GLOBAL_SETTINGS_RELATIVE_PATH);
				if (archivedGlobalSettingsFile.exists()) {
					listener.getLogger().println("Using archived maven global settings.xml");
					FilePath filePathGlobal = new FilePath(tmpSettingsGlobal);
					archivedGlobalSettingsFile.copyTo(filePathGlobal);
					remoteGlobalSettingsFromConfig = tmpSettingsGlobal;
				} else {
					String remoteGlobalSettingsPath = GlobalSettingsProvider
							.getSettingsRemotePath(((MavenModuleSet) project).getGlobalSettings(), build, listener);
					if (remoteGlobalSettingsPath != null) {
						// copy global settings from slave's remoteGlobalSettingsPath to
						// tmpSettingsGlobal
						FilePath filePathGlobal = new FilePath(tmpSettingsGlobal);
						FilePath remoteSettingsGlobal = build.getWorkspace().child(remoteGlobalSettingsPath);

						listener.getLogger().println("Using maven global settings from: "
								+ remoteSettingsGlobal.getRemote());
						remoteSettingsGlobal.copyTo(filePathGlobal);
						remoteGlobalSettingsFromConfig = tmpSettingsGlobal;
					}
				}
			}

			MavenEmbedderRequest mavenEmbedderRequest = new MavenEmbedderRequest(listener,
					m != null ? m.getHomeDir() : null, profiles, systemProperties, privateRepository, settingsLoc, workspace);

			if (remoteGlobalSettingsFromConfig != null) {
				mavenEmbedderRequest.setGlobalSettings(remoteGlobalSettingsFromConfig);
			}

			mavenEmbedderRequest.setTransferListener(new BatchModeMavenTransferListener(listener.getLogger()));

			return MavenUtil.createEmbedder(mavenEmbedderRequest);
		} finally {
			if (tmpSettings != null) {
				tmpSettings.delete();
			}
			if (tmpSettingsGlobal != null) {
				tmpSettingsGlobal.delete();
			}
		}
	}

	private static final class GetUserHome extends MasterToSlaveCallable<String, IOException> {
		private static final long serialVersionUID = -8755705771716056636L;

		public String call() throws IOException {
			return System.getProperty("user.home");
		}
	}

}
