package com.google.protobuf.maven;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.apache.maven.toolchain.java.DefaultJavaToolChain;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.io.RawInputStreamFacade;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.codehaus.plexus.util.FileUtils.cleanDirectory;
import static org.codehaus.plexus.util.FileUtils.copyStreamToFile;
import static org.codehaus.plexus.util.FileUtils.getFiles;

/**
 * Abstract Mojo implementation.
 * <p/>
 * This class is extended by {@link com.google.protobuf.maven.ProtocCompileMojo} and
 * {@link com.google.protobuf.maven.ProtocTestCompileMojo} in order to override the specific configuration for
 * compiling the main or test classes respectively.
 *
 * @author Gregory Kick
 * @author David Trott
 * @author Brice Figureau
 */
abstract class AbstractProtocMojo extends AbstractMojo {

    private static final String PROTO_FILE_SUFFIX = ".proto";

    private static final String DEFAULT_INCLUDES = "**/*" + PROTO_FILE_SUFFIX;

    /**
     * The current Maven project.
     */
    @Component
    protected MavenProject project;

    /**
     * The current Maven Session Object.
     *
     * @since 0.2.0
     */
    @Component
    private MavenSession session;

    /**
     * Build context that tracks changes to the source and target files.
     *
     * @since 0.3.0
     */
    @Component
    protected BuildContext buildContext;

    /**
     * An optional tool chain manager.
     *
     * @since 0.2.0
     */
    @Component
    private ToolchainManager toolchainManager;

    /**
     * A helper used to add resources to the project.
     */
    @Component
    protected MavenProjectHelper projectHelper;

    /**
     * Repository system for artifact resolution.
     *
     * @since 0.3.0
     */
    @Component
    private RepositorySystem repoSystem;

    /**
     * Repository system session for artifact resolution.
     *
     * @since 0.3.0
     */
    @Parameter(
            defaultValue = "${repositorySystemSession}",
            readonly = true
    )
    private RepositorySystemSession repoSystemSession;

    /**
     * Remote repositories for artifact resolution.
     *
     * @since 0.3.0
     */
    @Parameter(
            defaultValue = "${project.remotePluginRepositories}",
            readonly = true
    )
    private List<RemoteRepository> remoteRepos;

    /**
     * A directory where native launchers for java protoc plugins will be generated.
     *
     * @since 0.3.0
     */
    @Parameter(
            defaultValue = "${project.build.directory}/protoc-plugins",
            required = false
    )
    private File protocPluginDirectory;

    /**
     * This is the path to the {@code protoc} executable.
     * When this parameter is not set, the plugin attempts to load
     * a {@code protobuf} toolchain and use it locate {@code protoc} executable.
     * If no {@code protobuf} toolchain is defined in the project,
     * the {@code protoc} executable in the {@code PATH} is used.
     */
    @Parameter(
            required = false,
            property = "protocExecutable"
    )
    private String protocExecutable;

    /**
     * Additional source paths for {@code .proto} definitions.
     */
    @Parameter(
            required = false
    )
    private File[] additionalProtoPathElements = {};

    /**
     * Since {@code protoc} cannot access jars, proto files in dependencies are extracted to this location
     * and deleted on exit. This directory is always cleaned during execution.
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/protoc-dependencies"
    )
    private File temporaryProtoFileDirectory;

    /**
     * This is the path to the local maven {@code repository}.
     */
    @Parameter(
            readonly = true,
            required = true,
            property = "localRepository"
    )
    private ArtifactRepository localRepository;

    /**
     * Set this to {@code false} to disable hashing of dependent jar paths.
     * <p/>
     * This plugin expands jars on the classpath looking for embedded {@code .proto} files.
     * Normally these paths are hashed (MD5) to avoid issues with long file names on windows.
     * However if this property is set to {@code false} longer paths will be used.
     */
    @Parameter(
            required = true,
            defaultValue = "true"
    )
    private boolean hashDependentPaths;

    /**
     * A list of &lt;include&gt; elements specifying the protobuf definition files (by pattern)
     * that should be included in compilation.
     * When not specified, the default includes will be:
     * <code><br/>
     * &lt;includes&gt;<br/>
     * &nbsp;&lt;include&gt;**&#47;*.proto&lt;/include&gt;<br/>
     * &lt;/includes&gt;<br/>
     * </code>
     */
    @Parameter(
            required = false
    )
    private Set<String> includes = ImmutableSet.of(DEFAULT_INCLUDES);

    /**
     * A list of &lt;exclude&gt; elements specifying the protobuf definition files (by pattern)
     * that should be excluded from compilation.
     * When not specified, the default excludes will be empty:
     * <code><br/>
     * &lt;excludes&gt;<br/>
     * &lt;/excludes&gt;<br/>
     * </code>
     */
    @Parameter(
            required = false
    )
    private Set<String> excludes = ImmutableSet.of();

    /**
     * The descriptor set file name. Only used if {@code writeDescriptorSet} is set to {@code true}.
     *
     * @since 0.3.0
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.finalName}.protobin"
    )
    private String descriptorSetFileName;

    /**
     * If set to {@code true}, the compiler will generate a binary descriptor set file for the
     * specified {@code .proto} files.
     *
     * @since 0.3.0
     */
    @Parameter(
            required = true,
            defaultValue = "false"
    )
    private boolean writeDescriptorSet;

    /**
     * If {@code true} and {@code writeDescriptorSet} has been set, the compiler will include
     * all dependencies in the descriptor set making it "self-contained".
     *
     * @since 0.3.0
     */
    @Parameter(
            required = false,
            defaultValue = "false"
    )
    private boolean includeDependenciesInDescriptorSet;

    /**
     * Specifies one of more custom protoc plugins, written in Java
     * and available as Maven artifacts. An executable plugin will be created
     * at execution time. On UNIX the executable is a shell script and on
     * Windows it is a WinRun4J .exe and .ini.
     */
    @Parameter(
            required = false
    )
    private List<ProtocPlugin> protocPlugins;

    /**
     * Sets the granularity in milliseconds of the last modification date
     * for testing whether source protobuf definitions need recompilation.
     *
     * <p>This parameter is only used when {@link #checkStaleness} parameter is set to {@code true}.
     *
     * <p>If the project is built on NFS it's recommended to set this parameter to {@code 10000}.
     */
    @Parameter(
            required = false,
            defaultValue = "0"
    )
    private long staleMillis;

    /**
     * Normally {@code protoc} is invoked on every execution of the plugin.
     * Setting this parameter to {@code true} will enable checking
     * timestamps of source protobuf definitions vs. generated sources.
     *
     * @see #staleMillis
     */
    @Parameter(
            required = false,
            defaultValue = "false"
    )
    private boolean checkStaleness;

    /**
     * When {@code true}, skip the execution.
     *
     * @since 0.2.0
     */
    @Parameter(
            required = false,
            property = "protoc.skip",
            defaultValue = "false"
    )
    private boolean skip;

    /**
     * Usually most of protobuf mojos will not get executed on parent poms
     * (i.e. projects with packaging type 'pom').
     * Setting this parameter to {@code true} will force
     * the execution of this mojo, even if it would usually get skipped in this case.
     *
     * @since 0.2.0
     */
    @Parameter(
            required = false,
            property = "protoc.force",
            defaultValue = "false"
    )
    private boolean forceMojoExecution;

    /**
     * Executes the mojo.
     */
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skipMojo()) {
            return;
        }

        checkParameters();
        final File protoSourceRoot = getProtoSourceRoot();
        if (protoSourceRoot.exists()) {
            try {
                final ImmutableSet<File> protoFiles = findProtoFilesInDirectory(protoSourceRoot);
                final File outputDirectory = getOutputDirectory();
                final ImmutableSet<File> outputFiles = findGeneratedFilesInDirectory(getOutputDirectory());

                if (protoFiles.isEmpty()) {
                    getLog().info("No proto files to compile.");
                } else if (!hasDelta(protoFiles)) {
                    getLog().info("Skipping compilation because build context has no changes.");
                    attachFiles();
                } else if (checkStaleness && checkFilesUpToDate(protoFiles, outputFiles)) {
                    getLog().info("Skipping compilation because target directory newer than sources.");
                    attachFiles();
                } else {
                    final ImmutableSet<File> derivedProtoPathElements =
                            makeProtoPathFromJars(temporaryProtoFileDirectory, getDependencyArtifactFiles());
                    FileUtils.mkdir(outputDirectory.getAbsolutePath());

                    // Quick fix to fix issues with two mvn installs in a row (ie no clean)
                    cleanDirectory(outputDirectory);

                    if (writeDescriptorSet) {
                        final File descriptorSetOutputDirectory = getDescriptorSetOutputDirectory();
                        FileUtils.mkdir(descriptorSetOutputDirectory.getAbsolutePath());
                        // See above
                        cleanDirectory(descriptorSetOutputDirectory);
                    }

                    if (protocPlugins != null) {
                        createProtocPlugins();
                    }

                    //get toolchain from context
                    final Toolchain tc = toolchainManager.getToolchainFromBuildContext("protobuf", session); //NOI18N
                    if (tc != null) {
                        getLog().info("Toolchain in protoc-plugin: " + tc);
                        //when the executable to use is explicitly set by user in mojo's parameter, ignore toolchains.
                        if (protocExecutable != null) {
                            getLog().warn(
                                    "Toolchains are ignored, 'protocExecutable' parameter is set to " + protocExecutable);
                        } else {
                            //assign the path to executable from toolchains
                            protocExecutable = tc.findTool("protoc"); //NOI18N
                        }
                    }
                    if (protocExecutable == null) {
                        // Try to fall back to 'protoc' in $PATH
                        getLog().warn("No 'protocExecutable' parameter is configured, using the default: 'protoc'");
                        protocExecutable = "protoc";
                    }

                    final Protoc.Builder protocBuilder =
                            new Protoc.Builder(protocExecutable)
                                    .addProtoPathElement(protoSourceRoot)
                                    .addProtoPathElements(derivedProtoPathElements)
                                    .addProtoPathElements(asList(additionalProtoPathElements))
                                    .addProtoFiles(protoFiles);
                    addProtocBuilderParameters(protocBuilder);
                    final Protoc protoc = protocBuilder.build();

                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Proto source root:");
                        getLog().debug(" " + protoSourceRoot);

                        if (derivedProtoPathElements != null && !derivedProtoPathElements.isEmpty()) {
                            getLog().debug("Derived proto paths:");
                            for (final File path : derivedProtoPathElements) {
                                getLog().debug(" " + path);
                            }
                        }

                        if (additionalProtoPathElements != null && additionalProtoPathElements.length > 0) {
                            getLog().debug("Additional proto paths:");
                            for (final File path : additionalProtoPathElements) {
                                getLog().debug(" " + path);
                            }
                        }
                    }
                    protoc.logExecutionParameters(getLog());

                    getLog().info(format("Compiling %d proto file(s) to %s", protoFiles.size(), outputDirectory));

                    final int exitStatus = protoc.execute();
                    if (exitStatus != 0) {
                        getLog().error("protoc failed output: " + protoc.getOutput());
                        getLog().error("protoc failed error: " + protoc.getError());
                        throw new MojoFailureException(
                                "protoc did not exit cleanly. Review output for more information.");
                    }
                    attachFiles();
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An IO error occured", e);
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("protoc failed to execute because: " + e.getMessage(), e);
            } catch (CommandLineException e) {
                throw new MojoExecutionException("An error occurred while invoking protoc.", e);
            }
        } else {
            getLog().info(format("%s does not exist. Review the configuration or consider disabling the plugin.",
                    protoSourceRoot));
        }
    }

    /**
     * Generates native launchers for java protoc plugins.
     * These launchers will later be added as parameters for protoc compiler.
     *
     * @throws MojoExecutionException if plugins launchers could not be created.
     *
     * @since 0.3.0
     */
    protected void createProtocPlugins() throws MojoExecutionException {
        final String javaHome = detectJavaHome();

        for (final ProtocPlugin plugin : protocPlugins) {

            if (plugin.getJavaHome() != null) {
                getLog().debug("Using javaHome defined in plugin definition: " + javaHome);
            } else {
                getLog().debug("Setting javaHome for plugin: " + javaHome);
                plugin.setJavaHome(javaHome);
            }

            getLog().info("Building protoc plugin: " + plugin.getId());
            final ProtocPluginAssembler assembler = new ProtocPluginAssembler(
                    plugin,
                    repoSystem,
                    repoSystemSession,
                    remoteRepos,
                    protocPluginDirectory,
                    getLog());
            assembler.execute();
        }
    }

    /**
     * Attempts to detect java home directory, using {@code jdk} toolchain if available,
     * with a fallback to {@code java.home} system property.
     *
     * @return path to java home directory.
     *
     * @since 0.3.0
     */
    protected String detectJavaHome() {
        String javaHome = null;

        final Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (tc != null) {
            if (tc instanceof DefaultJavaToolChain) {
                javaHome = ((DefaultJavaToolChain) tc).getJavaHome();
                if (javaHome != null) {
                    getLog().debug("Using javaHome from toolchain: " + javaHome);
                }
            } else {
                // Try to infer JAVA_HOME from location of 'java' tool in toolchain, if available.
                // We don't use 'java' directly because for Windows we need to find the path to
                // jvm.dll instead, which the assembler tries to figure out relative to JAVA_HOME.
                final String javaExecutable = tc.findTool("java");
                if (javaExecutable != null) {
                    File parent = new File(javaExecutable).getParentFile();
                    if (parent != null) {
                        parent = parent.getParentFile();
                        if (parent != null && parent.isDirectory()) {
                            javaHome = parent.getAbsolutePath();
                            getLog().debug(
                                    "Using javaHome based on 'java' location returned by toolchain: " + javaHome);
                        }
                    }
                }
            }
        }
        if (javaHome == null) {
            // Default location is the current JVM's JAVA_HOME.
            javaHome = System.getProperty("java.home");
            getLog().debug("Using javaHome from java.home system property: " + javaHome);
        }
        return javaHome;
    }

    /**
     * Adds mojo-specific parameters to the protoc builder.
     *
     * @param protocBuilder the builder to be modified.
     */
    protected void addProtocBuilderParameters(final Protoc.Builder protocBuilder) {
        if (protocPlugins != null) {
            for (final ProtocPlugin plugin : protocPlugins) {
                protocBuilder.addPlugin(plugin);
            }
            protocPluginDirectory.mkdirs();
            protocBuilder.setPluginDirectory(protocPluginDirectory);
        }
        if (writeDescriptorSet) {
            final File descriptorSetFile = new File(getDescriptorSetOutputDirectory(), descriptorSetFileName);
            getLog().info("Will write descriptor set:");
            getLog().info(" " + descriptorSetFile.getAbsolutePath());
            protocBuilder.withDescriptorSetFile(descriptorSetFile, includeDependenciesInDescriptorSet);
        }
    }

    /**
     * <p>Determine if the mojo execution should get skipped.</p>
     * This is the case if:
     * <ul>
     * <li>{@link #skip} is <code>true</code></li>
     * <li>if the mojo gets executed on a project with packaging type 'pom' and
     * {@link #forceMojoExecution} is <code>false</code></li>
     * </ul>
     *
     * @return <code>true</code> if the mojo execution should be skipped.
     *
     * @since 0.2.0
     */
    protected boolean skipMojo() {
        if (skip) {
            getLog().info("Skipping protoc mojo execution");
            return true;
        }

        if (!forceMojoExecution && "pom".equals(this.project.getPackaging())) {
            getLog().info("Skipping protoc mojo execution for project with packaging type 'pom'");
            return true;
        }

        return false;
    }

    protected ImmutableSet<File> findGeneratedFilesInDirectory(final File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            return ImmutableSet.of();
        }

        final List<File> javaFilesInDirectory = getFiles(directory, "**/*.java", null);
        return ImmutableSet.copyOf(javaFilesInDirectory);
    }

    protected static long lastModified(final ImmutableSet<File> files) {
        long result = 0;
        for (final File file : files) {
            result = max(result, file.lastModified());
        }
        return result;
    }

    /**
     * Checks that the source files don't have modification time that is later than the target files.
     *
     * @param sourceFiles a set of source files.
     * @param targetFiles a set of target files.
     * @return {@code true}, if source files are not later than the target files; {@code false}, otherwise.
     */
    protected boolean checkFilesUpToDate(final ImmutableSet<File> sourceFiles, final ImmutableSet<File> targetFiles) {
        return lastModified(sourceFiles) + staleMillis < lastModified(targetFiles);
    }

    /**
     * Checks if the injected build context has changes in any of the specified files.
     *
     * @param files files to be checked for changes.
     * @return {@code true}, if at least one file has changes; {@code false}, if no files have changes.
     *
     * @since 0.3.0
     */
    protected boolean hasDelta(final ImmutableSet<File> files) {
        for (final File file : files) {
            if (buildContext.hasDelta(file)) {
                return true;
            }
        }
        return false;
    }

    protected void checkParameters() {
        checkNotNull(project, "project");
        checkNotNull(projectHelper, "projectHelper");
        final File protoSourceRoot = getProtoSourceRoot();
        checkNotNull(protoSourceRoot);
        checkArgument(!protoSourceRoot.isFile(), "protoSourceRoot is a file, not a directory");
        checkNotNull(temporaryProtoFileDirectory, "temporaryProtoFileDirectory");
        checkState(!temporaryProtoFileDirectory.isFile(), "temporaryProtoFileDirectory is a file, not a directory");
        final File outputDirectory = getOutputDirectory();
        checkNotNull(outputDirectory);
        checkState(!outputDirectory.isFile(), "the outputDirectory is a file, not a directory");
    }

    protected abstract File getProtoSourceRoot();

    // TODO add artifact filtering (inclusions and exclusions)
    // TODO add filtering for proto definitions in included artifacts
    protected abstract List<Artifact> getDependencyArtifacts();

    /**
     * Returns the output directory for generated sources. Depends on build phase so must
     * be defined in concrete implementation.
     *
     * @return output directory for generated sources.
     */
    protected abstract File getOutputDirectory();

    /**
     * Returns output directory for descriptor set file. Depends on build phase so must
     * be defined in concrete implementation.
     *
     * @return output directory for generated descriptor set.
     *
     * @since 0.3.0
     */
    protected abstract File getDescriptorSetOutputDirectory();

    protected abstract void attachFiles();

    /**
     * Gets the {@link File} for each dependency artifact.
     *
     * @return A set of all dependency artifacts.
     */
    protected ImmutableSet<File> getDependencyArtifactFiles() {
        final Set<File> dependencyArtifactFiles = newHashSet();
        for (final Artifact artifact : getDependencyArtifacts()) {
            dependencyArtifactFiles.add(artifact.getFile());
        }
        return ImmutableSet.copyOf(dependencyArtifactFiles);
    }

    /**
     * @throws IOException
     */
    protected ImmutableSet<File> makeProtoPathFromJars(
            final File temporaryProtoFileDirectory,
            final Iterable<File> classpathElementFiles)
            throws IOException, MojoExecutionException {
        checkNotNull(classpathElementFiles, "classpathElementFiles");
        if (!classpathElementFiles.iterator().hasNext()) {
            return ImmutableSet.of(); // Return an empty set
        }
        // clean the temporary directory to ensure that stale files aren't used
        if (temporaryProtoFileDirectory.exists()) {
            cleanDirectory(temporaryProtoFileDirectory);
        }
        final Set<File> protoDirectories = newHashSet();
        for (final File classpathElementFile : classpathElementFiles) {
            // for some reason under IAM, we receive poms as dependent files
            // I am excluding .xml rather than including .jar as there may be other extensions in use (sar, har, zip)
            if (classpathElementFile.isFile() && classpathElementFile.canRead() &&
                    !classpathElementFile.getName().endsWith(".xml")) {

                // create the jar file. the constructor validates.
                final JarFile classpathJar;
                try {
                    classpathJar = new JarFile(classpathElementFile);
                } catch (IOException e) {
                    throw new IllegalArgumentException(format(
                            "%s was not a readable artifact", classpathElementFile), e);
                }
                final Enumeration<JarEntry> jarEntries = classpathJar.entries();
                while (jarEntries.hasMoreElements()) {
                    final JarEntry jarEntry = jarEntries.nextElement();
                    final String jarEntryName = jarEntry.getName();
                    // TODO try using org.codehaus.plexus.util.SelectorUtils.matchPath() with DEFAULT_INCLUDES
                    if (jarEntryName.endsWith(PROTO_FILE_SUFFIX)) {
                        final File jarDirectory =
                                new File(temporaryProtoFileDirectory, truncatePath(classpathJar.getName()));
                        final File uncompressedCopy = new File(jarDirectory, jarEntryName);
                        FileUtils.mkdir(uncompressedCopy.getParentFile().getAbsolutePath());
                        copyStreamToFile(
                                new RawInputStreamFacade(classpathJar.getInputStream(jarEntry)),
                                uncompressedCopy);
                        protoDirectories.add(jarDirectory);
                    }
                }
            } else if (classpathElementFile.isDirectory()) {
                final List protoFiles = getFiles(classpathElementFile, DEFAULT_INCLUDES, null);
                if (!protoFiles.isEmpty()) {
                    protoDirectories.add(classpathElementFile);
                }
            }
        }
        return ImmutableSet.copyOf(protoDirectories);
    }

    protected ImmutableSet<File> findProtoFilesInDirectory(final File directory) throws IOException {
        checkNotNull(directory);
        checkArgument(directory.isDirectory(), "%s is not a directory", directory);
        final Joiner joiner = Joiner.on(',');
        final List<File> protoFilesInDirectory =
                getFiles(directory, joiner.join(includes), joiner.join(excludes));
        return ImmutableSet.copyOf(protoFilesInDirectory);
    }

    protected ImmutableSet<File> findProtoFilesInDirectories(final Iterable<File> directories) throws IOException {
        checkNotNull(directories);
        final Set<File> protoFiles = newHashSet();
        for (final File directory : directories) {
            protoFiles.addAll(findProtoFilesInDirectory(directory));
        }
        return ImmutableSet.copyOf(protoFiles);
    }

    /**
     * Truncates the path of jar files so that they are relative to the local repository.
     *
     * @param jarPath the full path of a jar file.
     * @return the truncated path relative to the local repository or root of the drive.
     */
    protected String truncatePath(final String jarPath) throws MojoExecutionException {

        if (hashDependentPaths) {
            try {
                return toHexString(MessageDigest.getInstance("MD5").digest(jarPath.getBytes()));
            } catch (NoSuchAlgorithmException e) {
                throw new MojoExecutionException("Failed to expand dependent jar", e);
            }
        }

        String repository = localRepository.getBasedir().replace('\\', '/');
        if (!repository.endsWith("/")) {
            repository += "/";
        }

        String path = jarPath.replace('\\', '/');
        final int repositoryIndex = path.indexOf(repository);
        if (repositoryIndex != -1) {
            path = path.substring(repositoryIndex + repository.length());
        }

        // By now the path should be good, but do a final check to fix windows machines.
        final int colonIndex = path.indexOf(':');
        if (colonIndex != -1) {
            // 2 = :\ in C:\
            path = path.substring(colonIndex + 2);
        }

        return path;
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    protected static String toHexString(final byte[] byteArray) {
        final StringBuilder hexString = new StringBuilder(2 * byteArray.length);
        for (final byte b : byteArray) {
            hexString.append(HEX_CHARS[(b & 0xF0) >> 4]).append(HEX_CHARS[b & 0x0F]);
        }
        return hexString.toString();
    }
}
