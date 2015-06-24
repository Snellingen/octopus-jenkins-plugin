package hudson.plugins.octopusdeploy;
import com.octopusdeploy.api.*;
import hudson.*;
import hudson.FilePath.FileCallable;
import hudson.model.*;
import hudson.remoting.VirtualChannel;
import jenkins.model.*;
import hudson.tasks.*;
import hudson.scm.*;
import hudson.util.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.*;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.export.*;

/**
 * Creates a release and optionally deploys it.
 */
public class OctopusDeployReleaseRecorder extends Recorder implements Serializable {
    /**
     * The project name as defined in Octopus.
     */
    private final String project;
    public String getProject() {
        return project;
    }

    /**
     * The release version as defined in Octopus.
     */
    private final String releaseVersion;
    public String getReleaseVersion() {
        return releaseVersion;
    }


    /**
     * Are there release notes for this release?
     */
    private final boolean releaseNotes;
    public boolean getReleaseNotes() {
        return releaseNotes;
    }
    
    /**
     * Where are the release notes located?
     */
    private final String releaseNotesSource;
    public String getReleaseNotesSource() {
        return releaseNotesSource;
    }

    public boolean isReleaseNotesSourceFile() {
        return "file".equals(releaseNotesSource);
    }
    public boolean isReleaseNotesSourceScm() {
        return "scm".equals(releaseNotesSource);
    }
    
    /**
     * The file that the release notes are in.
     */
    private final String releaseNotesFile;
    public String getReleaseNotesFile() {
        return releaseNotesFile;
    }
    
    /**
     * If we are deploying, should we wait for it to complete?
     */
    private final boolean waitForDeployment;
    public boolean getWaitForDeployment() {
        return waitForDeployment;
    }
    
    /** 
     * The environment to deploy to, if we are deploying.
     */
    private final String environment;
    public String getEnvironment() {
        return environment;
    }
    
    /**
     * Should this release be deployed right after it is created?
     */
    private final boolean deployThisRelease;
    @Exported
    public boolean getDeployThisRelease() {
        return deployThisRelease;
    }

    /**
     * All packages needed to create this new release.
     */
    private final List<PackageConfiguration> packageConfigs;
    @Exported
    public List<PackageConfiguration> getPackageConfigs() {
        return packageConfigs;
    }

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OctopusDeployReleaseRecorder(
            String project, String releaseVersion, 
            boolean releaseNotes, String releaseNotesSource, String releaseNotesFile, 
            boolean deployThisRelease, String environment, boolean waitForDeployment,
            List<PackageConfiguration> packageConfigs) {
        this.project = project.trim();
        this.releaseVersion = releaseVersion.trim();
        this.releaseNotes = releaseNotes;
        this.releaseNotesSource = releaseNotesSource;
        this.releaseNotesFile = releaseNotesFile.trim();
        this.deployThisRelease = deployThisRelease;
        this.packageConfigs = packageConfigs;
        this.environment = environment.trim();
        this.waitForDeployment = waitForDeployment;
    }
    
    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        boolean success = true;
        Log log = new Log(listener);
        logStartHeader(log);
        // todo: getting from descriptor is ugly. refactor?
        ((DescriptorImpl)getDescriptor()).setGlobalConfiguration();
        OctopusApi api = new OctopusApi(((DescriptorImpl)getDescriptor()).octopusHost, ((DescriptorImpl)getDescriptor()).apiKey);
        
        VariableResolver resolver = build.getBuildVariableResolver();
        EnvVars envVars;
        try {
            envVars = build.getEnvironment(listener);
        } catch (Exception ex) {
            log.fatal(String.format("Failed to retrieve environment variables for this build - '%s'",
                    project, ex.getMessage()));
            return false;
        }
        EnvironmentVariableValueInjector envInjector = new EnvironmentVariableValueInjector(resolver, envVars);
        
        // NOTE: hiding the member variables of the same name with their env-injected equivalents
        String project = envInjector.injectEnvironmentVariableValues(this.project);
        String releaseVersion = envInjector.injectEnvironmentVariableValues(this.releaseVersion);
        String releaseNotesFile = envInjector.injectEnvironmentVariableValues(this.releaseNotesFile);
        String environment = envInjector.injectEnvironmentVariableValues(this.environment);
        
        com.octopusdeploy.api.Project p = null;
        try {
            p = api.getProjectByName(project);
        } catch (Exception ex) {
            log.fatal(String.format("Retrieving project name '%s' failed with message '%s'",
                    project, ex.getMessage()));
            success = false;
        }
        if (p == null) {
            log.fatal("Project was not found.");
            success = false;
        }

        // Check packageVersion
        String releaseNotesContent = null;
        if (releaseNotes) {
            if (isReleaseNotesSourceFile()) {
                try {
                    releaseNotesContent = getReleaseNotesFromFile(build);
                } catch (Exception ex) {
                    log.fatal(String.format("Unable to get file contents from release ntoes file! - %s", ex.getMessage()));
                    success = false;
                }
            } else if (isReleaseNotesSourceScm()) {
                releaseNotesContent = getReleaseNotesFromScm(build);
            } else {
                log.fatal(String.format("Bad configuration: if using release notes, should have source of file or scm. Found '%s'", releaseNotesSource));
                success = false;
            }
        }
        
        if (!success) { // Early exit
            return success;
        }
        
        Set<SelectedPackage> selectedPackages = null;
        if (packageConfigs != null && !packageConfigs.isEmpty()) {
            selectedPackages = new HashSet<SelectedPackage>();
            for (PackageConfiguration pc : packageConfigs) {
                selectedPackages.add(new SelectedPackage(
                        envInjector.injectEnvironmentVariableValues(pc.getPackageName()), 
                        envInjector.injectEnvironmentVariableValues(pc.getPackageVersion())));
            }
        }
        
        try {
            log.info(api.createRelease(p.getId(), releaseVersion, releaseNotesContent, selectedPackages));
        } catch (IOException ex) {
            log.fatal("Failed to create release: " + ex.getMessage());
            success = false;
        }
        
        if (success && deployThisRelease) {
            OctopusDeployDeploymentRecorder deployment = new OctopusDeployDeploymentRecorder(project, releaseVersion, environment, waitForDeployment);
            deployment.perform(build, launcher, listener);
        }
            
        return success;
    }       
    
    /**
     * Write the startup header for the logs to show what our inputs are.
     * @param log The logger
     */
    private void logStartHeader(Log log) {
        log.info("Started Octopus Release");
        log.info("=======================");
        log.info("Project: " + project);
        log.info("Release Version: " + releaseVersion);
        log.info("Include Release Notes?: " + releaseNotes);
        if (releaseNotes) {
            log.info("\tRelease Notes Source: " + releaseNotesSource);
            log.info("\tRelease Notes File: " + releaseNotesFile);
        }
        log.info("Deploy this Release?: " + deployThisRelease);
        if (deployThisRelease) {
            log.info("\tEnvironment: " + environment);
            log.info("\tWait for Deployment: " + waitForDeployment);
        }
        if (packageConfigs == null || packageConfigs.isEmpty()) {
            log.info("Package Configurations: none");
        } else {
            log.info("Package Configurations:");
            for (PackageConfiguration pc : packageConfigs) {
                log.info("\t" + pc.getPackageName() + "\tv" + pc.getPackageVersion());
            }
        }
        log.info("=======================");
    }
    
    /**
     * Return the release notes contents from a file.
     * @param build our build
     * @return string contents of file
     * @throws IOException
     * @throws InterruptedException 
     */
    private String getReleaseNotesFromFile(AbstractBuild build) throws IOException, InterruptedException {
        FilePath path = new FilePath(build.getWorkspace(), releaseNotesFile);
        return path.act(new ReadFileCallable());        
    }
    
    /**
     * This callable allows us to read files from other nodes - ie. Jenkins slaves.
     */
    private static final class ReadFileCallable implements FileCallable<String> {
        public final static String ERROR_READING = "<Error Reading File>";
        
        @Override 
        public String invoke(File f, VirtualChannel channel) {
            try {
                return String.join("\n", Files.readAllLines(f.toPath()));
            } catch (IOException ex) {
                return ERROR_READING;
            }
        }

        @Override
        public void checkRoles(RoleChecker rc) throws SecurityException {
            
        }
    }
    
    /**
     * Attempt to load release notes info from SCM.
     * @param build
     * @return 
     */
    private String getReleaseNotesFromScm(AbstractBuild build) {
        StringBuilder notes = new StringBuilder();
        AbstractProject project = build.getProject();
        AbstractBuild lastSuccessfulBuild = (AbstractBuild)project.getLastSuccessfulBuild();
        AbstractBuild currentBuild = null;
        if (lastSuccessfulBuild == null) {
            AbstractBuild lastBuild = (AbstractBuild)project.getLastBuild();
            currentBuild = lastBuild;
        }
        else
        {
            currentBuild = lastSuccessfulBuild.getNextBuild();
        }
        if (currentBuild != null) {
            while (currentBuild != build)
            {
                ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = currentBuild.getChangeSet();
                for(Object item : changeSet.getItems())
                {
                    ChangeLogSet.Entry entry = (ChangeLogSet.Entry)item;
                    notes.append(entry.getMsg()).append("\n");
                }
                currentBuild = currentBuild.getNextBuild();
            }
        }
        
        return notes.toString();
    }
    
    /**
     * Descriptor for {@link OctopusDeployReleaseRecorder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private String octopusHost;
        private String apiKey;
        private boolean loadedConfig;
        private OctopusApi api;
        private static final String PROJECT_RELEASE_VALIDATION_MESSAGE = "Project must be set to validate release.";
        
        public DescriptorImpl() {
            load();
        }
        
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "OctopusDeploy Release";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws Descriptor.FormException {
            save();
            return true;
        }
        
        /**
        * Loads the OctopusDeployPlugin descriptor and pulls configuration from it
        * for API Key, and Host.
        */
        private void setGlobalConfiguration() {
            // NOTE  - This method is not being called from the constructor due 
            // to a circular dependency issue on startup
            if (!loadedConfig) { 
                OctopusDeployPlugin.DescriptorImpl descriptor = (OctopusDeployPlugin.DescriptorImpl) 
                       Jenkins.getInstance().getDescriptor(OctopusDeployPlugin.class);
                apiKey = descriptor.getApiKey();
                octopusHost = descriptor.getOctopusHost();
                api = new OctopusApi(octopusHost, apiKey);
                loadedConfig = true;
            }
        }
        
        /**
         * Check that the project field is not empty and represents an actual project.
         * @param project The name of the project.
         * @return FormValidation message if not ok.
         */
        public FormValidation doCheckProject(@QueryParameter String project) {
            setGlobalConfiguration(); 
            project = project.trim(); 
            OctopusValidator validator = new OctopusValidator(api);
            return validator.validateProject(project);
        }
        
        /**
         * Check that the releaseVersion field is not empty.
         * @param releaseVersion release version.
         * @param project  The name of the project.
         * @return Ok if not empty, error otherwise.
         */
        public FormValidation doCheckReleaseVersion(@QueryParameter String releaseVersion, @QueryParameter String project) {
            setGlobalConfiguration();
            releaseVersion = releaseVersion.trim();
            if (project == null || project.isEmpty()) {
                return FormValidation.warning(PROJECT_RELEASE_VALIDATION_MESSAGE);
            }
            com.octopusdeploy.api.Project p;
            try {
                p = api.getProjectByName(project);
                if (p == null) {
                    return FormValidation.warning(PROJECT_RELEASE_VALIDATION_MESSAGE);
                }
            } catch (Exception ex) {
                return FormValidation.warning(PROJECT_RELEASE_VALIDATION_MESSAGE);
            }
            
            OctopusValidator validator = new OctopusValidator(api);
            return validator.validateRelease(releaseVersion, p.getId(), OctopusValidator.ReleaseExistenceRequirement.MustNotExist);
        }
        
        /**
         * Check that the releaseNotesFile field is not empty.
         * @param releaseNotesFile The path to the release notes file, relative to the WS.
         * @return Ok if not empty, error otherwise.
         */
        public FormValidation doCheckReleaseNotesFile(@QueryParameter String releaseNotesFile) {
            if (releaseNotesFile.isEmpty()) {
                return FormValidation.error("Please provide a project notes file.");
            }

            return FormValidation.ok();
        }
        
        /**
         * Check that the environment field is not empty, and represents a real environment.
         * @param environment The name of the environment.
         * @return FormValidation message if not ok.
         */
        public FormValidation doCheckEnvironment(@QueryParameter String environment) {
            setGlobalConfiguration();
            environment = environment.trim(); 
            OctopusValidator validator = new OctopusValidator(api);
            return validator.validateEnvironment(environment);
        }
    }
}