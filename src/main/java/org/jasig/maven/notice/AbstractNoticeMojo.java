/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.maven.notice;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor;
import org.jasig.maven.notice.lookup.ArtifactLicense;
import org.jasig.maven.notice.lookup.LicenseLookup;
import org.jasig.maven.notice.lookup.MappedVersion;
import org.jasig.maven.notice.util.ResourceFinder;

/**
 * Common base mojo for notice related plugins
 * 
 * @author Eric Dalquist
 * @version $Revision$
 */
public abstract class AbstractNoticeMojo extends AbstractMojo {

    /**
     * The Maven Project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * The dependency tree builder to use.
     *
     * @component
     * @required
     * @readonly
     */
    protected DependencyTreeBuilder dependencyTreeBuilder;

    /**
     * The artifact repository to use.
     *
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * The artifact factory to use.
     *
     * @component
     * @required
     * @readonly
     */
    protected ArtifactFactory artifactFactory;

    /**
     * The artifact metadata source to use.
     *
     * @component
     * @required
     * @readonly
     */
    protected ArtifactMetadataSource artifactMetadataSource;

    /**
     * The artifact collector to use.
     *
     * @component
     * @required
     * @readonly
     */
    protected ArtifactCollector artifactCollector;
    
    /**
     * Maven Project Builder component.
     *
     * @component
     * @required
     * @readonly
     */
    protected MavenProjectBuilder mavenProjectBuilder;
    
    /**
     * License Lookup XML files / URLs.
     *
     * @parameter
     */
    protected String[] licenseLookup = new String[0];
    
    /**
     * Template for NOTICE file generation
     *
     * @parameter default-value="NOTICE.template"
     */
    protected String noticeTemplate = "NOTICE.template";
    
    /**
     * Placeholder string in the NOTICE template file
     *
     * @parameter default-value="#GENERATED_NOTICES#"
     */
    protected String noticeTemplatePlaceholder = "#GENERATED_NOTICES#";
    
    /**
     * Output location for the generated NOTICE file
     *
     * @parameter
     */
    protected String outputDir = "";
    
    /**
     * Output file name
     *
     * @parameter default-value="NOTICE"
     */
    protected String fileName = "NOTICE";
    
    /**
     * Level of indentation to use when generating notice lines
     *
     * @parameter default-value="2"
     */
    protected int indent = 2;
    
    /**
     * @parameter default-value="${project.build.sourceEncoding}"
     */
    protected String encoding = "UTF-8";
    
    /**
     * Set if the NOTICE file should aggregate all dependencies from all child modules.
     * 
     * @parameter default-value="true"
     */
    protected boolean aggregating = true;
    
    /**
     * The {@link MessageFormat} syntax string used to generate each license line in the NOTICE file
     * {0} - indent
     * {1} - artifact name
     * {2} - license name
     * 
     * @parameter default-value="{0}{1} under {2}"
     */
    protected String noticeMessage = "{0}{1} under {2}";
    
    /**
     * Module paths to exclude when running with aggregating=true
     *
     * @parameter
     */
    protected String[] excludedModulePaths = new String[0];
    
    
    
    /* (non-Javadoc)
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    public final void execute() throws MojoExecutionException, MojoFailureException {
        final Log logger = this.getLog();
        
        //If aggregating skip child modules
        if (aggregating && !this.project.isExecutionRoot()) {
            return;
        }
        
        final ResourceFinder finder = this.getResourceFinder();
        
        final LicenseLookupHelper licenseLookupHelper = new LicenseLookupHelper(logger, finder, licenseLookup);

        final List<?> remoteArtifactRepositories = project.getRemoteArtifactRepositories();

        final LicenseResolvingNodeVisitor visitor = new LicenseResolvingNodeVisitor(
                logger,
                licenseLookupHelper, remoteArtifactRepositories, 
                this.mavenProjectBuilder, this.localRepository);

        this.parseProject(logger, this.project, visitor);
     
        //Check for any unresolved artifacts
        final Set<Artifact> unresolvedArtifacts = visitor.getUnresolvedArtifacts();
        this.checkUnresolved(logger, unresolvedArtifacts);
        
        //Convert the resovled notice data into a String
        final Map<String, String> resolvedLicenses = visitor.getResolvedLicenses();
        final String noticeLines = this.generateNoticeLines(resolvedLicenses);
        final String noticeTemplateContents = this.readNoticeTemplate(finder);
        
        //Replace the template placeholder with the generated notice data
        final String noticeContents = noticeTemplateContents.replaceAll(Pattern.quote(this.noticeTemplatePlaceholder), noticeLines);        
        
        this.handleNotice(logger, finder, noticeContents);
    }
    
    /**
     * Called with the expected NOTICE file contents for this project.
     */
    protected abstract void handleNotice(Log logger, ResourceFinder finder, String noticeContents) throws MojoFailureException;
    
    /**
     * Loads the dependency tree for the project via {@link #loadDependencyTree(MavenProject)} and then uses
     * the {@link DependencyNodeVisitor} to load the license data. If {@link #aggregating} is enabled the method
     * recurses on each child module.
     */
    @SuppressWarnings("unchecked")
    protected void parseProject(Log logger, MavenProject project, DependencyNodeVisitor visitor) throws MojoExecutionException, MojoFailureException {
        logger.info("Parsing Dependencies for: " + project.getName());
        
        //Load and parse immediate dependencies
        final DependencyNode tree = this.loadDependencyTree(project);
        tree.accept(visitor);
        
        //If not aggregating don't recurse on modules
        if (!this.aggregating) {
            return;
        }
        
        //No child modules, return
        final List<MavenProject> collectedProjects = project.getCollectedProjects();
        if (collectedProjects == null) {
            return;
        }
        
        //Find all sub-modules for the project
        for (final MavenProject moduleProject : collectedProjects) {
            this.parseProject(logger, moduleProject, visitor);
        }
    }

    /**
     * Check if there are any unresolved artifacts in the Set. If there are print a helpful error
     * message and then throw a {@link MojoFailureException}
     */
    protected void checkUnresolved(Log logger, Set<Artifact> unresolvedArtifacts) throws MojoFailureException {
        if (unresolvedArtifacts.isEmpty()) {
            return;
        }
        
        final LicenseLookup licenseLookup = new LicenseLookup();
        final List<ArtifactLicense> artifacts = licenseLookup.getArtifact();
        
        logger.error("Failed to find Licenses for the following dependencies: ");
        for (final Artifact unresolvedArtifact : unresolvedArtifacts) {
            logger.error("\t" + unresolvedArtifact);
            
            //Build LicenseLookup data model for artifacts that failed resolution
            final ArtifactLicense artifactLicense = new ArtifactLicense();
            artifactLicense.setGroupId(unresolvedArtifact.getGroupId());
            artifactLicense.setArtifactId(unresolvedArtifact.getArtifactId());
            
            final List<MappedVersion> mappedVersions = artifactLicense.getVersion();
            final MappedVersion mappedVersion = new MappedVersion();
            mappedVersion.setValue(unresolvedArtifact.getVersion());
            mappedVersions.add(mappedVersion);
            
            artifacts.add(artifactLicense);
        }
        logger.error("Try adding them to a 'licenseLookup' file.");
        
        final File buildDir = new File(project.getBuild().getDirectory());
        final File mappingsfile = new File(buildDir, "license-mappings.xml");
        
        //Make sure the target directory exists
        try {
            FileUtils.forceMkdir(buildDir);
        }
        catch (IOException e) {
            logger.warn("Failed to write stub license-mappings.xml file to: " + mappingsfile, e);
        }

        //Write the example mappings file
        final Marshaller marshaller = LicenseLookupContext.getMarshaller();
        try {
            marshaller.marshal(licenseLookup, mappingsfile);
            logger.error("A stub license mapping file has been written to: " + mappingsfile);
        }
        catch (JAXBException e) {
            logger.warn("Failed to write stub license-mappings.xml file to: " + mappingsfile, e);
        }
        
        throw new MojoFailureException("Failed to find Licenses for " + unresolvedArtifacts.size() + " artifacts");
    }
    
    /**
     * Create the generated part of the NOTICE file based on the resolved license data
     */
    protected String generateNoticeLines(Map<String, String> resolvedLicenses) {
        final StringBuilder builder = new StringBuilder();

        final MessageFormat messageFormat = new MessageFormat(this.noticeMessage);
        final String indent = StringUtils.repeat(" ", this.indent);
        
        for (final Map.Entry<String, String> resolvedEntry : resolvedLicenses.entrySet()) {
            final String line = messageFormat.format(new Object[] { indent,  resolvedEntry.getKey(), resolvedEntry.getValue()});
            builder.append(line).append("\n");
        }
        
        return builder.toString();
    }

    /**
     * Read the template notice file into a string
     */
    protected String readNoticeTemplate(ResourceFinder finder) throws MojoFailureException {
        final URL inputFile = finder.findResource(this.noticeTemplate);

        final String noticeTemplateContents;
        InputStream inputStream = null;
        try {
            inputStream = inputFile.openStream();
            noticeTemplateContents = IOUtils.toString(inputStream, this.encoding);
        }
        catch (IOException e) {
            throw new MojoFailureException("Failed to open NOTICE Template File '" + this.noticeTemplate + "' from: " + inputFile, e);
        }
        finally {
            IOUtils.closeQuietly(inputStream);
        }
        return noticeTemplateContents;
    }
    
    /**
     * Resolve the {@link File} to write the generated NOTICE file to
     */
    protected File getNoticeOutputFile() {
        if (this.outputDir == null) {
            this.outputDir = "";
        }
        
        File outputPath = new File(this.outputDir);
        if (!outputPath.isAbsolute()) {
            outputPath = new File(project.getBasedir(), this.outputDir);
        }
        return new File(outputPath, this.fileName);
    }

    /**
     * Create the {@link ResourceFinder} for the project
     */
    @SuppressWarnings("unchecked")
    protected ResourceFinder getResourceFinder() throws MojoExecutionException {
        final ResourceFinder finder = new ResourceFinder(this.project.getBasedir());
        try {
            final List<String> classpathElements = this.project.getCompileClasspathElements();
            finder.setCompileClassPath(classpathElements);
        }
        catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        finder.setPluginClassPath(getClass().getClassLoader());
        return finder;
    }

    /**
     * Load the dependency tree for the specified project
     */
    protected DependencyNode loadDependencyTree(MavenProject project) throws MojoExecutionException {
        try {
            return this.dependencyTreeBuilder.buildDependencyTree(
                    project, this.localRepository, 
                    this.artifactFactory, this.artifactMetadataSource, 
                    null, this.artifactCollector);
        }
        catch (DependencyTreeBuilderException e) {
            throw new MojoExecutionException("Cannot build project dependency tree for project: " + project, e );
        }
    }
}